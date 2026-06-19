object OutputApp:
  def main(args: Array[String]): Unit =
    // A distinctive line on stdout that reloadOutput's capture file must contain.
    println("OUTPUT_MARKER_LINE_42")
    // Marker file written AFTER the println so the test can wait for startup.
    val marker = java.io.File("target/app-started.txt")
    marker.getParentFile.mkdirs()
    marker.createNewFile()
    // Stay alive so the background fork keeps running.
    Thread.currentThread().join()
