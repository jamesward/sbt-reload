# sbt-zio-reload — Design

This document describes the design of `sbt-zio-reload`, an sbt 2.x plugin that
provides auto-restart-on-source-change for forked Scala / ZIO applications.
Modeled on [sbt-revolver](https://github.com/spray/sbt-revolver) but built on
top of sbt 2.x's first-class background-job and watch primitives instead of
re-implementing them.

It assumes the answers in `SPEC.md` (Open Questions section) are authoritative.

---

## 1. Goals

- A single user-facing command — `~Test/runReload` (or `~runReload`) — that:
  1. forks the configured main class into a background JVM,
  2. watches the project's sources,
  3. on every source change: stops the running fork, recompiles, starts a fresh
     fork.
- Zero project configuration in the common case: dropping the plugin in
  `project/plugins.sbt` is enough.
- Coexist with sbt 2.x's existing `run` / `bgRun` / `~` infrastructure rather
  than fighting it.
- Be ZIO-friendly today (works for any `ZIOAppDefault` or plain `main`) and leave
  room for ZIO-specific features later (testcontainers persistence, Play-style
  compile-error pages — see §10).

## 2. Non-goals (v1)

- Hot class reloading (JRebel-style). We always restart the JVM.
- Sharing JVM state across reloads (e.g. keeping testcontainers, Redis fakes,
  HTTP servers warm). Tracked as a future extension.
- A web UI for compile errors. Future extension.
- sbt 1.x support.
- Cross-build to multiple sbt versions. Pin to sbt 2.0.0.
- CLI argument passing to the running app (deferred — args come from settings
  only, per SPEC Q4).
- Per-process color tagging (per SPEC Q12 — sbt 2.x's default per-job logger is
  enough).

## 3. User experience

### 3.1 Install

`project/plugins.sbt`:

```scala
addSbtPlugin("com.jamesward" % "sbt-zio-reload" % "<version>")
```

No `enablePlugins(...)` needed — the plugin auto-triggers on every JVM project
(SPEC Q7).

### 3.2 Run

For a `Test`-scoped main (the primary use case):

```
$ ./sbt "~Test/runReload"
```

For a `Compile`-scoped main:

```
$ ./sbt "~runReload"
```

Behavior:

- The first invocation forks the JVM and starts the app in the background.
- Editing any source triggers a recompile. On a successful compile, the running
  fork is terminated and a fresh fork is started. On compile failure, the
  running fork keeps running (SPEC Q6).
- Pressing **Enter** exits sbt's watch loop. The bg job keeps running (sbt 2.x
  default) until sbt itself shuts down or the user runs `bgStop <id>`.
- Pressing **Ctrl-C** while in watch mode cancels the active task. Our task
  cancellation handler tears down any `runReload`-spawned background jobs.
- On sbt `reload` or `exit`, all `runReload`-spawned jobs are stopped via an
  `onUnload` hook. (sbt 2.x's `BackgroundJobService` already handles JVM-level
  shutdown of jobs — we add the user-visible early termination on top.)

### 3.3 Without `~`

Plain `./sbt Test/runReload` is supported but rare:

- Stops any prior `runReload`-spawned job (per the single-instance rule, §5.3).
- Forks and starts the app.
- Returns the `JobHandle` to the sbt prompt — the bg process keeps running.
  The user can stop it with `bgStop <id>` or exit sbt.

> **Open question to confirm.** SPEC Q3 said "runReload should block.
> cancelling (Ctrl-C) should terminate the bg process." With `~` (the
> recommended UX) the watch loop already provides that blocking-feel. But for
> plain `runReload` (no `~`), making the task itself block (e.g. via
> `bgWaitFor`) is incompatible with `~runReload` — `~` re-invokes the task on
> source changes, which requires the task to return promptly. We resolve this
> by: the task itself is non-blocking (returns `JobHandle`), and the
> "block + Ctrl-C kills" experience is delivered through `~` + cancellation
> hooks. Plain `runReload` therefore returns immediately. Please confirm this
> is acceptable; if not, we add a separate `runReloadBlocking` (or similar)
> task that does `runReload` + `bgWaitFor`.

## 4. Architecture

### 4.1 What sbt 2.x already gives us

We build on these existing primitives — all live in sbt 2.x core:

| Primitive | Source | Used for |
|---|---|---|
| `bgJobService: BackgroundJobService` | `sbt.Keys`, `DefaultBackgroundJobService` | Spawn / track / stop jobs |
| `runInBackgroundWithLoader` | `BackgroundJobService` | Fork the JVM in a managed bg thread |
| `BackgroundJobService.copyClasspath` | same | Copy classpath into a working dir so a deletion of `target/classes` doesn't blow up the running fork |
| `JobHandle` (`spawningTask: ScopedKey[?]`) | same | Identify which task spawned each job |
| `Watch.scala` / `~` command | `sbt.nio.Watch`, `sbt.internal.Continuous` | Source-change detection + re-invocation |
| `run / forkOptions`, `run / mainClass`, `run / runner` | `Defaults`, `RunUtil` | Reuse the user's fork config (SPEC Q5) |
| `(Compile|Test) / fullClasspathAsJars`, `exportedProductJars` | `Defaults` | Build the classpath the fork sees |
| `JvmPlugin` | `sbt.plugins.JvmPlugin` | Plugin requirement |

### 4.2 What this plugin adds

A single `AutoPlugin`:

```
package com.jamesward.sbtzioreload

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin

object ZioReloadPlugin extends AutoPlugin:
  override def requires = JvmPlugin
  override def trigger  = allRequirements

  object autoImport:
    val runReload              = taskKey[JobHandle]("Restart the app in a forked JVM, killing any prior runReload job.")
    val runReloadArgs          = settingKey[Seq[String]]("App arguments passed to the main method on each runReload.")
    val runReloadSingleInstance =
      settingKey[Boolean]("If true (default), runReload stops every other runReload job across the build before starting. " +
                          "If false, only the prior runReload job in the same (project, config) is stopped.")

  import autoImport.*

  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    runReloadArgs           := Nil,
    runReloadSingleInstance := true,
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(reloadSettings) ++
    inConfig(Test)   (reloadSettings)

  private lazy val reloadSettings: Seq[Setting[?]] = Seq(
    runReload := runReloadTaskImpl.value,
  )
end ZioReloadPlugin
```

Everything else is implementation detail in `runReloadTaskImpl` (§5).

### 4.3 Why a `TaskKey`, not an `InputKey`

SPEC Q4 said args come from settings only, not the CLI. That means
`runReload` takes no parsed input, so `TaskKey[JobHandle]` is the right type.
It also means `runReload` aggregates by default (matching the "aggregate by
default" answer to SPEC Q11 — `disableAggregation` only applies to a curated
list and we simply don't add ourselves to it).

## 5. Implementation

### 5.1 Per-config layering

We define settings inside `inConfig(Compile)` and `inConfig(Test)`. Each
configuration's `runReload` task delegates to the same `runReloadTaskImpl`,
which reads `configuration.value` to know which classpath/products to use.
The user invokes it as `Compile/runReload` or `Test/runReload`.

### 5.2 The task body (sketch)

```scala
private def runReloadTaskImpl: Def.Initialize[Task[JobHandle]] =
  Def.taskDyn {
    // 1. Force compilation first. If this fails, the task aborts BEFORE we
    //    touch the running job — satisfying SPEC Q6 (compile failure keeps the
    //    old fork running).
    val products  = exportedProductJars.value
    val classpath = fullClasspathAsJars.value

    Def.task {
      val service        = bgJobService.value
      val log            = streams.value.log
      val converter      = fileConverter.value
      val mainClassOpt   = (run / mainClass).value
      val forkOpts       = (run / forkOptions).value.withConnectInput(false)
      val scalaRun       = (run / runner).value
      val appArgs        = runReloadArgs.value
      val singleInstance = runReloadSingleInstance.value
      val thisProject    = thisProjectRef.value
      val thisConfig     = configuration.value
      val hashClasspath  = (bgRun / bgHashClasspath).value
      val copyCp         = (bgRun / bgCopyClasspath).value
      val state          = Keys.state.value

      // 2. Stop prior runReload-spawned jobs.
      stopPriorJobs(service, log, thisProject, thisConfig, singleInstance)

      val mainClass = mainClassOpt.getOrElse(
        sys.error("runReload: no main class detected. Set Compile/run/mainClass or " +
                  "ensure exactly one main class is discovered.")
      )

      // 3. Spawn the new fork. Reuses RunUtil.bgRun-style logic verbatim:
      //    copy the classpath into a sandboxed working dir, then run the main
      //    inside a BackgroundJob.
      service.runInBackgroundWithLoader(Keys.resolvedScoped.value, state) {
        (logger, workingDir) =>
          val cp =
            if copyCp then service.copyClasspath(products, classpath, workingDir, hashClasspath, converter)
            else classpath
          given xsbti.FileConverter = converter
          scalaRun match
            case r: Run =>
              val loader = r.newLoader(cp.files)
              (Some(loader), () => r.runWithLoader(loader, cp.files, mainClass, appArgs, logger).get)
            case sr =>
              (None, () => sr.run(mainClass, cp.files, appArgs, logger).get)
      }
    }
  }
```

The stop+start logic is intentionally near-identical to
`sbt.internal.RunUtil.bgRunTask` so we behave consistently with `bgRun`.

### 5.3 Stop scope (single-instance default — SPEC Q2)

```scala
private def stopPriorJobs(
    service: BackgroundJobService,
    log: Logger,
    thisProject: ProjectRef,
    thisConfig: ConfigKey,
    singleInstance: Boolean
): Unit =
  val matching = service.jobs.filter { handle =>
    val sk    = handle.spawningTask
    val isOurs = sk.key.label == runReload.key.label
    if !isOurs then false
    else if singleInstance then true
    else sameProjectAndConfig(sk.scope, thisProject, thisConfig)
  }
  matching.foreach { h =>
    log.info(s"runReload: stopping prior job ${h.id} (${h.humanReadableName})")
    service.stop(h)
    service.waitForTry(h)
  }
```

Job identification uses `JobHandle.spawningTask: ScopedKey[?]`, which sbt 2.x
sets to the input task that called `runInBackground*` — so jobs we spawn from
`runReload` are tagged by the `runReload` key's label. Filtering on the label
gives us "all `runReload`-spawned jobs across the build"; `sameProjectAndConfig`
narrows that to the current scope when `runReloadSingleInstance` is `false`.

### 5.4 Cleanup hooks

Two complementary cleanups:

1. **`onUnload`**: stop every `runReload`-spawned job on sbt `reload` / `exit`.
   Modeled on sbt-revolver's `RevolverPlugin.settings` (`onUnload in Global ~=`).
   Using `bgJobService` makes this almost free — its `shutdown()` already runs
   on JVM exit; we just add an explicit early-cleanup so a `reload` doesn't
   leak a dangling fork on top of the new build's fork.

2. **Task cancellation**: if the user hits Ctrl-C inside `~runReload`, sbt
   raises `TaskCancellationStrategy.Signal`. The currently-running task
   (`runReload`) gets interrupted, but already-spawned jobs are independent
   threads. We don't intercept Ctrl-C globally — instead we rely on (1) plus
   the user pressing Enter to exit watch mode, after which the next sbt action
   (or sbt exit) triggers `onUnload`.

   This is a deliberate v1 simplification. If users report Ctrl-C leaving
   forks alive, we'll add a `Watch`-level `onTermination` hook (sbt 2.x doesn't
   have one yet but it's possible to attach via `state.attribute`).

### 5.5 Why we don't reuse `bgRun` directly

Tempting, but `bgRun` is an `InputKey`. You can't compose
`bgRun.toTask("").value` with a stop step in a clean way without parser
plumbing, and we'd inherit `bgRun`'s CLI args parser even though SPEC Q4 says
we don't accept CLI args. Copying `RunUtil.bgRunTask`'s body — about 25 lines —
is straightforward and gives us full control over ordering (compile → stop →
start) and the `runReloadArgs` setting hookup.

## 6. Public API

| Key | Type | Default | Purpose |
|---|---|---|---|
| `runReload` | `TaskKey[JobHandle]` | (impl) | Stop prior + start new bg fork. |
| `runReloadArgs` | `SettingKey[Seq[String]]` | `Nil` | App arguments passed to `main`. |
| `runReloadSingleInstance` | `SettingKey[Boolean]` | `true` | If `true`, stops every other `runReload` job in the build before starting; if `false`, only same `(project, config)`. |

Settings consumed (read-only) from sbt core, per SPEC Q5:

- `bgJobService`
- `bgCopyClasspath`, `bgHashClasspath` (scoped to `bgRun` per sbt's defaults)
- `run / mainClass`
- `run / forkOptions`
- `run / runner`
- `(Compile|Test) / fullClasspathAsJars`
- `(Compile|Test) / exportedProductJars`
- `fileConverter`

No `runReload / forkOptions` / `runReload / mainClass` overrides are defined.
Users who need them can still use sbt's normal scope delegation
(`runReload / javaOptions += ...`) because `forkOptions` already resolves
through scope delegation against `javaOptions`, `envVars`, etc.

## 7. Build setup

### 7.1 Layout

```
sbt-zio-reload/
├─ build.sbt
├─ project/
│  └─ build.properties              # sbt.version=2.0.0
├─ src/main/scala/com/jamesward/sbtzioreload/
│  └─ ZioReloadPlugin.scala
├─ src/sbt-test/                    # scripted tests, see §8
│  ├─ basic/test-main/
│  ├─ compile-failure/keeps-running/
│  └─ multi-instance/coexist/
├─ LICENSE                          # Apache-2.0
├─ README.md
├─ SPEC.md
└─ DESIGN.md
```

### 7.2 `build.sbt`

```scala
ThisBuild / organization := "com.jamesward"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / homepage     := Some(url("https://github.com/jamesward/sbt-zio-reload"))
ThisBuild / licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

lazy val root = rootProject
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-zio-reload",
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match
        case "3" => "2.0.0"
    },
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Wunused:all",
      "-language:strictEquality",
    ),
    scriptedLaunchOpts := scriptedLaunchOpts.value ++ Seq(
      s"-Dplugin.version=${version.value}",
    ),
    scriptedBufferLog := false,
  )
```

(`scalaVersion` is intentionally left unset — sbt 2.x picks the right Scala 3
version for plugins automatically.)

### 7.3 `project/build.properties`

```
sbt.version=2.0.0
```

## 8. Testing

Three layers, in order of fastest → slowest (matching the test-onion principle):

1. **Type-level / signature**: the plugin compiles. The `runReload` key's
   type is exactly `TaskKey[JobHandle]`. Verified by `compile`.

2. **Unit tests** for pure helpers (`stopPriorJobs`'s filter predicate,
   `sameProjectAndConfig`). Use ZIO Test (`zio-test`) so we match the wider
   ZIO ecosystem the plugin targets.

3. **Scripted tests** (sbt's own integration test harness) — the only way to
   exercise the actual fork+watch+restart loop. One scripted test per scenario:

   - `basic/test-main`: defines a `Test`-scoped main that writes a marker file,
     runs `Test/runReload`, asserts the marker file exists.
   - `basic/compile-main`: same for `Compile/runReload`.
   - `compile-failure/keeps-running`: starts a job, introduces a syntax error,
     attempts a re-run, asserts the original job is still running.
   - `multi-instance/coexist`: with `runReloadSingleInstance := false`,
     verifies `Compile/runReload` and `Test/runReload` jobs coexist.
   - `single-instance/replaces`: with the default, verifies a second
     `runReload` invocation kills the first job.
   - `cleanup/on-unload`: runs `runReload`, then `reload`; asserts no leftover
     jobs.

   Watch-loop tests (verifying `~runReload` actually restarts on file change)
   are awkward inside scripted because there's no clean way to send Enter to
   exit the watch. We'll start without them and add a manual smoke-test script
   in `notes/manual-smoke.md`.

## 9. Lifecycle walkthrough

To make the moving pieces concrete, here is what happens for
`./sbt "~Test/runReload"` in a single-project build:

```
sbt boot
  └─ JvmPlugin loads → ZioReloadPlugin auto-triggers (allRequirements)
     ├─ globalSettings: runReloadArgs := Nil, runReloadSingleInstance := true
     └─ projectSettings: Compile/runReload, Test/runReload defined

user types: ~Test/runReload
  └─ sbt enters watch (Continuous)
     └─ iteration 0: invoke Test/runReload
        ├─ Test/compile runs (via exportedProductJars dependency)
        ├─ stopPriorJobs(service, ...) → no jobs yet, no-op
        ├─ service.runInBackgroundWithLoader(...) spawns fork#1
        └─ returns JobHandle(id=1)
     └─ watch waits for source change OR Enter

user edits Foo.scala
  └─ watch trigger: re-invoke Test/runReload
     └─ iteration 1:
        ├─ Test/compile runs
        │   └─ if FAIL: throws, task aborts, fork#1 keeps running, watch resumes
        │   └─ if OK: continue
        ├─ stopPriorJobs(...) → finds fork#1, calls service.stop + waitForTry
        ├─ service.runInBackgroundWithLoader(...) spawns fork#2
        └─ returns JobHandle(id=2)

user presses Enter
  └─ sbt exits watch loop
     └─ fork#2 is still running (sbt 2.x default behavior)
     └─ user can run `bgStop 2` or exit sbt

user types: exit
  └─ onUnload fires → stops every runReload-spawned job
  └─ DefaultBackgroundJobService.shutdown() runs as a backstop
```

## 10. Future work / extension points

Captured here so v1 stays small but the door stays open.

| Idea | Sketch |
|---|---|
| **Testcontainers persistence** | Allow user code to register "session resources" that survive across reloads. Implementation idea: a small Java agent attached to each fork that reports its container handles back to sbt; on restart, sbt passes the handles to the new fork via system properties so it can reattach instead of starting fresh. ZIO-specific because `ZIO.scoped` makes "owned-by-this-process" vs "owned-by-this-session" a meaningful distinction. |
| **Play-style compile error UI** | When a compile fails during watch, serve `target/compile-errors.html` from the running fork's HTTP server (or a tiny embedded one we start) so browsers see a useful page instead of a stale or broken response. Likely needs a hook into the fork's request pipeline; ZIO-HTTP is a natural integration point. |
| **CLI args** | Surface `runReload` as an `InputKey` with the same `<args> -- <jvmArgs>` parser as `bgRun`. Backward compatible with the v1 setting (`runReloadArgs` becomes the default when no CLI args are passed). |
| **Per-process log tagging** | Port sbt-revolver's color tagging if the default per-job logger turns out to be insufficient in multi-fork scenarios. |
| **Cross-build to sbt 1.x** | Currently a non-goal. Would need a parallel implementation of the bg-job logic since sbt 1.x's `BackgroundJobService` API differs. |

## 11. Risks and unknowns

- **The `~` + non-blocking-task interplay** (§3.3): the current design returns
  the `JobHandle` immediately so `~` can re-trigger. If the SPEC Q3 answer
  ("runReload should block") really meant "the task itself should block until
  the bg process exits", that's incompatible with `~` and we need a different
  shape (probably a Command + helper task). Flagged for confirmation.
- **`spawningTask`-based job identification**: depends on the `ScopedKey`'s
  `key.label` matching the literal string `"runReload"`. Refactors that rename
  the key need to update the filter. Cheap to mitigate (pull the label from
  `runReload.key.label` rather than hard-coding it, as shown in §5.3).
- **sbt 2.0.0 just shipped** (June 14, 2026). API surface in the
  `sbt.internal.*` packages we touch (`RunUtil` is `private[sbt]`) is
  technically unstable. We avoid importing anything from `sbt.internal.*` in
  the plugin source — instead we replicate the small slice we need from
  `RunUtil.bgRunTask`. That keeps the plugin source on top of the public sbt
  API only.
- **Aggregation across subprojects** (SPEC Q11): with aggregate-by-default and
  multiple subprojects each defining their own main, root-level `~runReload`
  tries to start every subproject's main simultaneously. Combined with
  `runReloadSingleInstance := true` (the default), jobs would stomp on each
  other. We document this in the README and recommend `subproj/runReload` for
  multi-project builds, or `runReloadSingleInstance := false` if the user
  really wants concurrent forks.
