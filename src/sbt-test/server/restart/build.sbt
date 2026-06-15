scalaVersion := "3.8.4"
run / mainClass := Some("WebApp")

val waitForFile = taskKey[Unit]("Wait for server-started.txt")
waitForFile := {
  val marker = baseDirectory.value / "target" / "server-started.txt"
  var attempts = 0
  while (!marker.exists() && attempts < 30) {
    Thread.sleep(1000)
    attempts += 1
  }
  if (!marker.exists()) sys.error("server-started.txt never appeared")
}
