ThisBuild / scalaVersion := "3.8.4"

val waitForStarted = taskKey[Unit]("Wait until the project's pid file is written")
val checkAlive     = taskKey[Unit]("Verify the recorded PID is still alive")

val markerSettings = Seq(
  waitForStarted := {
    val log    = streams.value.log
    val marker = baseDirectory.value / "target" / "pid.txt"
    var attempts = 0
    while (!marker.exists() && attempts < 60) {
      Thread.sleep(500)
      attempts += 1
    }
    if (!marker.exists()) sys.error(s"$marker never appeared")
    log.info(s"waitForStarted: $marker present")
  },
  checkAlive := {
    val log    = streams.value.log
    val marker = baseDirectory.value / "target" / "pid.txt"
    if (!marker.exists()) sys.error(s"$marker doesn't exist")
    val pid = scala.io.Source.fromFile(marker).mkString.trim.toLong
    val handle = ProcessHandle.of(pid)
    val alive  = handle.isPresent && handle.get.isAlive
    log.info(s"checkAlive: $marker pid=$pid alive=$alive")
    if (!alive) sys.error(s"Process $pid (from $marker) is not alive")
  }
)

lazy val a = (project in file("a"))
  .settings(markerSettings*)
  .settings(
    run / mainClass := Some("AppA")
  )

lazy val b = (project in file("b"))
  .settings(markerSettings*)
  .settings(
    run / mainClass := Some("AppB")
  )

lazy val root = (project in file("."))
  .aggregate(a, b)
