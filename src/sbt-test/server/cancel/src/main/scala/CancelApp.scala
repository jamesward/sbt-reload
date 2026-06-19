object CancelApp:
  def main(args: Array[String]): Unit =
    val marker = java.io.File("target/pid.txt")
    val tmp    = java.io.File("target/pid.txt.tmp")
    marker.getParentFile.mkdirs()
    val pid = ProcessHandle.current().pid()
    java.nio.file.Files.writeString(tmp.toPath, pid.toString)
    java.nio.file.Files.move(
      tmp.toPath,
      marker.toPath,
      java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
    println(s"CancelApp started, pid=$pid")
    // Stay alive so the background fork keeps running until explicitly stopped.
    Thread.currentThread().join()
