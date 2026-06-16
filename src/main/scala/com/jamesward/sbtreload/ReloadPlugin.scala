package com.jamesward.sbtreload

import sbt.*
import sbt.Keys.*
import sbt.nio.Keys.watchOnTermination
import sbt.plugins.JvmPlugin

import java.util.concurrent.ConcurrentHashMap

object ReloadPlugin extends AutoPlugin:
  override def requires = JvmPlugin
  override def trigger = allRequirements

  object autoImport:
    val runReload = taskKey[Unit]("Stop any prior runReload process, recompile, then start the app in a forked JVM.")
    val runReloadArgs = settingKey[Seq[String]]("App arguments passed to the main method on each runReload.")

  import autoImport.*

  // Per-scope fingerprint of the inputs we last started a fork with.
  // Used to skip restarts when nothing the fork actually depends on has
  // changed — which is what makes aggregated `~runReload` only restart
  // the project(s) whose sources actually changed.
  private val lastInputs = ConcurrentHashMap[ScopedKey[?], Vector[String]]()

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    runReloadArgs := Nil,
    onUnload := { s =>
      // On unload, stop every runReload job across all projects.
      stopAllReloadJobs(Project.extract(s).get(bgJobService))
      lastInputs.clear()
      onUnload.value(s)
    },
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(reloadSettings) ++
    inConfig(Test)(reloadSettings)

  private lazy val reloadSettings: Seq[Setting[?]] = Seq(
    runReload := Def.uncached(runReloadTask.value),
    runReload / watchOnTermination := {
      // Capture this scope's runReload ScopedKey at setting-init time so the
      // termination handler only stops the job spawned by THIS runReload.
      val rs = (runReload / Keys.resolvedScoped).value
      (action, cmd, count, state) =>
        stopReloadJobsFor(Project.extract(state).get(bgJobService), rs)
        lastInputs.remove(rs)
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

      service.runInBackground(rs, st) {
        (logger, workingDir) =>
          val cp =
            if copyCp then service.copyClasspath(products, classpath, workingDir, converter)
            else classpath
          given xsbti.FileConverter = converter
          val r = new ForkRun(forkOpts)
          r.run(mainClass, cp.files, appArgs, logger).get
      }
      lastInputs.put(rs, fingerprint)
    end if
  }

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

  private def stopReloadJobsFor(service: BackgroundJobService, key: ScopedKey[?]): Unit =
    service.jobs.filter(_.spawningTask == key).foreach { h =>
      service.stop(h)
      service.waitForTry(h)
      ()
    }

  /** Stop every runReload job across the build (used on unload). */
  private def stopAllReloadJobs(service: BackgroundJobService): Unit =
    val label = runReload.key.label
    service.jobs.filter(_.spawningTask.key.label == label).foreach { h =>
      service.stop(h)
      service.waitForTry(h)
      ()
    }
end ReloadPlugin
