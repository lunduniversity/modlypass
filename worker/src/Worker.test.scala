class WorkerTest extends munit.FunSuite {
  import Worker.*

  test("no arguments give the defaults") {
    val s = parseArgs(Nil).toOption.get
    assertEquals(s.bind, "127.0.0.1")
    assertEquals(s.port, 11500)
    assertEquals(s.model, "qwen3-coder:30b")
    assertEquals(s.contextLength, 65536)
  }

  test("flags override the defaults") {
    val s = parseArgs(Seq("--bind", "0.0.0.0", "--port", "12345", "--model", "m:1", "--ctx", "8192")).toOption.get
    assertEquals((s.bind, s.port, s.model, s.contextLength), ("0.0.0.0", 12345, "m:1", 8192))
  }

  test("bad values are refused with a reason") {
    assert(parseArgs(Seq("--bind", "10.0.0.1")).left.exists(_.contains("--bind")))
    assert(parseArgs(Seq("--port", "80")).left.exists(_.contains("--port")))
    assert(parseArgs(Seq("--port", "x")).left.exists(_.contains("--port")))
    assert(parseArgs(Seq("--ctx", "100")).left.exists(_.contains("--ctx")))
    assert(parseArgs(Seq("--port")).left.exists(_.contains("missing value")))
    assert(parseArgs(Seq("--nope", "1")).left.exists(_.contains("unknown option")))
    assert(parseArgs(Seq("--help")).left.exists(_.startsWith("usage")))
  }

  test("runtime files default to the state directory, outside any git clone") {
    val s = Settings()
    assert(s.logFile.toString.endsWith("/modlypass/ollama.log"))
    assert(s.logFile.startsWith(stateDir) && s.infoFile.startsWith(stateDir))
    assert(!s.logFile.startsWith(os.home / "modlypass"))
  }

  test("ollama environment carries bind address, port and context length") {
    val env = ollamaEnv(Settings(bind = "0.0.0.0", port = 12000, contextLength = 4096))
    assertEquals(env("OLLAMA_HOST"), "0.0.0.0:12000")
    assertEquals(env("OLLAMA_CONTEXT_LENGTH"), "4096")
    assert(env.contains("OLLAMA_MODELS"))
  }

  test("model names without a tag mean latest") {
    assert(sameModel("llama3", "llama3:latest"))
    assert(sameModel("qwen3-coder:30b", "qwen3-coder:30b"))
    assert(!sameModel("qwen3-coder:30b", "qwen3-coder:latest"))
  }

  val tags = """{"models":[{"name":"qwen3-coder:30b","model":"qwen3-coder:30b","size":19000000000}]}"""

  test("hasModel finds a pulled model and reports a missing one") {
    assertEquals(hasModel(tags, "qwen3-coder:30b"), Right(true))
    assertEquals(hasModel(tags, "llama3"), Right(false))
    assert(hasModel("not json", "x").isLeft)
  }

  test("gpuShare: fully on GPU, partly on GPU, not loaded, garbage") {
    val full = """{"models":[{"name":"qwen3-coder:30b","size":100,"size_vram":100}]}"""
    val part = """{"models":[{"name":"qwen3-coder:30b","size":100,"size_vram":25}]}"""
    assertEquals(gpuShare(full, "qwen3-coder:30b"), Right(1.0))
    assertEquals(gpuShare(part, "qwen3-coder:30b"), Right(0.25))
    assert(gpuShare("""{"models":[]}""", "qwen3-coder:30b").left.exists(_.contains("not loaded")))
    assert(gpuShare("{", "qwen3-coder:30b").isLeft)
  }

  test("tunnel command: jump to the node when bound to localhost, else via the login node") {
    val local = tunnelCommand("u", "cg01", Settings(bind = "127.0.0.1", port = 11500, loginHost = "login.example"))
    assertEquals(local, "ssh -N -L 11500:localhost:11500 -J u@login.example u@cg01")
    val open = tunnelCommand("u", "cg01", Settings(bind = "0.0.0.0", port = 11500, loginHost = "login.example"))
    assertEquals(open, "ssh -N -L 11500:cg01:11500 u@login.example")
  }

  test("info text is key=value lines that java.util.Properties can read") {
    val text = infoText("cg01", Settings(port = 11501), "2026-11-27T10:00:00Z")
    val p = java.util.Properties()
    p.load(java.io.StringReader(text))
    assertEquals(p.getProperty("node"), "cg01")
    assertEquals(p.getProperty("port"), "11501")
    assertEquals(p.getProperty("startedAt"), "2026-11-27T10:00:00Z")
  }
}
