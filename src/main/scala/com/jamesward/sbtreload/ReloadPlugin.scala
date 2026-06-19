package com.jamesward.sbtreload

import sbt.*
import sbt.Keys.*
import sbt.nio.Keys.{ fileInputs, watchOnTermination }
import sbt.nio.file.Glob
import sbt.plugins.JvmPlugin
import sbt.util.{ Level, Logger }

import java.io.{ BufferedWriter, FileOutputStream, OutputStreamWriter, Writer }
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

object ReloadPlugin extends AutoPlugin:
  override def requires = JvmPlugin
  override def trigger = allRequirements

  object autoImport:
    val runReload = taskKey[Unit]("Stop any prior runReload process, recompile, then start the app in a forked JVM.")
    val runReloadArgs = settingKey[Seq[String]]("App arguments passed to the main method on each runReload.")
    val reloadOutput = taskKey[Unit]("View-only: print runReload fork output captured since the last call (non-blocking).")

  import autoImport.*

  // Per-scope fingerprint of the inputs we last started a fork with.
  // Used to skip restarts when nothing the fork actually depends on has
  // changed — which is what makes aggregated `~runReload` only restart
  // the project(s) whose sources actually changed.
  private val lastInputs = ConcurrentHashMap[ScopedKey[?], Vector[String]]()

  // Per-capture-file read state for `reloadOutput`, so repeated calls only emit
  // new output. Keyed by the capture file's absolute path.
  private val outputStates = ConcurrentHashMap[String, OutputReader.ReadState]()

  // Per-capture-file restart "epoch". `runReload` bumps this each time it (re)starts
  // a fork (which truncates the capture file). `reloadOutput` compares it to the epoch
  // in its stored ReadState to detect a restart and reset its read offset to 0 — even
  // when the new fork's output is already longer than the previous offset.
  private val outputEpochs = ConcurrentHashMap[String, Long]()

  // Registry of the capture file each running runReload fork writes to, keyed by the
  // fork's `spawningTask` ScopedKey. `reloadOutput` reads from here rather than
  // recomputing the path from its own scope, so (a) it finds output regardless of
  // which scope it is invoked from — e.g. bare `reloadOutput` at the aggregate root
  // shows a subproject's running fork — and (b) there is no chance of a writer/reader
  // path-derivation mismatch.
  private val captureFiles = ConcurrentHashMap[ScopedKey[?], java.io.File]()

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    runReloadArgs := Nil,
    onUnload := { s =>
      // On unload, stop every runReload job across all projects.
      stopAllReloadJobs(Project.extract(s).get(bgJobService))
      lastInputs.clear()
      outputStates.clear()
      outputEpochs.clear()
      captureFiles.clear()
      onUnload.value(s)
    },
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(reloadSettings) ++
    inConfig(Test)(reloadSettings)

  private lazy val reloadSettings: Seq[Setting[?]] = Seq(
    runReload := Def.uncached(runReloadTask.value),
    reloadOutput := Def.uncached(reloadOutputTask.value),
    // `reloadOutput` reports on every running runReload fork (see reloadOutputTask),
    // so it must NOT aggregate — otherwise invoking it at an aggregate root would run
    // once per subproject and print each fork's output N times.
    reloadOutput / aggregate := false,
    // `~reloadOutput` tails the capture files: declaring them as file inputs makes
    // sbt's continuous build re-run `reloadOutput` whenever any fork appends output,
    // turning `~reloadOutput` into a stream. The glob is build-wide (every project's
    // `target/.../reload/<config>-output.log`) so streaming works even when invoked
    // from the aggregate root. Bare `reloadOutput` ignores this and stays a one-shot poll.
    reloadOutput / fileInputs := {
      val outDir = (LocalRootProject / baseDirectory).value / "target"
      Seq(Glob(outDir, s"**/reload/${configuration.value.name}-output.log"))
    },
    runReload / watchOnTermination := {
      // NOTE: `Keys.resolvedScoped` ignores any scope prefix and always resolves to
      // the *enclosing* setting's key — so `(runReload / Keys.resolvedScoped).value`
      // here yields the `watchOnTermination` key, NOT `runReload`. Matching the
      // background job's `spawningTask` (which is `<config>/runReload`) against that
      // never succeeds, so the fork would survive a `~runReload` cancel. Instead,
      // capture this setting's project+config and match jobs by project, config, and
      // the `runReload` label — which still distinguishes projects/configs (so we
      // don't kill another subproject's fork) without depending on the task axis.
      val termScope = Keys.resolvedScoped.value.scope
      val outKey = reloadOutputFile(target.value, configuration.value.name).getAbsolutePath
      (action, cmd, count, state) =>
        val service = Project.extract(state).get(bgJobService)
        stopReloadJobsForScope(service, termScope)
        clearReloadStateForScope(termScope)
        outputStates.remove(outKey)
        outputEpochs.remove(outKey)
        state
    },
  )

  private def runReloadTask: Def.Initialize[Task[Unit]] = Def.task {
    val service = bgJobService.value
    val log = streams.value.log
    val converter = fileConverter.value
    val st = state.value
    val rs = Keys.resolvedScoped.value

    // Compile first (via classpath dependencies). If compile fails,
    // this task aborts and the running fork keeps going.
    val products = exportedProductJars.value
    val classpath = fullClasspathAsJars.value
    val mainClassOpt = (run / Keys.mainClass).value
    val appArgs = runReloadArgs.value

    // Where this scope's fork output is teed for `reloadOutput` to read.
    val outFile = reloadOutputFile(target.value, configuration.value.name)

    // Fingerprint everything that, if changed, should justify a restart.
    // contentHashStr is content-based, so it changes whenever any classpath
    // entry's bytes change (a fresh compile of this project's source, or
    // any internal/external dep). path-based id is intentionally not used
    // — output paths can be stable across rebuilds.
    val fingerprint: Vector[String] =
      classpath.map(_.data.contentHashStr).toVector ++
        mainClassOpt.toVector.map("main:" + _) ++
        appArgs.toVector.map("arg:" + _)

    val isRunning = service.jobs.exists(_.spawningTask == rs)
    val unchanged = Option(lastInputs.get(rs)).contains(fingerprint)

    if isRunning && unchanged then
      log.debug(s"runReload: inputs unchanged for ${Def.showFullKey.show(rs)}; keeping running fork")
    else
      stopReloadJobsFor(service, rs, log)

      val mainClass = mainClassOpt.getOrElse(
        sys.error("runReload: no main class detected. Set run/mainClass.")
      )
      val forkOpts = (run / forkOptions).value.withConnectInput(false)
      val copyCp = (bgRun / bgCopyClasspath).value

      log.info(s"runReload: starting $mainClass")

      // Record where this fork's output is captured so reloadOutput (in any scope)
      // can find it via the running-jobs registry.
      captureFiles.put(rs, outFile)

      service.runInBackground(rs, st) {
        (logger, workingDir) =>
          val cp =
            if copyCp then service.copyClasspath(products, classpath, workingDir, converter)
            else classpath
          given xsbti.FileConverter = converter
          // Tee the fork's logged output (stdout at info, stderr at error) to a
          // per-config capture file so another client can read it via reloadOutput.
          // Truncate on each (re)start so the file reflects the current fork.
          Option(outFile.getParentFile).foreach(_.mkdirs())
          val sink =
            new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(outFile, false), StandardCharsets.UTF_8)
            )
          // The line above just truncated the capture file. Bump the epoch now, before
          // any output is written, so a concurrent `reloadOutput` poll detects the
          // restart and reads the new fork's output from the beginning.
          outputEpochs.merge(outFile.getAbsolutePath, 1L, (a, b) => a + b)
          val teeLogger = new TeeLogger(logger, sink)
          val r = new ForkRun(forkOpts)
          try r.run(mainClass, cp.files, appArgs, teeLogger).get
          finally
            try sink.close()
            catch case NonFatal(_) => ()
      }
      lastInputs.put(rs, fingerprint)
    end if
  }

  /**
   * View-only task: print, for every currently-running runReload fork in this config,
   * the output it has emitted since the last call (non-blocking).
   *
   * Reading is driven by the live `BackgroundJobService` jobs (not this task's own
   * scope), so `reloadOutput` shows the app's output no matter which scope you invoke
   * it from — in particular bare `reloadOutput` at the aggregate root surfaces a
   * subproject's running fork. Output from multiple forks is prefixed with the project
   * id. The config axis is still honored: `reloadOutput` shows Compile forks,
   * `Test/reloadOutput` shows Test forks.
   */
  private def reloadOutputTask: Def.Initialize[Task[Unit]] = Def.task {
    val service = bgJobService.value
    val log = streams.value.log
    val myConfig = configuration.value.name

    val targets: Vector[(ScopedKey[?], java.io.File)] =
      service.jobs
        .filter(_.spawningTask.key.label == runReload.key.label)
        .filter(_.spawningTask.scope.config.toOption.exists(_.name == myConfig))
        .flatMap(h => Option(captureFiles.get(h.spawningTask)).map(h.spawningTask -> _))
        .toVector
        .distinct

    if targets.isEmpty then log.info(s"reloadOutput: no running $myConfig runReload fork")
    else
      val multi = targets.size > 1
      // Poll every running fork, but only ever PRINT actual new output lines (prefixed
      // by project when more than one fork is running). The per-fork "no new output"
      // status is intentionally NOT printed here: with N running forks, emitting it once
      // per fork on every call floods `~reloadOutput` (which re-runs on each capture-file
      // change) with N identical "no new output" lines per trigger. Instead we print a
      // single invocation-level status only when NO fork produced anything new — so a
      // manual one-shot poll still gets confirmation, and a streaming tail stays quiet.
      var printedAny = false
      targets.foreach { (sk, outFile) =>
        val key = outFile.getAbsolutePath
        val epoch = Option(outputEpochs.get(key)).map(_.longValue).getOrElse(0L)
        val prev = Option(outputStates.get(key)).getOrElse(OutputReader.ReadState.initial)
        val result = OutputReader.poll(outFile, prev, epoch)
        val prefix = if multi then s"[${projectId(sk)}] " else ""
        result.lines.foreach { l =>
          printedAny = true
          log.info(prefix + l)
        }
        outputStates.put(key, result.state)
      }
      if !printedAny then log.info("reloadOutput: no new output")
  }

  /** Best-effort human-readable project id for a runReload `spawningTask` ScopedKey. */
  private def projectId(sk: ScopedKey[?]): String =
    sk.scope.project.toOption match
      case Some(ref: ProjectRef) => ref.project
      case Some(other)           => other.toString
      case None                  => "?"

  /** Deterministic per-(project, config) capture file shared by writer and reader. */
  private def reloadOutputFile(targetDir: java.io.File, configName: String): java.io.File =
    new java.io.File(new java.io.File(targetDir, "reload"), s"$configName-output.log")

  /** A Logger that forwards every log call to `underlying` and also appends the message to `sink`. */
  private final class TeeLogger(underlying: Logger, sink: Writer) extends Logger:
    override def trace(t: => Throwable): Unit = underlying.trace(t)
    override def success(message: => String): Unit = underlying.success(message)
    override def log(level: Level.Value, message: => String): Unit =
      val m = message
      underlying.log(level, m)
      try
        sink.synchronized {
          sink.write(m)
          sink.write("\n")
          sink.flush()
        }
      catch case NonFatal(_) => ()

  /** Stop only jobs whose `spawningTask` equals the given ScopedKey. */
  private def stopReloadJobsFor(
      service: BackgroundJobService,
      key: ScopedKey[?],
      log: Logger,
  ): Unit =
    service.jobs.filter(_.spawningTask == key).foreach { h =>
      log.info(s"runReload: stopping job ${h.id}")
      service.stop(h)
      service.waitForTry(h)
      ()
    }

  /**
   * Stop runReload jobs spawned by the given scope's runReload, matching on project,
   * config, and the `runReload` label rather than full `ScopedKey` equality.
   *
   * Used by `watchOnTermination`, where the only handle on the scope is the termination
   * setting's own resolved scope (`Keys.resolvedScoped` ignores scope prefixes), so an
   * exact key comparison against the job's `spawningTask` (`<config>/runReload`) never
   * matches. Comparing project+config+label still isolates this project/config's fork
   * without killing other subprojects' jobs.
   */
  private def stopReloadJobsForScope(service: BackgroundJobService, scope: Scope): Unit =
    val label = runReload.key.label
    service.jobs
      .filter { h =>
        val s = h.spawningTask.scope
        h.spawningTask.key.label == label && s.project == scope.project && s.config == scope.config
      }
      .foreach { h =>
        service.stop(h)
        service.waitForTry(h)
        ()
      }

  /** Drop the restart fingerprint for the runReload of the given scope (project+config). */
  private def clearReloadStateForScope(scope: Scope): Unit =
    val label = runReload.key.label
    lastInputs.keySet.removeIf { k =>
      k.key.label == label && k.scope.project == scope.project && k.scope.config == scope.config
    }
    ()

  /** Stop every runReload job across the build (used on unload). */
  private def stopAllReloadJobs(service: BackgroundJobService): Unit =
    val label = runReload.key.label
    service.jobs.filter(_.spawningTask.key.label == label).foreach { h =>
      service.stop(h)
      service.waitForTry(h)
      ()
    }
end ReloadPlugin
