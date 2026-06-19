scalaVersion := "3.8.4"
run / mainClass := Some("OutputApp")

import sbt.nio.Keys.fileInputs

val waitForStart = taskKey[Unit]("Wait for the app to start (app-started.txt)")
waitForStart := Def.uncached {
  val marker = baseDirectory.value / "target" / "app-started.txt"
  var attempts = 0
  while (!marker.exists() && attempts < 30) {
    Thread.sleep(1000)
    attempts += 1
  }
  if (!marker.exists()) sys.error("app-started.txt never appeared")
}

// Verify runReload teed the fork's stdout to the per-config capture file that
// `reloadOutput` reads. This proves the capture mechanism end to end.
val checkCapture = taskKey[Unit]("Assert the capture file contains the app's marker line")
checkCapture := Def.uncached {
  // The plugin writes the capture file under `target` (sbt 2.0's per-project
  // output dir), so resolve it the same way rather than assuming <base>/target.
  val capture = target.value / "reload" / "compile-output.log"
  val needle = "OUTPUT_MARKER_LINE_42"
  var attempts = 0
  def found: Boolean =
    capture.exists() && IO.read(capture).contains(needle)
  while (!found && attempts < 30) {
    Thread.sleep(1000)
    attempts += 1
  }
  if (!found)
    sys.error(s"capture file ${capture} did not contain '$needle'")
}

// `~reloadOutput` streams the fork output by re-running `reloadOutput` whenever the
// capture file changes. That works only if `reloadOutput / fileInputs` declares the
// capture file as a watched input. `~` itself blocks, so we assert the wiring
// declaratively rather than running it.
val checkWatchWiring = taskKey[Unit]("Assert ~reloadOutput watches the capture file")
checkWatchWiring := Def.uncached {
  val globs   = (Compile / reloadOutput / fileInputs).value
  val capture = (target.value / "reload" / "compile-output.log").toPath
  if (!globs.exists(_.matches(capture)))
    sys.error(s"reloadOutput / fileInputs ($globs) does not watch the capture file $capture")
}
