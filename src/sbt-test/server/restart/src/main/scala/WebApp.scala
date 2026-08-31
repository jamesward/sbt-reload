object WebApp:
  def main(args: Array[String]): Unit =
    println("App started")
    val marker = java.io.File("target/server-started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    Thread.currentThread().join()
