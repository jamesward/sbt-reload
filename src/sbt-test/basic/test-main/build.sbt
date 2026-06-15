scalaVersion := "3.8.4"
Test / run / mainClass := Some("TestApp")

val waitForFile = taskKey[Unit]("Wait for test-marker.txt")
waitForFile := {
  val marker = baseDirectory.value / "target" / "test-marker.txt"
  var attempts = 0
  while (!marker.exists() && attempts < 30) {
    Thread.sleep(1000)
    attempts += 1
  }
  if (!marker.exists()) sys.error("test-marker.txt never appeared")
}
