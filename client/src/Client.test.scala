class ClientTest extends munit.FunSuite {
  import Client.*

  test("defaults and flags") {
    assertEquals(parseArgs(Nil).toOption.get.model, "qwen3-coder:30b")
    val s = parseArgs(Seq("--url", "http://h:1/x/v1/", "--model", "m")).toOption.get
    assertEquals(s.url, "http://h:1/x/v1")
    assertEquals(s.model, "m")
    assert(parseArgs(Seq("--url")).isLeft)
    assert(parseArgs(Seq("--bad", "x")).isLeft)
  }

  test("request JSON has model, stream=true and the whole conversation in order") {
    val json = ujson.read(requestJson("m", Seq(Message("user", "hi"), Message("assistant", "yo"), Message("user", "q"))))
    assertEquals(json("model").str, "m")
    assertEquals(json("stream").bool, true)
    assertEquals(json("messages").arr.map(_("content").str).toList, List("hi", "yo", "q"))
    assertEquals(json("messages").arr.map(_("role").str).toList, List("user", "assistant", "user"))
  }

  test("SSE lines: text delta, done, role-only delta, non-data, error, garbage") {
    assertEquals(parseSseLine("""data: {"choices":[{"delta":{"content":"Hej"}}]}"""), Sse.Delta("Hej"))
    assertEquals(parseSseLine("data: [DONE]"), Sse.Done)
    assertEquals(parseSseLine("""data: {"choices":[{"delta":{"role":"assistant","content":""}}]}"""), Sse.Ignore)
    assertEquals(parseSseLine("""data: {"choices":[{"delta":{"content":null}}]}"""), Sse.Ignore)
    assertEquals(parseSseLine(""), Sse.Ignore)
    assertEquals(parseSseLine(": keep-alive"), Sse.Ignore)
    assertEquals(parseSseLine("""data: {"error":{"message":"model not found"}}"""), Sse.Error("model not found"))
    assert(parseSseLine("data: {broken").isInstanceOf[Sse.Error])
  }
}
