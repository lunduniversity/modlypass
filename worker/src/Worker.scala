// modlypass worker: starts an Ollama model server on an HPC GPU node and checks that it really runs
// on the GPU. Run it inside a Slurm job AFTER `module load ollama/...` (module is a shell function,
// so it cannot be called from here).
//
//   scala run worker -- [--bind 127.0.0.1|0.0.0.0] [--port N] [--model NAME] [--ctx N] ...
//
// Pure core (argument parsing, JSON interpretation, hint text) is separated from the effectful
// driver (`start`), which spawns processes with os-lib and probes Ollama with the JDK HttpClient.

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

@main def startModelWorker(args: String*): Unit = Worker.run(args)

object Worker {

  type Host = String
  type Port = Int
  type ModelName = String

  case class Settings(
    bind: Host = "127.0.0.1",
    port: Port = 11500,
    model: ModelName = "qwen3-coder:30b",
    contextLength: Int = 65536,
    keepAlive: String = "1h",
    modelsDir: os.Path = os.home / ".ollama" / "models",
    logFile: os.Path = os.home / "modlypass" / "ollama.log",
    infoFile: os.Path = os.home / "modlypass" / "worker.properties",
    loginHost: Host = "cosmos.lunarc.lu.se"
  )

  val usage: String =
    """usage: scala run worker -- [options]
      |  --bind HOST        127.0.0.1 (default, needs ssh to the node) or 0.0.0.0 (reachable by all cluster users)
      |  --port N           Ollama port on the node (default 11500; not 11434, the node may be shared)
      |  --model NAME       model to load (default qwen3-coder:30b)
      |  --ctx N            context length in tokens (default 65536)
      |  --keep-alive DUR   how long Ollama keeps the model loaded when idle (default 1h)
      |  --models-dir DIR   OLLAMA_MODELS (default ~/.ollama/models)
      |  --log FILE         Ollama server log (default ~/modlypass/ollama.log)
      |  --info FILE        where node and port are written (default ~/modlypass/worker.properties)
      |  --login-host HOST  HPC login node, used in the printed tunnel command (default cosmos.lunarc.lu.se)""".stripMargin

  // ---------- pure core ----------

  def parseArgs(args: Seq[String], base: Settings = Settings()): Either[String, Settings] =
    args match
      case Seq() => Right(base)
      case Seq("--help", _*) | Seq("-h", _*) => Left(usage)
      case Seq(flag) => Left(s"missing value for $flag\n$usage")
      case Seq(flag, value, rest*) => applyFlag(base, flag, value).flatMap(parseArgs(rest, _))

  private def applyFlag(s: Settings, flag: String, value: String): Either[String, Settings] =
    flag match
      case "--bind" =>
        if value == "127.0.0.1" || value == "0.0.0.0" then Right(s.copy(bind = value))
        else Left(s"--bind must be 127.0.0.1 or 0.0.0.0, got $value")
      case "--port" =>
        value.toIntOption.filter(p => p > 1024 && p < 65536)
          .toRight(s"--port must be a number in 1025..65535, got $value")
          .map(p => s.copy(port = p))
      case "--model" => Right(s.copy(model = value))
      case "--ctx" =>
        value.toIntOption.filter(_ >= 2048)
          .toRight(s"--ctx must be a number >= 2048, got $value")
          .map(n => s.copy(contextLength = n))
      case "--keep-alive" => Right(s.copy(keepAlive = value))
      case "--models-dir" => Right(s.copy(modelsDir = os.Path(value, os.pwd)))
      case "--log" => Right(s.copy(logFile = os.Path(value, os.pwd)))
      case "--info" => Right(s.copy(infoFile = os.Path(value, os.pwd)))
      case "--login-host" => Right(s.copy(loginHost = value))
      case other => Left(s"unknown option $other\n$usage")

  /** Environment for `ollama serve`. OLLAMA_HOST decides both bind address and port. */
  def ollamaEnv(s: Settings): Map[String, String] = Map(
    "OLLAMA_HOST" -> s"${s.bind}:${s.port}",
    "OLLAMA_MODELS" -> s.modelsDir.toString,
    "OLLAMA_CONTEXT_LENGTH" -> s.contextLength.toString,
    "OLLAMA_KEEP_ALIVE" -> s.keepAlive
  )

  /** Ollama names without a tag mean `:latest`. */
  def sameModel(a: ModelName, b: ModelName): Boolean =
    def norm(m: ModelName) = if m.contains(':') then m else s"$m:latest"
    norm(a) == norm(b)

  private def field(m: ujson.Value, key: String): Option[ujson.Value] = m.obj.get(key)

  private def names(m: ujson.Value): List[ModelName] =
    List("name", "model").flatMap(k => field(m, k).map(_.str))

  /** Is `model` among the models in an /api/tags reply? */
  def hasModel(tagsJson: String, model: ModelName): Either[String, Boolean] =
    try Right(ujson.read(tagsJson)("models").arr.exists(m => names(m).exists(sameModel(_, model))))
    catch case e: Exception => Left(s"unparseable /api/tags reply: ${e.getMessage}")

  /** Share of the loaded model that sits in GPU memory (1.0 = fully on GPU), from an /api/ps reply. */
  def gpuShare(psJson: String, model: ModelName): Either[String, Double] =
    try
      ujson.read(psJson)("models").arr.find(m => names(m).exists(sameModel(_, model))) match
        case None => Left(s"model $model is not loaded")
        case Some(m) =>
          val size = field(m, "size").map(_.num).getOrElse(0.0)
          val vram = field(m, "size_vram").map(_.num).getOrElse(0.0)
          if size <= 0 then Left("Ollama reports model size 0") else Right(vram / size)
    catch case e: Exception => Left(s"unparseable /api/ps reply: ${e.getMessage}")

  /** The SSH command to run on the gateway host to reach this worker. */
  def tunnelCommand(user: String, node: Host, s: Settings): String =
    if s.bind == "127.0.0.1" then s"ssh -N -L ${s.port}:localhost:${s.port} -J $user@${s.loginHost} $user@$node"
    else s"ssh -N -L ${s.port}:$node:${s.port} $user@${s.loginHost}"

  /** Contents of the info file that tells the gateway side where the worker is. */
  def infoText(node: Host, s: Settings, startedAt: String): String =
    List(
      s"node=$node",
      s"bind=${s.bind}",
      s"port=${s.port}",
      s"model=${s.model}",
      s"contextLength=${s.contextLength}",
      s"startedAt=$startedAt"
    ).mkString("", "\n", "\n")

  // ---------- effectful driver ----------

  def run(args: Seq[String]): Unit =
    parseArgs(args) match
      case Left(msg) => fail(msg)
      case Right(s) => start(s)

  private def say(msg: String): Unit = println(s"[worker] $msg")

  private def fail(msg: String): Nothing =
    System.err.println(s"[worker] $msg")
    sys.exit(1)

  private def start(s: Settings): Unit = {
    val node = sys.env.getOrElse("SLURMD_NODENAME", java.net.InetAddress.getLocalHost.getHostName)
    say(s"node $node, Slurm job ${sys.env.getOrElse("SLURM_JOB_ID", "(none)")}")

    val preflight = for
      gpus <- gpuNames()
      _ = gpus.foreach(g => say(s"GPU: $g"))
      version <- ollamaVersion()
      _ = say(s"ollama: $version")
      _ <- portFree(s.bind, s.port)
    yield ()
    preflight.left.foreach(fail)

    os.makeDir.all(s.logFile / os.up)
    say(s"starting ollama serve on ${s.bind}:${s.port}, log: ${s.logFile}")
    // destroyOnExit (os-lib default) stops Ollama when this JVM exits, e.g. on Ctrl-C.
    val proc = os.proc("ollama", "serve").spawn(env = ollamaEnv(s), stdout = s.logFile, mergeErrIntoOut = true)

    val base = s"http://127.0.0.1:${s.port}"
    val ready = for
      _ <- waitUntilUp(proc, base, s.logFile, Duration.ofSeconds(60))
      tags <- Http.get(s"$base/api/tags", Duration.ofSeconds(10))
      present <- hasModel(tags, s.model)
      _ <- if present then Right(()) else Left(missingModelHint(s))
      loadSeconds <- loadModel(base, s)
      _ = say(f"model ${s.model} loaded in $loadSeconds%.1f s")
      ps <- Http.get(s"$base/api/ps", Duration.ofSeconds(10))
      share <- gpuShare(ps, s.model)
    yield share

    ready match
      case Left(msg) =>
        proc.destroy()
        fail(msg)
      case Right(share) =>
        if share >= 0.99 then say(f"${share * 100}%.0f%% of the model is on the GPU")
        else say(f"WARNING: only ${share * 100}%.0f%% of the model is on the GPU; the rest runs on CPU and will be slow")
        os.write.over(s.infoFile, infoText(node, s, java.time.Instant.now().toString), createFolders = true)
        val user = System.getProperty("user.name")
        say(s"ready: OpenAI-compatible API at $base/v1 on $node")
        say(s"info written to ${s.infoFile}")
        say(s"on the gateway host, run:  ${tunnelCommand(user, node, s)}")
        if s.bind == "0.0.0.0" then say("NOTE: bound to 0.0.0.0, so other cluster users can reach this server while it runs")
        say("Ctrl-C stops the worker and Ollama")
        proc.waitFor()
        say(s"ollama exited with code ${proc.exitCode()}")
  }

  private def gpuNames(): Either[String, List[String]] =
    try
      val r = os.proc("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader")
        .call(check = false, stderr = os.Pipe)
      val lines = r.out.lines().map(_.trim).filter(_.nonEmpty).toList
      if r.exitCode == 0 && lines.nonEmpty then Right(lines)
      else Left(s"nvidia-smi found no GPU (exit ${r.exitCode}): ${r.err.text().trim}")
    catch case e: java.io.IOException => Left(s"nvidia-smi not available, is this a GPU node? (${e.getMessage})")

  private def ollamaVersion(): Either[String, String] =
    try
      val r = os.proc("ollama", "--version").call(check = false, mergeErrIntoOut = true)
      // Without a running server, ollama also prints a "could not connect" warning; keep the version line.
      val line = r.out.lines().find(_.contains("version")).getOrElse(r.out.text().trim)
      Right(line.trim.stripPrefix("Warning:").trim)
    catch case e: java.io.IOException => Left(s"ollama not found on PATH; run `module load ollama/<version>` first (${e.getMessage})")

  private def portFree(bind: Host, port: Port): Either[String, Unit] =
    try
      java.net.ServerSocket(port, 1, java.net.InetAddress.getByName(bind)).close()
      Right(())
    catch case e: java.io.IOException => Left(s"port $port on $bind is taken; choose another with --port (${e.getMessage})")

  private def waitUntilUp(proc: os.SubProcess, base: String, log: os.Path, timeout: Duration): Either[String, Unit] =
    val deadline = System.nanoTime() + timeout.toNanos
    def tailOfLog = if os.exists(log) then os.read.lines(log).takeRight(15).mkString("\n") else "(no log)"
    @annotation.tailrec
    def loop(): Either[String, Unit] =
      if !proc.isAlive() then Left(s"ollama serve exited with code ${proc.exitCode()}; last log lines:\n$tailOfLog")
      else if Http.get(s"$base/api/version", Duration.ofSeconds(2)).isRight then Right(())
      else if System.nanoTime() > deadline then Left(s"ollama did not answer within ${timeout.toSeconds} s; last log lines:\n$tailOfLog")
      else
        Thread.sleep(500)
        loop()
    loop()

  private def loadModel(base: String, s: Settings): Either[String, Double] =
    // A generate request without a prompt only loads the model; it can take minutes from disk.
    val body = ujson.Obj("model" -> s.model, "keep_alive" -> s.keepAlive, "stream" -> false).render()
    val t0 = System.nanoTime()
    Http.postJson(s"$base/api/generate", body, Duration.ofMinutes(10)).map(_ => (System.nanoTime() - t0) / 1e9)

  private def missingModelHint(s: Settings): String =
    s"""model ${s.model} not found in ${s.modelsDir}.
       |Pull it into that directory, e.g. on the login node (needs internet):
       |  OLLAMA_MODELS=${s.modelsDir} ollama serve &   then   ollama pull ${s.model}""".stripMargin

  /** Thin JDK HttpClient wrapper. Left carries the reason; a non-2xx status is a failure, never data. */
  object Http {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    def get(url: String, timeout: Duration): Either[String, String] =
      send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build())

    def postJson(url: String, body: String, timeout: Duration): Either[String, String] =
      send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build())

    private def send(req: HttpRequest): Either[String, String] =
      try
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        if res.statusCode() / 100 == 2 then Right(res.body())
        else Left(s"${req.method()} ${req.uri()} -> HTTP ${res.statusCode()}: ${res.body().take(300)}")
      catch case e: Exception => Left(s"${req.method()} ${req.uri()} -> ${e.getClass.getSimpleName}: ${e.getMessage}")
  }
}
