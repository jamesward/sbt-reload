object AppB:
  def main(args: Array[String]): Unit =
    println("OUTPUT_MARKER_B")
    val marker = java.io.File("target/started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    Thread.currentThread().join()
