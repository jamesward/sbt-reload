object AppA:
  def main(args: Array[String]): Unit =
    println("OUTPUT_MARKER_A")
    val marker = java.io.File("target/started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    Thread.currentThread().join()
