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
    val reloadRestart = taskKey[Unit]("Force runReload to recompile and restart this scope's app, even when its fingerprint is unchanged.")
    val runReloadArgs = settingKey[Seq[String]]("App arguments passed to the main method on each runReload.")
    val reloadOutput = taskKey[Unit]("View-only: print runReload fork output captured since the last call (non-blocking).")
    val reloadPause = taskKey[Unit]("Pause runReload for this scope: while paused, runReload keeps the current fork and does not restart it.")
    val reloadResume = taskKey[Unit]("Resume runReload after a reloadPause and immediately reconcile changed inputs for an existing fork.")
    val reloadStatus = taskKey[Unit]("Report whether this scope's runReload fork is running and whether it is paused.")

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

  // Scopes (project + config) that have been paused via `reloadPause`. While a scope is
  // present here, `runReload` invocations for that scope keep the current fork and do NOT
  // stop/restart it — even if the inputs changed. `reloadResume` removes the scope.
  //
  // This lives in the plugin object (a single instance per sbt server JVM) so it is shared
  // across all connected clients: one client can `~runReload` while another issues
  // `reloadPause` / `reloadResume` against the same running server. It is keyed by a
  // project+config string (see `scopeId`) rather than a full `ScopedKey`, because
  // `Keys.resolvedScoped` inside `reloadPause`/`reloadResume` resolves to *their* own key,
  // not `runReload` (same gotcha as `watchOnTermination`); matching on project+config
  // still isolates the right project/config's fork.
  private val pausedScopes = ConcurrentHashMap.newKeySet[String]()

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    runReloadArgs := Nil,
    onUnload := { s =>
      // On unload, stop every runReload job across all projects.
      stopAllReloadJobs(Project.extract(s).get(bgJobService))
      lastInputs.clear()
      outputStates.clear()
      outputEpochs.clear()
      captureFiles.clear()
      pausedScopes.clear()
      onUnload.value(s)
    },
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(reloadSettings) ++
    inConfig(Test)(reloadSettings)

  private lazy val reloadSettings: Seq[Setting[?]] = Seq(
    runReload := Def.uncached(runReloadTask(forceRestart = false).value),
    reloadRestart := Def.uncached(runReloadTask(forceRestart = true).value),
    reloadOutput := Def.uncached(reloadOutputTask.value),
    reloadPause := Def.uncached(reloadPauseTask.value),
    reloadResume := Def.uncached(reloadResumeTask.value),
    reloadStatus := Def.uncached(reloadStatusTask.value),
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
        pausedScopes.remove(scopeId(termScope))
        state
    },
  )

  private def runReloadTask(forceRestart: Boolean): Def.Initialize[Task[Unit]] = Def.task {
    val service = bgJobService.value
    val log = streams.value.log
    val converter = fileConverter.value
    val st = state.value
    val enclosing = Keys.resolvedScoped.value
    // reloadRestart must manage the same background job and state as runReload.
    // Its own resolvedScoped has a different task axis, so canonicalize that axis
    // before matching/stopping/spawning jobs or reading/writing fingerprints.
    val rs = Def.ScopedKey(enclosing.scope, runReload.key)

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

    if !forceRestart && pausedScopes.contains(scopeId(rs.scope)) then
      // Paused via `reloadPause` (possibly from another client on the same sbt server).
      // Keep the current fork as-is: do not stop, do not restart, and do NOT update the
      // fingerprint — so reloadResume's immediate reconciliation sees the changed
      // inputs and restarts. An explicit reloadRestart bypasses this branch once without
      // clearing the paused state.
      log.info(s"runReload: paused for ${Def.showFullKey.show(rs)}; keeping current fork, not restarting")
    else if !forceRestart && isRunning && unchanged then
      log.debug(s"runReload: inputs unchanged for ${Def.showFullKey.show(rs)}; keeping running fork")
    else
      if forceRestart then log.info(s"runReload: forced restart for ${Def.showFullKey.show(rs)}")
      stopReloadJobsFor(service, rs, log)

      val mainClass = mainClassOpt.getOrElse(
        sys.error("runReload: no main class detected. Set run/mainClass.")
      )
      // sbt changed run/forkOptions' workingDirectory across 2.0.x: 2.0.4 set it to
      // None; 2.0.6 sets it to Some(ThisBuild / baseDirectory) (the build root) because
      // `run / baseDirectory := (ThisBuild / baseDirectory).value`. Either way we must
      // NOT inherit it — we pin the fork to THIS scope's (subproject's) baseDirectory so
      // relative paths like "target/pid.txt" in the forked app resolve inside the
      // subproject's directory, not the build root. (baseDirectory here is the
      // Compile/Test-scoped value, unaffected by the run-scoped override.)
      val userForkOpts = (run / forkOptions).value
      val forkOpts = userForkOpts
        .withConnectInput(false)
        .withWorkingDirectory(baseDirectory.value)
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
   * Pause `runReload` for this task's scope (project + config). While paused, any
   * `runReload` invocation for the same project/config is a no-op that keeps the current
   * fork running (see `runReloadTask`). Because the paused-scope registry lives in the
   * plugin object shared by the whole sbt server, this can be issued from a *different*
   * client than the one running `~runReload`.
   */
  private def reloadPauseTask: Def.Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val scope = Keys.resolvedScoped.value.scope
    pausedScopes.add(scopeId(scope))
    log.info(s"runReload: paused ${configuration.value.name} for ${scopeDisplay(scope)}; changes will not restart until reloadResume")
  }

  /**
   * Resume `runReload` for this task's scope after a `reloadPause`. If this scope
   * was paused and already has a running fork, immediately re-evaluate normal
   * fingerprint-aware `runReload`: changed inputs restart now, unchanged inputs
   * keep the fork. A paused scope with no fork is merely resumed and is not started.
   */
  private def reloadResumeTask: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val service = bgJobService.value
    val log = streams.value.log
    val scope = Keys.resolvedScoped.value.scope
    val wasPaused = pausedScopes.remove(scopeId(scope))
    val wasRunning = service.jobs.exists { h =>
      val s = h.spawningTask.scope
      h.spawningTask.key.label == runReload.key.label &&
        s.project == scope.project && s.config == scope.config
    }

    if wasPaused then
      log.info(s"runReload: resumed ${configuration.value.name} for ${scopeDisplay(scope)}")
    else
      log.info(s"runReload: ${configuration.value.name} was not paused for ${scopeDisplay(scope)}")

    if wasPaused && wasRunning then
      log.info(s"runReload: reconciling inputs after resume for ${scopeDisplay(scope)}")
      runReloadTask(forceRestart = false)
    else Def.task(())
  }

  /**
   * Report the runReload state for this task's scope (project + config): whether a fork is
   * currently running and, if so, whether the scope is paused. Like pause/resume this is
   * driven by the shared plugin-object state and the live `BackgroundJobService`, so it
   * reflects a fork started by any client on the same server.
   *
   * Jobs are matched by project + config + the `runReload` label (not full `ScopedKey`
   * equality) for the same reason as `watchOnTermination`/`reloadPause`: this task's
   * `Keys.resolvedScoped` is the `reloadStatus` key, whose task axis differs from the
   * running job's `<config>/runReload` `spawningTask`.
   */
  private def reloadStatusTask: Def.Initialize[Task[Unit]] = Def.task {
    val service = bgJobService.value
    val log = streams.value.log
    val scope = Keys.resolvedScoped.value.scope
    val configName = configuration.value.name
    val label = runReload.key.label
    val running = service.jobs.exists { h =>
      val s = h.spawningTask.scope
      h.spawningTask.key.label == label && s.project == scope.project && s.config == scope.config
    }
    val paused = pausedScopes.contains(scopeId(scope))
    val status =
      if !running then "not running"
      else if paused then "running (paused)"
      else "running"
    log.info(s"reloadStatus [$configName/${scopeDisplay(scope)}]: $status")
  }

  /**
   * View-only task: print the output a running runReload fork has emitted since the last
   * call (non-blocking).
   *
   * Reading is driven by the live `BackgroundJobService` jobs (not this task's own task
   * axis). Fork selection is **project-aware**:
   *
   *   - If the project the task is invoked in has its own running fork (e.g.
   *     `a/reloadOutput`, or bare `reloadOutput` in a single-project build), only that
   *     project's fork is reported. This is what stops a subproject-scoped call from
   *     dumping every other subproject's output — the multi-project duplication bug where
   *     a line common to N forks (a shared startup banner) showed up N times.
   *   - Only when the invoking scope has NO fork of its own (typically an aggregate root
   *     that just aggregates subprojects) do we fall back to reporting every running fork
   *     in the config, so bare `reloadOutput` at the root still surfaces a subproject's
   *     running fork.
   *
   * When more than one fork is reported (the aggregate-root fallback) each line is
   * prefixed with the project id. The config axis is always honored: `reloadOutput` shows
   * Compile forks, `Test/reloadOutput` shows Test forks.
   */
  private def reloadOutputTask: Def.Initialize[Task[Unit]] = Def.task {
    val service = bgJobService.value
    val log = streams.value.log
    val myConfig = configuration.value.name
    // `Keys.resolvedScoped` resolves to the `reloadOutput` key itself, but its scope
    // carries the correct project + config axes of the invocation (same property used by
    // `watchOnTermination`/`reloadStatus`). We use its project axis to prefer this
    // project's own fork.
    val myProject = Keys.resolvedScoped.value.scope.project

    val allForks: Vector[(ScopedKey[?], java.io.File)] =
      service.jobs
        .filter(_.spawningTask.key.label == runReload.key.label)
        .filter(_.spawningTask.scope.config.toOption.exists(_.name == myConfig))
        .flatMap(h => Option(captureFiles.get(h.spawningTask)).map(h.spawningTask -> _))
        .toVector
        .distinct

    // Project-aware scoping: prefer this project's own running fork; only fall back to
    // every fork when this scope has none of its own (aggregate root).
    val ownForks = allForks.filter(_._1.scope.project == myProject)
    val targets = if ownForks.nonEmpty then ownForks else allForks

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

  /**
   * Stable identity for a runReload scope based on project + config only.
   *
   * `runReload`'s job `spawningTask` scope and the `reloadPause`/`reloadResume`/
   * `watchOnTermination` settings' own scopes all share the same project and config axes
   * (they differ only on the task axis, which `Keys.resolvedScoped` fixes to the enclosing
   * key). Keying the paused-scope registry on project+config lets pause/resume issued
   * against `reloadPause` match the `runReload` job for the same project/config, without
   * depending on the task axis.
   */
  private def scopeId(scope: Scope): String =
    val p = scope.project.toOption.map(_.toString).getOrElse("*")
    val c = scope.config.toOption.map(_.name).getOrElse("*")
    s"$p/$c"

  /** Best-effort human-readable project name for log messages. */
  private def scopeDisplay(scope: Scope): String =
    scope.project.toOption match
      case Some(ref: ProjectRef) => ref.project
      case Some(other)           => other.toString
      case None                  => "*"

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
