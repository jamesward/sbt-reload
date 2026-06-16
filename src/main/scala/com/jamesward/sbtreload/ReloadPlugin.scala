package com.jamesward.sbtreload

import sbt.*
import sbt.Keys.*
import sbt.nio.Keys.watchOnTermination
import sbt.plugins.JvmPlugin

object ReloadPlugin extends AutoPlugin:
  override def requires = JvmPlugin
  override def trigger = allRequirements

  object autoImport:
    val runReload = taskKey[Unit]("Stop any prior runReload process, recompile, then start the app in a forked JVM.")
    val runReloadArgs = settingKey[Seq[String]]("App arguments passed to the main method on each runReload.")

  import autoImport.*

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    runReloadArgs := Nil,
    onUnload := { s =>
      // On unload, stop every runReload job across all projects.
      stopAllReloadJobs(Project.extract(s).get(bgJobService))
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

    // Stop only this scope's prior runReload job.
    stopReloadJobsFor(service, rs, log)

    val mainClass = (run / Keys.mainClass).value.getOrElse(
      sys.error("runReload: no main class detected. Set run/mainClass.")
    )
    val forkOpts = (run / forkOptions).value.withConnectInput(false)
    val appArgs = runReloadArgs.value
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
    ()
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
