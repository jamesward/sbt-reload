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
  `runReloadTask` uses this as the job's `spawningTask`.

### **GOTCHA: `Keys.resolvedScoped` ignores any scope prefix**

`(runReload / Keys.resolvedScoped).value` does **NOT** give you the `runReload`
scope. `resolvedScoped` always resolves to the *enclosing* setting/task's own
key, regardless of the scope you prefix it with. So inside
`runReload / watchOnTermination := { val rs = (runReload / Keys.resolvedScoped).value; ... }`,
`rs` is the `watchOnTermination` key, not `runReload`.

This previously broke `~runReload` cancel: `watchOnTermination` tried to stop
jobs via `service.jobs.filter(_.spawningTask == rs)`, but the job's
`spawningTask` is `<config>/runReload` while `rs` was the `watchOnTermination`
key, so the filter matched nothing and the fork survived the cancel. There is
no scripted assertion on the interactive `~` cancel itself, so it slipped
through; `src/sbt-test/server/cancel` now covers it by invoking the real
`(Compile / runReload / watchOnTermination).value` handler and asserting the
forked PID dies.

- To stop the right job from `watchOnTermination`, capture the setting's own
  scope (`Keys.resolvedScoped.value.scope`, which carries the correct project
  and config axes) and match jobs by **project + config + `runReload` label**
  (`stopReloadJobsForScope`) rather than full `ScopedKey` equality. This still
  isolates the right project/config without depending on the task axis.
- ScopedKey equality includes the project axis (`Select(ProjectRef(...))`)
  and the config axis (`Select(ConfigKey("compile"))`), so two projects'
  runReload tasks compare unequal — exactly what we want for the
  `runReloadTask` skip/stop path, which reuses the *same* `resolvedScoped`
  value for both spawning and matching.

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

## reloadOutput: capture, restart-awareness, and cross-scope reads

- `runReload` tees the fork's logged output to
  `<Compile|Test target>/reload/<config>-output.log` via `TeeLogger` and
  records that file in a plugin-internal `ConcurrentHashMap[ScopedKey,File]`
  (`captureFiles`) keyed by the fork's `spawningTask`.
- `OutputReader.poll` (pure, unit-tested in `OutputReaderSuite`) does the
  incremental, line-aligned read. It takes a per-file `ReadState(epoch,offset)`
  and the writer's current epoch. **Restart detection is epoch-based**:
  `runReload` bumps a per-file epoch (`outputEpochs`) the instant it truncates
  the capture file for a new fork, so the reader resets to byte 0 even when the
  new fork's output is already longer than the old offset. A length-only
  truncation check (`offset > len`) misses that and drops the head of the new
  output — the original "no output after reload" bug. (Length-shrink is kept as
  a backstop for the truncation/epoch-bump race window.)

### **GOTCHA: `reloadOutput` reads running jobs, not its own scope**

`reloadOutput` does **not** read its own scope's capture file. It iterates the
live `bgJobService.value.jobs`, filters to `runReload` jobs in its own config,
maps each to its registered capture file, and prints. This is what lets bare
`reloadOutput` at the aggregate root surface a *subproject's* running fork —
the original multi-project "no output captured yet" bug came from each
aggregated invocation reading its own (empty) scope's path.

Two consequences to preserve:

- `reloadOutput / aggregate := false`. Because the task already reports on every
  running fork, letting it aggregate would print each fork's output once per
  aggregated subproject.
- `reloadOutput / fileInputs` is a **build-wide** glob
  (`<root>/target ** /reload/<config>-output.log`), not the own-scope file, so
  `~reloadOutput` streams every fork's output even when invoked from the root.
  `src/sbt-test/multi/output` covers the cross-scope read (asserts the running
  Compile fork is visible from the root via `bgJobService`).

### **GOTCHA: don't print the "no new output" status per fork**

`reloadOutputTask` iterates *every* running fork in its config, but it must only
print **actual new output lines** per fork (prefixed by project id when more than
one fork is running). The per-fork "no new output" / "no output captured yet"
status from `OutputReader.poll` is deliberately **not** printed — with N running
forks that floods the console with N identical status lines on every call, and
under `~reloadOutput` (which re-runs on each capture-file change) every trigger
reprints all N. The original report was a 4-project build showing
`[login]/[www]/[mcp]/[api] reloadOutput: no new output` four times each under
`~Test/reloadOutput`.

Instead, the task tracks whether *any* fork produced new lines and, only when
none did, emits a **single** unprefixed `reloadOutput: no new output`. So a
manual one-shot poll still gets one confirmation line, while a streaming
`~reloadOutput` stays quiet except for real output. `OutputReader.Result.message`
is still produced (and unit-tested in `OutputReaderSuite`) — the plugin just
doesn't surface it per fork anymore.

### **GOTCHA (sbt limitation): two concurrent `~` watches on one server drop events**

Running `~runReload` in one client and `~reloadOutput` in another (same sbt
server) is unreliable: the first triggered rebuild works, but after that one of
the two watch sessions stops receiving file-change events. This is an **sbt
limitation, not a plugin bug** — it reproduces with two plain `~compile`
sessions and zero plugin code:

```
# client 1
sbt --jvm-client "~compile"     # started first
# client 2
sbt --jvm-client "~compile"     # started second
# edit a source 3 times:
#   client 1 (first):  1 trigger   <- goes deaf after the first rebuild
#   client 2 (second): 3 triggers  <- keeps working
```

Root cause (from `sbt/internal/Continuous.scala`): watches are per-channel
(`watchStates` keyed by channel), but they share a single
`globalFileTreeRepository` in the one `State`, which `beforeCommand`/
`afterCommand` swap in/out and stash under a single `stashedRepo` attribute key.
With two channels the shared registration/stamp state gets disturbed after the
first trigger and the *first-started* watch stops seeing events. Verified
empirically: single `~runReload` catches every change; `~runReload` +
`~reloadOutput` catches only the first; the victim is whichever `~` started
first (usually `~runReload`).

The supported pattern (documented in README) sidesteps it: run **one**
`~runReload` and, from the other client, call the **one-shot** `reloadOutput`
(non-blocking poll), not `~reloadOutput`. Verified: with a single `~runReload`
session, three successive edits all triggered, and a one-shot `reloadOutput`
after each showed the new output. Do not "fix" this by adding a second `~`.

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
