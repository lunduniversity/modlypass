class GatewayTest extends munit.FunSuite {
  import Gateway.*
  import java.net.{InetAddress, URI}

  test("defaults alone resolve to port 8099") {
    val s = resolve(defaults).toOption.get
    assertEquals(s.port, 8099)
    assertEquals(s.backend.toString, "http://127.0.0.1:11500")
  }

  test("command line beats config file beats defaults") {
    val file = Map("PORT" -> "9000", "BACKEND" -> "http://127.0.0.1:12000")
    val cli = Map("PORT" -> "9001")
    val s = resolve(defaults, file, cli).toOption.get
    assertEquals(s.port, 9001)
    assertEquals(s.backend.toString, "http://127.0.0.1:12000")
  }

  test("bad port and bad backend are refused") {
    assert(resolve(defaults, Map("PORT" -> "80")).isLeft)
    assert(resolve(defaults, Map("PORT" -> "abc")).isLeft)
    assert(resolve(defaults, Map("BACKEND" -> "ftp://x")).isLeft)
    assert(resolve(defaults, Map("BACKEND" -> "not a url")).isLeft)
  }

  test("parseArgs collects flags and the config path") {
    val p = parseArgs(Seq("--port", "9002", "--backend", "http://h:1", "--config", "/tmp/x.conf")).toOption.get
    assertEquals(p.overrides, Map("PORT" -> "9002", "BACKEND" -> "http://h:1"))
    assertEquals(p.configFile, Some("/tmp/x.conf"))
    assert(parseArgs(Seq("--port")).isLeft)
    assert(parseArgs(Seq("--what", "1")).isLeft)
  }

  test("relay paths map to the backend, others do not") {
    val b = URI.create("http://127.0.0.1:11500")
    assertEquals(backendUri(b, "/modlypass/v1/chat/completions", None).map(_.toString),
      Some("http://127.0.0.1:11500/v1/chat/completions"))
    assertEquals(backendUri(b, "/modlypass/v1/models", Some("a=1")).map(_.toString),
      Some("http://127.0.0.1:11500/v1/models?a=1"))
    assertEquals(backendUri(b, "/modlypass/health", None), None)
    assertEquals(backendUri(b, "/v1/models", None), None)
    assertEquals(backendUri(b, "/modlypass/v1/../api/pull", None), None)
  }

  test("loopback check accepts IPv4 and IPv6 loopback only") {
    assert(isLoopback(InetAddress.getByName("127.0.0.1")))
    assert(isLoopback(InetAddress.getByName("::1")))
    assert(!isLoopback(InetAddress.getByName("192.0.2.10")))
  }

  test("health JSON reports ok or the reason, with escaping") {
    assert(healthJson(Right(()), 5).contains(""""status":"ok""""))
    val bad = healthJson(Left("say \"hi\"\n"), 5)
    assert(bad.contains(""""status":"degraded""""))
    assert(bad.contains("""say \"hi\"\n"""))
  }
}
