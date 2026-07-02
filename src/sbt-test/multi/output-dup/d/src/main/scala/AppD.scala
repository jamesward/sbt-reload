object AppD:
  def main(args: Array[String]): Unit =
    println("SHARED_BANNER")
    println("UNIQUE_D")
    val marker = java.io.File("target/started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    Thread.currentThread().join()
