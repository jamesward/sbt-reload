scalaVersion := "3.8.4"
run / mainClass := Some("WebApp")

val waitForFile = taskKey[Unit]("Wait for server-started.txt")
waitForFile := Def.uncached {
  val marker = baseDirectory.value / "target" / "server-started.txt"
  var attempts = 0
  while (!marker.exists() && attempts < 30) {
    Thread.sleep(1000)
    attempts += 1
  }
  if (!marker.exists()) sys.error("server-started.txt never appeared")
}

val assertCanonicalJobIdentity = taskKey[Unit]("Assert reloadRestart owns the canonical runReload background job")
assertCanonicalJobIdentity := Def.uncached {
  val jobs = bgJobService.value.jobs
  val runReloadJobs = jobs.filter(_.spawningTask.key.label == "runReload")
  val restartJobs = jobs.filter(_.spawningTask.key.label == "reloadRestart")
  if (runReloadJobs.size != 1)
    sys.error(s"expected exactly one canonical runReload job, found ${runReloadJobs.size}")
  if (restartJobs.nonEmpty)
    sys.error(s"reloadRestart incorrectly registered ${restartJobs.size} background job(s) under its own key")
}
