// modlypass dumb chat client (MVG): reads prompts from stdin, sends them with the conversation so far
// to an OpenAI-compatible /chat/completions endpoint with streaming, and prints the reply as it arrives.
//
//   scala run client -- [--url BASE] [--model NAME]
//
// Commands at the prompt: /reset forgets the conversation, /quit (or end of input) exits.

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

@main def chatWithModel(args: String*): Unit = Client.run(args)

object Client {

  type Role = String
  case class Message(role: Role, content: String)

  case class Settings(url: String = "http://localhost:8099/modlypass/v1", model: String = "qwen3-coder:30b")

  enum Sse:
    case Delta(text: String)
    case Done
    case Ignore
    case Error(message: String)

  val usage: String =
    """usage: scala run client -- [--url BASE] [--model NAME]
      |  --url BASE    OpenAI-compatible base URL (default http://localhost:8099/modlypass/v1)
      |  --model NAME  model name (default qwen3-coder:30b)""".stripMargin

  // ---------- pure core ----------

  def parseArgs(args: Seq[String], base: Settings = Settings()): Either[String, Settings] =
    args match
      case Seq() => Right(base)
      case Seq("--help", _*) | Seq("-h", _*) => Left(usage)
      case Seq(flag) => Left(s"missing value for $flag\n$usage")
      case Seq("--url", v, rest*) => parseArgs(rest, base.copy(url = v.stripSuffix("/")))
      case Seq("--model", v, rest*) => parseArgs(rest, base.copy(model = v))
      case Seq(other, _*) => Left(s"unknown option $other\n$usage")

  def requestJson(model: String, history: Seq[Message]): String =
    ujson.Obj(
      "model" -> model,
      "stream" -> true,
      "messages" -> ujson.Arr.from(history.map(m => ujson.Obj("role" -> m.role, "content" -> m.content)))
    ).render()

  /** Interpret one line of a server-sent event stream from /chat/completions. */
  def parseSseLine(line: String): Sse =
    if !line.startsWith("data:") then Sse.Ignore
    else
      val payload = line.stripPrefix("data:").trim
      if payload == "[DONE]" then Sse.Done
      else
        try
          val json = ujson.read(payload)
          json.obj.get("error") match
            case Some(err) => Sse.Error(err.obj.get("message").map(_.str).getOrElse(err.render()))
            case None =>
              val text = for
                choices <- json.obj.get("choices")
                first <- choices.arr.headOption
                delta <- first.obj.get("delta")
                content <- delta.obj.get("content")
                if !content.isNull
              yield content.str
              text.filter(_.nonEmpty).map(Sse.Delta(_)).getOrElse(Sse.Ignore)
        catch case e: Exception => Sse.Error(s"unparseable stream line: ${e.getMessage}")

  // ---------- effectful driver ----------

  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  def run(args: Seq[String]): Unit =
    parseArgs(args) match
      case Left(msg) =>
        System.err.println(msg)
        sys.exit(1)
      case Right(s) =>
        println(s"modlypass chat client: ${s.url}  model ${s.model}  (/reset, /quit)")
        chatLoop(s, Vector.empty)

  @annotation.tailrec
  private def chatLoop(s: Settings, history: Vector[Message]): Unit =
    print("\n> ")
    Console.out.flush()
    Option(scala.io.StdIn.readLine()).map(_.trim) match
      case None | Some("/quit") => println()
      case Some("") => chatLoop(s, history)
      case Some("/reset") =>
        println("(conversation forgotten)")
        chatLoop(s, Vector.empty)
      case Some(prompt) =>
        val asked = history :+ Message("user", prompt)
        ask(s, asked) match
          case Right(reply) => chatLoop(s, asked :+ Message("assistant", reply))
          case Left(err) =>
            println(s"\n[error] $err")
            chatLoop(s, history) // forget the failed turn
    end match

  /** Send the conversation, print the reply as it streams in, and return it. */
  private def ask(s: Settings, history: Seq[Message]): Either[String, String] =
    val req = HttpRequest.newBuilder(URI.create(s"${s.url}/chat/completions"))
      .timeout(Duration.ofMinutes(10)) // until headers arrive; the model may need to load first
      .header("Content-Type", "application/json")
      .header("Accept", "text/event-stream")
      .POST(HttpRequest.BodyPublishers.ofString(requestJson(s.model, history)))
      .build()
    val t0 = System.nanoTime()
    try
      val res = client.send(req, HttpResponse.BodyHandlers.ofLines())
      if res.statusCode() / 100 != 2 then
        import scala.jdk.CollectionConverters.*
        Left(s"HTTP ${res.statusCode()}: ${res.body().iterator().asScala.mkString("\n").take(500)}")
      else
        import scala.jdk.CollectionConverters.*
        // Local mutation while consuming the stream: printing as we go is the whole point here.
        val reply = StringBuilder()
        var firstTokenMs = -1L
        var error = Option.empty[String]
        scala.util.Using.resource(res.body()) { lines =>
          lines.iterator().asScala.map(parseSseLine).takeWhile(e => e != Sse.Done && error.isEmpty).foreach {
            case Sse.Delta(text) =>
              if firstTokenMs < 0 then firstTokenMs = (System.nanoTime() - t0) / 1000000
              print(text)
              Console.out.flush()
              reply ++= text
            case Sse.Error(msg) => error = Some(msg)
            case _ => ()
          }
        }
        val totalS = (System.nanoTime() - t0) / 1e9
        println(f"\n[${reply.length} chars, first token $firstTokenMs ms, total $totalS%.1f s]")
        error.toLeft(reply.toString)
    catch case e: Exception => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
}
