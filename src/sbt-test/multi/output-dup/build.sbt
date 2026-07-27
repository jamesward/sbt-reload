ThisBuild / scalaVersion := "3.8.4"

val waitForStart = taskKey[Unit]("Wait until target/started.txt is present")

// Read reloadOutput's OWN per-task streams `out` file (sbt persists each task's
// logged output there, and nothing else lands in it), so we can assert on exactly
// what `reloadOutput` printed without capturing the console.
val assertOnlyOwnOutput = taskKey[Unit]("Assert a project-scoped reloadOutput shows only that project's fork")

val markerSettings = Seq(
  run / forkOptions := ForkOptions().withWorkingDirectory(baseDirectory.value),
  waitForStart := Def.uncached {
    val marker = baseDirectory.value / "target" / "started.txt"
    var attempts = 0
    while (!marker.exists() && attempts < 60) { Thread.sleep(500); attempts += 1 }
    if (!marker.exists()) sys.error(s"$marker never appeared")
  },
)

// reloadOutput logs the fork output it reports via its task `streams`, which sbt 2.x
// persists to <buildBase>/target/out/**/<project>/streams/compile/reloadOutput/_global/streams/out.
def reloadOutputStreamsFor(buildBase: File, projectId: String): Seq[File] =
  (buildBase / "target" ** "out").get()
    .map(f => (f.getAbsolutePath.replace('\\', '/'), f))
    .filter { case (p, _) => p.contains(s"/$projectId/streams/") && p.contains("/reloadOutput/") }
    .map(_._2)
    .filter(_.isFile)

lazy val a = (project in file("a"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppA"))
  .settings(
    // Runs in project a's scope, right after `a/reloadOutput`.
    assertOnlyOwnOutput := Def.uncached {
      val log       = streams.value.log
      val buildBase = (LocalRootProject / baseDirectory).value
      val files     = reloadOutputStreamsFor(buildBase, "a")
      val text      = files.map(f => IO.read(f)).mkString("\n")
      log.info(s"assertOnlyOwnOutput: scanned ${files.size} reloadOutput streams file(s)")
      files.foreach(f => log.info(s"  streams: ${f.getAbsolutePath}"))

      def count(needle: String): Int = needle.r.findAllIn(text).size

      val shared  = count("SHARED_BANNER")
      val foreign = Seq("UNIQUE_B", "UNIQUE_C", "UNIQUE_D").filter(u => count(u) > 0)

      log.info(s"assertOnlyOwnOutput: SHARED_BANNER appears $shared time(s) in a/reloadOutput output")
      log.info(s"assertOnlyOwnOutput: foreign project markers present: ${foreign.mkString(", ")}")

      if (files.isEmpty)
        sys.error("could not find reloadOutput's streams capture file; test harness problem, not the bug under test")

      // Desired behavior: `a/reloadOutput` reports ONLY project a's running fork, so the
      // banner that every fork prints appears exactly once and no other project's unique
      // marker leaks in. Currently `reloadOutput` reports EVERY running fork in the config
      // regardless of the invoking project, so SHARED_BANNER shows up once per subproject
      // (4x) — the reported duplication bug.
      if (shared != 1 || foreign.nonEmpty)
        sys.error(
          s"reloadOutput duplication: SHARED_BANNER x$shared (expected 1); " +
          s"foreign markers leaked: ${foreign.mkString(",")}"
        )
    }
  )

lazy val b = (project in file("b"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppB"))

lazy val c = (project in file("c"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppC"))

lazy val d = (project in file("d"))
  .settings(markerSettings*)
  .settings(run / mainClass := Some("AppD"))

lazy val root = (project in file("."))
  .aggregate(a, b, c, d)
