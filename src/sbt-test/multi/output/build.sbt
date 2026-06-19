ThisBuild / scalaVersion := "3.8.4"

val waitForStart = taskKey[Unit]("Wait until target/started.txt is present")
val assertCaptured = taskKey[Unit]("Assert this scope's capture file exists and contains the marker")

val markerSettings = Seq(
  waitForStart := Def.uncached {
    val marker = baseDirectory.value / "target" / "started.txt"
    var attempts = 0
    while (!marker.exists() && attempts < 60) { Thread.sleep(500); attempts += 1 }
    if (!marker.exists()) sys.error(s"$marker never appeared")
  },
  assertCaptured := Def.uncached {
    val capture = (Compile / target).value / "reload" / "compile-output.log"
    var attempts = 0
    def ok = capture.exists() && IO.read(capture).contains("OUTPUT_MARKER_")
    while (!ok && attempts < 30) { Thread.sleep(500); attempts += 1 }
    if (!ok) sys.error(s"capture file $capture missing or has no marker")
  },
)

// Run from the ROOT: prove the subproject's running Compile runReload fork is visible
// in the shared BackgroundJobService. This is exactly the data the fixed `reloadOutput`
// uses to surface a subproject's output when invoked at the aggregate root — before the
// fix, `reloadOutput` only ever looked at its own scope's (empty) capture file.
val assertRunningForkVisible = taskKey[Unit]("Assert a Compile runReload fork is visible from root")
assertRunningForkVisible := Def.uncached {
  val log  = streams.value.log
  val jobs = bgJobService.value.jobs.filter(_.spawningTask.key.label == "runReload")
  jobs.foreach(j => log.info(s"assertRunningForkVisible: ${Def.showFullKey.show(j.spawningTask)}"))
  val compileForks =
    jobs.filter(_.spawningTask.scope.config.toOption.exists(_.name == "compile"))
  if (compileForks.isEmpty)
    sys.error("no running Compile runReload fork is visible from the root project")
}

lazy val a = (project in file("a"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppA"))

lazy val b = (project in file("b"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppB"))

lazy val root = (project in file("."))
  .settings(markerSettings*)
  .aggregate(a, b)
