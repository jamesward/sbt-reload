object TestApp:
  def main(args: Array[String]): Unit =
    val marker = java.io.File("target/test-marker.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    // Keep running until killed
    while true do Thread.sleep(1000)
