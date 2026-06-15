# sbt-zio-reload

An sbt build plugin that enables auto-reload for Scala ZIO applications

Like: https://github.com/spray/sbt-revolver

Only support sbt 2.x

Primary usage is for Test scoped Mains / ZIO Apps like in (uses sbt-revolver currently):
https://github.com/jamesward/javadoccentral/blob/master/build.sbt

Initially there probably isn't anything ZIO specific in this plugin but some future ZIO specific functionality may be added (like keeping testcontainers running and Play Framework like compile errors in web UI)

Users who add the plugin should only need to run `./sbt Test/runReload` to have the auto-restart for the server process. While rare, we should also support the main scoped reload (with main in src/main/scala) via `./sbt runReload`

## Open Questions

### 1. Tilde or no tilde?

This is the biggest design question. The line above says `./sbt Test/runReload` should "have the auto-restart". Two readings:

- **(a) One-shot + sbt's `~`.** `runReload` is a one-shot "stop existing, recompile, start in background" task. The user types `~Test/runReload` to enter the watch loop. This is sbt-revolver's model (`reStart` + `~reStart`). Composes cleanly with sbt's existing `~` machinery, scopes correctly for `Test/` vs `Compile/`, follows the convention users coming from sbt-revolver will expect.
- **(b) Watch baked in.** `runReload` itself enters the watch loop — typing `Test/runReload` (no tilde) starts the app and watches sources until the user hits Enter. Doable but awkward in sbt 2.x: a Command can't be config-scoped (`Test/runReload` is task syntax), so the implementation has to either push `~ Test/runReloadOnce` onto the state from the task, or use a pair (task + command alias) with sharp edges.

Which do you want?

**Answer:**

I think we need the ~ to get the watch support from sbt. Confirm. And if so, then yes we need the ~

### 2. Stop scope

When `runReload` runs, it needs to stop the previous instance. Should it stop:

- only the prior job spawned by `runReload` in the same `(project, config)`, or
- all jobs in that project, or
- all jobs across the build?

Default proposal: `(project, config)` so `Compile/runReload` and `Test/runReload` could coexist if a user wanted.

**Answer:**

Typically there would be port conflicts so default behavior should probably be single instance. Maybe a config param could override the default.

### 3. Companion tasks?

sbt-revolver provides `reStop` and `reStatus`. sbt 2.x already has `bgStop` and `bgList`/`ps`. Options:

- thin wrappers (`runStop`, `runStatus`) that filter to just `runReload`-spawned jobs, or
- rely on the built-in `bgStop` / `ps`.

**Answer:**

runReload should block. cancelling (Ctrl-C) should terminate the bg process.

### 4. Argument passing

sbt 2.x `bgRun` uses `args... -- jvmArgs...`. sbt-revolver used `--- `. Proposal: match sbt 2.x's `--` convention so muscle memory works.

**Answer:**

let's only allow args via sbt settings.

### 5. Settings overrides

Should `runReload` reuse `run / mainClass`, `run / forkOptions`, `run / runner` (like `bgRun` does), or also define `runReload / *` overrides? Proposal: reuse `run / *`; users can still override via `runReload / forkOptions := ...` thanks to sbt's normal scope delegation.

**Answer:**

No need for runReload overrides.

### 6. Compile failure behavior

If a triggered recompile fails, should the currently-running app:

- keep running (don't restart on broken build), or
- get stopped?

sbt-revolver keeps it running. Proposal: match that.

**Answer:**

Keep it running

### 7. Plugin enablement

Auto-trigger on every project (`trigger = allRequirements`, like sbt-revolver) or require explicit `enablePlugins(ZioReloadPlugin)`? Auto-trigger is friendlier; no opt-in step.

**Answer:**

auto-trigger

### 8. Coordinates / naming

- groupId / organization: `com.jamesward`?
- artifactId: `sbt-zio-reload`?
- plugin object name: `ZioReloadPlugin`?
- autoImport keys: `runReload` (and optionally `runStop` / `runStatus`)?
- License: Apache-2.0 (matches sbt-revolver)?

**Answer:**

looks good

### 9. Min sbt version

Pin `pluginCrossBuild / sbtVersion` to `2.0.0` (just released).

**Answer:**

yes

### 10. Future ZIO-specific extensions

SPEC mentions keeping testcontainers running across reloads and Play-style compile-error pages. Proposal: call them out as "out of scope for v1" extension points / non-goals in the design.

**Answer:**

yes

### 11. Multi-project builds

With `allRequirements`, every subproject gets `runReload`. If a user runs `~runReload` aggregated at the root, it would try to start every subproject's main. Options:

- disable aggregation for `runReload` (like `run` / `bgRun` do via `disableAggregation`), so users explicitly pick a subproject, or
- aggregate by default.

Proposal: disable aggregation, matching `run` / `bgRun`.

**Answer:**

aggregate by default

### 12. Output tagging / colors

sbt-revolver assigns per-process colors. sbt 2.x's `bgRun` already routes output through a per-job background logger. Do you want any special log tagging beyond what sbt 2.x already provides, or keep v1 minimal?

**Answer:**

no special log tagging
