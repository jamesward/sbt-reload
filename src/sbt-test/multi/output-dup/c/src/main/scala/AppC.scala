object AppC:
  def main(args: Array[String]): Unit =
    println("SHARED_BANNER")
    println("UNIQUE_C")
    val marker = java.io.File("target/started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    Thread.currentThread().join()
