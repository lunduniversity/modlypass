// modlypass gateway (MVG): a byte pipe from /modlypass/v1/* to an OpenAI-compatible backend,
// streaming replies back chunk by chunk. JDK only: com.sun.net.httpserver + java.net.http, virtual threads.
//
//   scala run gateway -- [--port N] [--backend URL] [--config FILE]
//
// Settings: command line > config file (~/.config/modlypass/settings.conf, KEY=value) > defaults.
// Security (iteration 0): listens on 127.0.0.1 only, and the relay also refuses any non-loopback
// client, so nobody but local users (and SSH tunnels) can spend the HPC allocation.

import java.net.{InetAddress, InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import com.sun.net.httpserver.{HttpExchange, HttpServer}

@main def runModelGateway(args: String*): Unit = Gateway.run(args)

object Gateway {

  val Version = "0.1.0-mvg"
  val Prefix = "/modlypass"
  val RelayPrefix = s"$Prefix/v1/"

  type Port = Int

  case class Settings(port: Port, backend: URI)

  /** One settings file shared by all modlypass parts (KEY=value lines). */
  val defaultConfigFile: java.nio.file.Path =
    java.nio.file.Path.of(System.getProperty("user.home"), ".config", "modlypass", "settings.conf")

  /** Tolerate shell-style quoting (KEY="value"), which java.util.Properties would keep as part of the value. */
  def unquote(v: String): String =
    val t = v.trim
    if t.length >= 2 && (t.head == '"' || t.head == '\'') && t.last == t.head then t.substring(1, t.length - 1) else t

  val defaults: Map[String, String] = Map("PORT" -> "8099", "BACKEND" -> "http://127.0.0.1:11500")

  val usage: String =
    """usage: scala run gateway -- [--port N] [--backend URL] [--config FILE]
      |  config file keys: PORT, BACKEND (default file ~/.config/modlypass/settings.conf)""".stripMargin

  // ---------- pure core ----------

  /** Command-line flags as config keys, plus an optional --config path. */
  def parseArgs(args: Seq[String]): Either[String, (overrides: Map[String, String], configFile: Option[String])] =
    @annotation.tailrec
    def loop(rest: Seq[String], acc: Map[String, String], cfg: Option[String]): Either[String, (overrides: Map[String, String], configFile: Option[String])] =
      rest match
        case Seq() => Right((overrides = acc, configFile = cfg))
        case Seq("--help", _*) | Seq("-h", _*) => Left(usage)
        case Seq(flag) => Left(s"missing value for $flag\n$usage")
        case Seq("--port", v, more*) => loop(more, acc + ("PORT" -> v), cfg)
        case Seq("--backend", v, more*) => loop(more, acc + ("BACKEND" -> v), cfg)
        case Seq("--config", v, more*) => loop(more, acc, Some(v))
        case Seq(other, _*) => Left(s"unknown option $other\n$usage")
    loop(args, Map.empty, None)

  /** Later maps win: defaults, then config file, then command line. */
  def resolve(layers: Map[String, String]*): Either[String, Settings] =
    val merged = layers.foldLeft(Map.empty[String, String])(_ ++ _)
    for
      port <- merged.get("PORT").flatMap(_.trim.toIntOption).filter(p => p > 1024 && p < 65536)
        .toRight(s"PORT must be a number in 1025..65535, got ${merged.getOrElse("PORT", "(none)")}")
      backend <- parseBackend(merged.getOrElse("BACKEND", ""))
    yield Settings(port, backend)

  private def parseBackend(s: String): Either[String, URI] =
    try
      val u = URI.create(s.trim.stripSuffix("/"))
      if (u.getScheme == "http" || u.getScheme == "https") && u.getHost != null then Right(u)
      else Left(s"BACKEND must be an http(s) URL, got '$s'")
    catch case _: IllegalArgumentException => Left(s"BACKEND is not a valid URL: '$s'")

  /** Map a gateway request path to the backend: /modlypass/v1/x?q -> BACKEND/v1/x?q. None if outside the relay. */
  def backendUri(backend: URI, path: String, rawQuery: Option[String]): Option[URI] =
    if !path.startsWith(RelayPrefix) || path.contains("..") then None
    else
      val suffix = path.stripPrefix(Prefix) // keeps the leading /v1/
      Some(URI.create(backend.toString + suffix + rawQuery.map("?" + _).getOrElse("")))

  def isLoopback(addr: InetAddress): Boolean = addr.isLoopbackAddress

  /** Request headers worth forwarding. Hop-by-hop and restricted ones (Host, Content-Length, ...) are not. */
  val forwardedHeaders: List[String] = List("Content-Type", "Accept")

  def jsonString(s: String): String =
    val escaped = s.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }
    s"\"$escaped\""

  def healthJson(backendUp: Either[String, Unit], uptimeSeconds: Long): String =
    val status = backendUp match
      case Right(_) => """"status":"ok","backend_reachable":true"""
      case Left(err) => s""""status":"degraded","backend_reachable":false,"error":${jsonString(err)}"""
    s"""{"service":"modlypass-gateway","version":"$Version",$status,"uptime_seconds":$uptimeSeconds}"""

  // ---------- effectful driver ----------

  private val startedAt = Instant.now()

  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build()

  def run(args: Seq[String]): Unit =
    val settings = for
      parsed <- parseArgs(args)
      file <- readConfig(parsed.configFile)
      s <- resolve(defaults, file, parsed.overrides)
    yield s
    settings match
      case Left(msg) =>
        System.err.println(s"[gateway] $msg")
        sys.exit(1)
      case Right(s) => serve(s)

  private def readConfig(explicit: Option[String]): Either[String, Map[String, String]] =
    val path = explicit.map(java.nio.file.Path.of(_)).getOrElse(defaultConfigFile)
    if !java.nio.file.Files.exists(path) then
      if explicit.isDefined then Left(s"config file not found: $path") else Right(Map.empty)
    else
      try
        val p = java.util.Properties()
        scala.util.Using.resource(java.nio.file.Files.newBufferedReader(path))(p.load)
        import scala.jdk.CollectionConverters.*
        Right(p.asScala.toMap.map((k, v) => k.trim -> unquote(v)))
      catch case e: java.io.IOException => Left(s"cannot read $path: ${e.getMessage}")

  private def serve(s: Settings): Unit =
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, s.port), 0)
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    server.createContext(s"$Prefix/health", ex => handle(ex)(health(ex, s)))
    server.createContext(RelayPrefix, ex => handle(ex)(relay(ex, s)))
    server.start()
    println(s"[gateway] modlypass gateway $Version on http://127.0.0.1:${s.port}$Prefix/  backend ${s.backend}")
    println(s"[gateway] OpenAI base URL: http://localhost:${s.port}${RelayPrefix.stripSuffix("/")}")

  /** Every request is logged as one metadata line (never content), and never leaks an exception. */
  private def handle(ex: HttpExchange)(body: => (status: Int, bytes: Long, firstByteMs: Long)): Unit =
    val t0 = System.nanoTime()
    val result =
      try body
      catch case e: Exception =>
        try respond(ex, 500, s"""{"error":${jsonString(e.toString)}}""") catch case _: Exception => ()
        (status = 500, bytes = 0L, firstByteMs = -1L)
    val ms = (System.nanoTime() - t0) / 1000000
    println(s"[gateway] ${Instant.now()} ${ex.getRequestMethod} ${ex.getRequestURI.getPath} -> ${result.status} " +
      s"ttfb=${result.firstByteMs}ms total=${ms}ms bytes=${result.bytes} from=${ex.getRemoteAddress.getAddress.getHostAddress}")
    ex.close()

  private def respond(ex: HttpExchange, status: Int, json: String): (status: Int, bytes: Long, firstByteMs: Long) =
    val bytes = json.getBytes("UTF-8")
    ex.getResponseHeaders.set("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    ex.getResponseBody.write(bytes)
    (status = status, bytes = bytes.length.toLong, firstByteMs = 0L)

  private def health(ex: HttpExchange, s: Settings): (status: Int, bytes: Long, firstByteMs: Long) =
    val probe =
      try
        val req = HttpRequest.newBuilder(URI.create(s"${s.backend}/v1/models")).timeout(Duration.ofSeconds(5)).GET().build()
        val res = client.send(req, HttpResponse.BodyHandlers.discarding())
        if res.statusCode() / 100 == 2 then Right(()) else Left(s"backend answered HTTP ${res.statusCode()}")
      catch case e: Exception => Left(s"backend unreachable: ${e.getClass.getSimpleName}")
    val uptime = Duration.between(startedAt, Instant.now()).toSeconds
    respond(ex, if probe.isRight then 200 else 503, healthJson(probe, uptime))

  private def relay(ex: HttpExchange, s: Settings): (status: Int, bytes: Long, firstByteMs: Long) =
    val uri = ex.getRequestURI
    if !isLoopback(ex.getRemoteAddress.getAddress) then respond(ex, 403, """{"error":"relay is loopback-only"}""")
    else backendUri(s.backend, uri.getPath, Option(uri.getRawQuery)) match
      case None => respond(ex, 404, """{"error":"not found"}""")
      case Some(target) => forward(ex, target)

  private def forward(ex: HttpExchange, target: URI): (status: Int, bytes: Long, firstByteMs: Long) =
    val t0 = System.nanoTime()
    val requestBody = ex.getRequestBody.readAllBytes()
    val builder = HttpRequest.newBuilder(target)
      .timeout(Duration.ofMinutes(10)) // until response headers arrive; a cold model load can take minutes
      .method(ex.getRequestMethod,
        if requestBody.isEmpty then HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(requestBody))
    forwardedHeaders.foreach(h => Option(ex.getRequestHeaders.getFirst(h)).foreach(v => builder.header(h, v)))

    val response =
      try Right(client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()))
      catch case e: Exception => Left(s"backend unreachable: ${e.getClass.getSimpleName}: ${e.getMessage}")

    response match
      case Left(err) => respond(ex, 502, s"""{"error":${jsonString(err)}}""")
      case Right(res) =>
        res.headers().firstValue("Content-Type").ifPresent(ct => ex.getResponseHeaders.set("Content-Type", ct))
        ex.getResponseHeaders.set("Cache-Control", "no-cache")
        ex.sendResponseHeaders(res.statusCode(), 0) // 0 = chunked: stream without knowing the length
        val firstByteMs = (System.nanoTime() - t0) / 1000000
        val bytes = scala.util.Using.resource(res.body())(in => pipe(in, ex.getResponseBody))
        (status = res.statusCode(), bytes = bytes, firstByteMs = firstByteMs)

  /** Copy and flush after every read, so each streamed chunk reaches the client at once. */
  private def pipe(in: java.io.InputStream, out: java.io.OutputStream): Long =
    val buf = new Array[Byte](8192)
    var total = 0L // local mutation: a tight copy loop, clearer than a fold over an iterator
    var n = in.read(buf)
    while n >= 0 do
      if n > 0 then
        out.write(buf, 0, n)
        out.flush()
        total += n
      n = in.read(buf)
    total
}
