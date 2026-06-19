import sbt.nio.Keys.watchOnTermination
import sbt.nio.Watch

scalaVersion := "3.8.4"
run / mainClass := Some("CancelApp")

val waitForStart = taskKey[Unit]("Wait until pid.txt is present")
waitForStart := Def.uncached {
  val log    = streams.value.log
  val marker = baseDirectory.value / "target" / "pid.txt"
  var attempts = 0
  while (!marker.exists() && attempts < 60) { Thread.sleep(500); attempts += 1 }
  if (!marker.exists()) sys.error(s"$marker never appeared")
  log.info(s"waitForStart: ${scala.io.Source.fromFile(marker).mkString.trim}")
}

// Invoke the REAL `runReload / watchOnTermination` handler, exactly as sbt does
// when you press <enter> to cancel a `~runReload`. If the handler's job-matching
// is wrong, the forked app keeps running and `assertStopped` fails.
val simulateWatchCancel = taskKey[Unit]("Invoke runReload's watchOnTermination handler")
simulateWatchCancel := Def.uncached {
  val log = streams.value.log
  // Diagnostics: show the job's spawningTask vs. the key the handler matches on.
  val jobs = bgJobService.value.jobs
  jobs.foreach(j => log.info(s"simulateWatchCancel: job spawningTask = ${Def.showFullKey.show(j.spawningTask)}"))
  log.info(
    s"simulateWatchCancel: handler scope = " +
      Def.showFullKey.show((Compile / runReload / Keys.resolvedScoped).value)
  )
  val fn = (Compile / runReload / watchOnTermination).value
  val st = state.value
  fn(Watch.CancelWatch, "runReload", 1, st)
  ()
}

val assertStopped = taskKey[Unit]("Assert the forked app's process is dead")
assertStopped := Def.uncached {
  val log    = streams.value.log
  val marker = baseDirectory.value / "target" / "pid.txt"
  if (!marker.exists()) sys.error(s"$marker never appeared")
  val pid = scala.io.Source.fromFile(marker).mkString.trim.toLong
  def alive: Boolean = {
    val h = ProcessHandle.of(pid)
    h.isPresent && h.get.isAlive
  }
  var attempts = 0
  while (alive && attempts < 20) { Thread.sleep(500); attempts += 1 }
  if (alive)
    sys.error(s"forked app (pid=$pid) is still alive after watchOnTermination cancel")
  log.info(s"assertStopped: pid=$pid is no longer alive")
}
