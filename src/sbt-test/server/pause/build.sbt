scalaVersion := "3.8.4"
run / mainClass := Some("PauseApp")

// All of these tasks have side effects and/or read mutable filesystem state, so they
// MUST run on every invocation. Wrap each in Def.uncached so sbt 2.x's action cache
// doesn't memoize them across the repeated calls in the test script.

val waitForStarted = taskKey[Unit]("Wait until target/pid.txt is present and non-empty")
waitForStarted := Def.uncached {
  val log    = streams.value.log
  val marker = baseDirectory.value / "target" / "pid.txt"
  var attempts = 0
  while (!marker.exists() && attempts < 60) { Thread.sleep(500); attempts += 1 }
  if (!marker.exists()) sys.error(s"$marker never appeared")
  attempts = 0
  while (java.nio.file.Files.size(marker.toPath) == 0 && attempts < 60) { Thread.sleep(500); attempts += 1 }
  log.info(s"waitForStarted: ${scala.io.Source.fromFile(marker).mkString.trim}")
}

val recordPid = taskKey[Unit]("Snapshot current pid.txt -> baseline-pid.txt")
recordPid := Def.uncached {
  val log      = streams.value.log
  val pid      = baseDirectory.value / "target" / "pid.txt"
  val baseline = baseDirectory.value / "target" / "baseline-pid.txt"
  if (!pid.exists()) sys.error(s"$pid doesn't exist")
  java.nio.file.Files.copy(
    pid.toPath, baseline.toPath,
    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
  )
  log.info(s"recordPid: baseline=${scala.io.Source.fromFile(baseline).mkString.trim}")
}

val touchSource = taskKey[Unit]("Mutate the app source so the next compile produces new bytecode")
touchSource := Def.uncached {
  val log    = streams.value.log
  val src    = baseDirectory.value / "src" / "main" / "scala" / "PauseApp.scala"
  val original = scala.io.Source.fromFile(src).mkString
  val replacement = s"""println("touched-${System.currentTimeMillis()}")
    // MARKER"""
  val updated = original.replaceFirst("// MARKER", java.util.regex.Matcher.quoteReplacement(replacement))
  if (updated == original) sys.error(s"`// MARKER` not found in $src")
  java.nio.file.Files.writeString(src.toPath, updated)
  log.info(s"touchSource: rewrote $src")
}

def pidAlive(pid: Long): Boolean = {
  val h = ProcessHandle.of(pid)
  h.isPresent && h.get.isAlive
}

// Proves that a paused runReload did NOT restart: the original (baseline) process is
// still alive, and because no new fork started, the deleted pid.txt was NOT recreated.
val assertNotRestarted = taskKey[Unit]("Assert the paused fork kept running and no new fork started")
assertNotRestarted := Def.uncached {
  val log      = streams.value.log
  val baseline = scala.io.Source.fromFile(baseDirectory.value / "target" / "baseline-pid.txt").mkString.trim.toLong
  val pidFile  = baseDirectory.value / "target" / "pid.txt"
  // Give a hypothetical (buggy) restart a chance to write pid.txt before asserting.
  Thread.sleep(3000)
  if (pidFile.exists())
    sys.error(s"pid.txt was recreated ($pidFile) — the paused runReload restarted the fork")
  if (!pidAlive(baseline))
    sys.error(s"baseline fork (pid=$baseline) died while paused — it should have kept running")
  log.info(s"assertNotRestarted: baseline pid=$baseline still alive, no new fork started")
}

val assertPidChanged = taskKey[Unit]("Assert pid.txt's pid differs from baseline-pid.txt")
assertPidChanged := Def.uncached {
  val log      = streams.value.log
  val cur      = scala.io.Source.fromFile(baseDirectory.value / "target" / "pid.txt").mkString.trim
  val baseline = scala.io.Source.fromFile(baseDirectory.value / "target" / "baseline-pid.txt").mkString.trim
  log.info(s"assertPidChanged: cur=$cur baseline=$baseline")
  if (cur == baseline)
    sys.error(s"Expected pid to change after resume+runReload, but it's still $cur")
}

val assertBaselineDead = taskKey[Unit]("Assert the original (baseline) fork was stopped on restart")
assertBaselineDead := Def.uncached {
  val log      = streams.value.log
  val baseline = scala.io.Source.fromFile(baseDirectory.value / "target" / "baseline-pid.txt").mkString.trim.toLong
  var attempts = 0
  while (pidAlive(baseline) && attempts < 20) { Thread.sleep(500); attempts += 1 }
  if (pidAlive(baseline))
    sys.error(s"baseline fork (pid=$baseline) is still alive after resume+restart")
  log.info(s"assertBaselineDead: pid=$baseline is no longer alive")
}

// The paused dimension of reloadStatus is plugin-internal, so the build can only
// independently observe the running/not-running dimension (via the shared
// BackgroundJobService, exactly as reloadStatus does). The paused reporting is exercised
// by invoking `reloadStatus` in the test script (its log line is visible thanks to
// scriptedBufferLog := false) and its effect is validated behaviorally by
// assertNotRestarted.
val assertRunning = taskKey[Unit]("Assert a runReload fork is currently running")
assertRunning := Def.uncached {
  val log = streams.value.log
  if (!bgJobService.value.jobs.exists(_.spawningTask.key.label == "runReload"))
    sys.error("expected a running runReload fork but none is alive")
  log.info("assertRunning: a runReload fork is alive")
}

val assertNotRunning = taskKey[Unit]("Assert no runReload fork is running")
assertNotRunning := Def.uncached {
  val log = streams.value.log
  if (bgJobService.value.jobs.exists(_.spawningTask.key.label == "runReload"))
    sys.error("expected no runReload fork but one is alive")
  log.info("assertNotRunning: no runReload fork is alive")
}
