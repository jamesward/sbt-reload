# AGENTS.md

Notes for agents working on this plugin. Captures non-obvious gotchas
discovered while debugging, so future sessions don't have to re-derive them.

## Plugin design

- `bgJobService` is a **single, global** `BackgroundJobService` shared by
  every project in the build. `service.jobs` returns jobs from all projects.
  When stopping prior jobs, filter by the full `ScopedKey` (`spawningTask ==
  myScope`), **not** by `spawningTask.key.label` — every project's
  `runReload` has the same label, so a label-only filter kills every
  subproject's fork. This was the original "subproject" bug.
- `runReload` is registered with the service via
  `service.runInBackground(spawningTask, state)`. Pass `Keys.resolvedScoped.value`
  as `spawningTask` so each project/config gets a distinct identity.
- `onUnload` (sbt `reload` / `exit`) intentionally stops **all** runReload
  jobs across the build via `stopAllReloadJobs` (label match). The
  per-scope `stopReloadJobsFor` is for the single-instance-per-scope
  semantics inside `runReloadTask` and `watchOnTermination`.

## Capturing the current scope

- Inside `Def.task { ... }`: `Keys.resolvedScoped.value` is the `ScopedKey`
  of the surrounding task (e.g. `ScopedKey(<projectRef>/Compile, runReload)`).
- Inside a setting body whose own scope is *not* the one you want
  (e.g. `runReload / watchOnTermination` — the setting is at scope
  `runReload/watchOnTermination`, but you need the scope of `runReload`):
  use `(runReload / Keys.resolvedScoped).value`. Capture it in a `val`
  outside the lambda so the closed-over value is the runReload scope, not
  the watchOnTermination scope.
- ScopedKey equality includes the project axis (`Select(ProjectRef(...))`)
  and the config axis (`Select(ConfigKey("compile"))`), so two projects'
  runReload tasks compare unequal — exactly what we want.

## Scripted tests

- Live under `src/sbt-test/<group>/<test>/` with:
  - `build.sbt` — test project's build
  - `project/plugins.sbt` — pulls in the plugin via
    `addSbtPlugin("com.jamesward" % "sbt-reload" % sys.props("plugin.version"))`
  - `test` — script of `>` commands (sbt tasks) and `$` commands (file ops);
    `#` is a comment
- `build.sbt` at the repo root sets `scriptedLaunchOpts +=
  s"-Dplugin.version=${version.value}"` and `scriptedBufferLog := false`.
  The latter is essential — without it, the per-test sbt's stdout is
  buffered and only printed on failure, which makes debugging painful.
- Run all tests: `./sbt scripted`. Run one: `./sbt 'scripted multi/subprojects'`.
  Pass marker is `[info] + group/test`, fail marker is `[error] x group/test`.
- Scripted copies the test directory into a fresh temp dir
  (`/tmp/sbt_<hash>/`) per run, so no clean step is needed inside the test.

### **GOTCHA: stale plugin jar in coursier cache**

When iterating on the plugin's source, `./sbt scripted` *can silently use a
previously-published version of the plugin from Maven Central* instead of
the freshly published-local jar. Symptoms:

- Source changes have no effect on test behavior.
- New `log.info` calls don't show up in the scripted output.
- Bytecode in `~/.ivy2/local/.../sbt-reload_sbt2_3.jar` contains your
  changes (verify with `unzip -p ... | grep <new-string>`), but the test
  acts like the old code is running.

Cause: dynver computes `version` from the latest git tag. If `HEAD` is on a
release tag (e.g. `v0.0.2`), the local version equals a published version,
and the test's resolver picks the cached Maven Central jar at
`~/.cache/coursier/v1/https/repo1.maven.org/maven2/com/jamesward/sbt-reload_sbt2_3/<v>/`.

Fixes (any one):

1. Delete the cached jar before iterating:
   ```
   rm -rf ~/.cache/coursier/v1/https/repo1.maven.org/maven2/com/jamesward/sbt-reload_sbt2_3
   ```
2. Develop on a commit that isn't a release tag so dynver appends
   `+N-<sha>-SNAPSHOT`.
3. Temporarily override `version` in `build.sbt` to a SNAPSHOT.

To verify which jar scripted actually loaded, look at the `Wrote ...pom`
and `published ... to /home/.../ivy2/local/...` lines (those are the
local publish), then check whether the test inside the temp build used the
strings only present in the new bytecode. `find ~/.cache ~/.ivy2 -name
"sbt-reload*.jar"` enumerates all candidate jars on disk.

Note: sbt 2.x's local publish appears to be content-addressed — if the
generated jar bytes are identical to what's already at the destination,
the file's mtime won't update even though the publish "succeeded". Don't
use mtime alone to confirm a fresh build; check the contents.

## Other notes

- `enablePlugins(SbtPlugin)` in the root `build.sbt` is what makes the
  `scripted` task available. Don't remove it.
- `inConfig(Compile)(reloadSettings) ++ inConfig(Test)(reloadSettings)`
  registers `runReload` in both configs. Each ends up with its own
  ScopedKey (config axis differs), so per-scope stop logic naturally
  treats `Compile/runReload` and `Test/runReload` as independent jobs.
- `Def.uncached(runReloadTask.value)` opts out of sbt 2.x action caching
  for `runReload` — necessary because the side effect (a long-running
  fork) is not a cacheable artifact.

## Per-project independent restart (skip-when-unchanged)

`runReload` keeps a per-scope fingerprint of its inputs in a
plugin-internal `ConcurrentHashMap[ScopedKey[?], Vector[String]]`. On
each invocation the task:

1. Builds a fingerprint from the current `fullClasspathAsJars`,
   `run / mainClass`, and `runReloadArgs`.
2. Checks `service.jobs.exists(_.spawningTask == thisScope)` to see if
   a fork is still alive.
3. If both are true (running fork + fingerprint matches the last
   recorded value) it logs at debug and returns without stopping or
   restarting. Otherwise it stops the prior job (if any), starts a new
   fork, and records the new fingerprint.

The fingerprint is cleared per-scope on `watchOnTermination` and
globally on `onUnload`. This is what makes aggregated `~Test/runReload`
restart only the project whose sources actually changed: the others'
classpaths come back identical and they take the no-op path.

### **GOTCHA: VirtualFileRef.toString is the path, not the content hash**

`HashedVirtualFileRef extends VirtualFileRef`. `VirtualFileRef.toString`
(from `BasicVirtualFileRef`) returns just `id` — the encoded path. It
does **not** include the content hash. Two different builds of the same
source file produce the same `toString`.

To get a content-aware fingerprint, call `.contentHashStr()` on the
`HashedVirtualFileRef` directly:

```scala
val fingerprint =
  classpath.map(_.data.contentHashStr).toVector
```

Symptom of using `toString`: `runReload` always takes the skip path even
after a real source change, so the fork doesn't pick up new code.

## Verifying liveness in scripted tests

Use a PID-based liveness check rather than shutdown hooks (which may not
fire under `Process.destroyForcibly`).

- Each app writes its own PID to a marker file via an atomic move:
  ```scala
  val pid = ProcessHandle.current().pid()
  Files.writeString(tmp.toPath, pid.toString)
  Files.move(tmp.toPath, marker.toPath, ATOMIC_MOVE, REPLACE_EXISTING)
  ```
- A test task reads the PID and checks
  `ProcessHandle.of(pid).isPresent && handle.isAlive`. Linux PIDs are
  not reused for a long time, so this is reliable for short tests.
- The forked JVM's working directory is the project's `baseDirectory`
  (set by the default `run / forkOptions`), so `target/pid.txt` resolves
  per-project even in multi-project builds.

### **GOTCHA: action cache memoizes test tasks across calls**

sbt 2.x runs every task through `ActionCache`. If a custom task's
declared inputs (`.value` calls) don't change between two invocations in
the same session, the cache returns the previous result and **the task
body never runs again**. This bites scripted tests that depend on side
effects: a `waitForStarted` task that polls the filesystem will silently
not poll the second time, even though the file state has changed.

Fix: wrap the body in `Def.uncached`:

```scala
waitForStarted := Def.uncached {
  // body runs every time the task is invoked
  ...
}
```

Apply this to every helper task in a scripted build that:
- has side effects (file IO, sleep, mutation),
- depends on filesystem state that isn't an explicit `.value` input, or
- is expected to re-run on every `>` invocation in the test script.

Symptom: a task is invoked multiple times in the test script but only
logs from the first call appear; later assertions fail with stale state
(e.g., FileNotFoundException for a file written between calls).
