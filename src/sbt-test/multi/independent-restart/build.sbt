ThisBuild / scalaVersion := "3.8.4"

val waitForStarted     = taskKey[Unit]("Wait until pid.txt is present")
val recordPid          = taskKey[Unit]("Snapshot current pid.txt -> baseline-pid.txt")
val assertPidChanged   = taskKey[Unit]("Assert pid.txt's pid differs from baseline-pid.txt")
val assertPidUnchanged = taskKey[Unit]("Assert pid.txt's pid matches baseline-pid.txt")
val touchSource        = taskKey[Unit]("Mutate the project's source so the next compile produces new bytecode")

// These tasks have side effects and read mutable filesystem state — they
// MUST run every time invoked. Wrap each in Def.uncached so sbt 2.x's
// action cache doesn't memoize them across calls.
val markerSettings = Seq(
  waitForStarted := Def.uncached {
    val log    = streams.value.log
    val marker = baseDirectory.value / "target" / "pid.txt"
    var attempts = 0
    while (!marker.exists() && attempts < 60) { Thread.sleep(500); attempts += 1 }
    if (!marker.exists()) sys.error(s"$marker never appeared")
    attempts = 0
    while (java.nio.file.Files.size(marker.toPath) == 0 && attempts < 60) {
      Thread.sleep(500); attempts += 1
    }
    log.info(s"waitForStarted: $marker -> ${scala.io.Source.fromFile(marker).mkString.trim}")
  },
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
  },
  assertPidChanged := Def.uncached {
    val log      = streams.value.log
    val cur      = scala.io.Source.fromFile(baseDirectory.value / "target" / "pid.txt").mkString.trim
    val baseline = scala.io.Source.fromFile(baseDirectory.value / "target" / "baseline-pid.txt").mkString.trim
    log.info(s"assertPidChanged: cur=$cur baseline=$baseline")
    if (cur == baseline)
      sys.error(s"Expected pid to change after recompile, but it's still $cur")
  },
  assertPidUnchanged := Def.uncached {
    val log      = streams.value.log
    val cur      = scala.io.Source.fromFile(baseDirectory.value / "target" / "pid.txt").mkString.trim
    val baseline = scala.io.Source.fromFile(baseDirectory.value / "target" / "baseline-pid.txt").mkString.trim
    log.info(s"assertPidUnchanged: cur=$cur baseline=$baseline")
    if (cur != baseline)
      sys.error(s"Expected pid to remain $baseline (no source change) but it's $cur")
  },
  touchSource := Def.uncached {
    val log    = streams.value.log
    val srcDir = baseDirectory.value / "src" / "main" / "scala"
    val files  = Option(srcDir.listFiles).toSeq.flatten.filter(_.getName.endsWith(".scala"))
    if (files.isEmpty) sys.error(s"no .scala files in $srcDir")
    val src       = files.head
    val original  = scala.io.Source.fromFile(src).mkString
    val timestamp = System.currentTimeMillis()
    val replacement = s"""println("touched-$timestamp")
    // MARKER"""
    val updated = original.replaceFirst("// MARKER", java.util.regex.Matcher.quoteReplacement(replacement))
    if (updated == original) sys.error(s"`// MARKER` not found in $src")
    java.nio.file.Files.writeString(src.toPath, updated)
    log.info(s"touchSource: rewrote $src")
  },
)

lazy val a = (project in file("a"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppA"))

lazy val b = (project in file("b"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppB"))

lazy val root = (project in file("."))
  .aggregate(a, b)
