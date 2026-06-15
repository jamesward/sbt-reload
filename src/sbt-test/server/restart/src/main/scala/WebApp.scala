import com.sun.net.httpserver.{HttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress

object WebApp:
  def main(args: Array[String]): Unit =
    val server = HttpServer.create(InetSocketAddress(8123), 0)
    server.createContext("/", (exchange: HttpExchange) => {
      val response = "hello"
      exchange.sendResponseHeaders(200, response.length)
      val os = exchange.getResponseBody
      os.write(response.getBytes)
      os.close()
    })
    server.start()
    println(s"Server started on port 8123")
    // write marker file
    val marker = java.io.File("target/server-started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    // block forever
    Thread.currentThread().join()
