# sbt-reload

An sbt 2.x plugin that auto-restarts your Scala application on source changes. Like [sbt-revolver](https://github.com/spray/sbt-revolver), but built on sbt 2.x primitives.

## Install

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("com.jamesward" % "sbt-reload" % "<version>")
```

No other configuration needed — the plugin auto-activates on all JVM projects.

## Usage

### Watch mode

```
./sbt ~runReload
```

For a `Test`-scoped main:

```
./sbt ~Test/runReload
```

This will:
1. Compile and start your app in a forked JVM
2. Watch for source changes
3. On change: stop the running app, recompile, restart
4. Press Enter to exit watch mode (stops the app)

### Watching output from another client (e.g. an AI agent)

sbt 2.x runs a persistent server that multiple clients can connect to. When one
client runs `~runReload`, its forked app's output is also captured to a
per-config file so a *different* client can view it read-only:

```
./sbt reloadOutput        # Compile-scoped runReload output
./sbt Test/reloadOutput   # Test-scoped runReload output
```

`reloadOutput` prints whatever the running app has emitted since the last call
and returns immediately (it does not stream/block, so it never starves the
shared server's command loop). An agent can poll it after triggering a rebuild
to see what the app printed. The capture file is truncated on each restart, and
`reloadOutput` resets accordingly — so the first output of a freshly restarted
fork is shown in full, even if it is longer than what the previous fork emitted.

`reloadOutput` reports on **every running `runReload` fork in its config**, keyed
off the live background-job service rather than its own scope. So in a
multi-project build you can run `reloadOutput` from the aggregate root (or any
project) and still see the output of whichever subproject's fork is running —
you don't have to scope it to the subproject that happens to host the app. When
more than one fork is running, each line is prefixed with the project id. The
config axis is still honored: `reloadOutput` shows Compile forks,
`Test/reloadOutput` shows Test forks.

When no fork has produced anything since the last call, `reloadOutput` prints a
single `reloadOutput: no new output` line — not one line per running fork. This
keeps a streaming `~reloadOutput` quiet (it only prints real output) while still
giving a manual one-shot poll a single confirmation that there was nothing new.

To follow the output continuously, run it under sbt's watch:

```
./sbt ~reloadOutput        # stream Compile-scoped runReload output
./sbt ~Test/reloadOutput   # stream Test-scoped runReload output
```

`~reloadOutput` re-runs `reloadOutput` every time the capture file changes
(it is declared as the task's `fileInputs`), so new output is printed as the
app emits it — a read-only tail. Press Enter to exit.

> **Do not run `~reloadOutput` at the same time as `~runReload` on the same sbt
> server.** sbt has a limitation where two concurrent continuous (`~`) builds on
> one server share a single file-watch repository, and after the first triggered
> rebuild one of the two watch sessions stops receiving further file-change
> events (the session that was started *first* is the one that goes deaf). This
> is **not** specific to this plugin — it reproduces with two plain `~compile`
> sessions and no plugin involved — but the `~runReload` + `~reloadOutput`
> combination is a natural way to hit it, and the casualty is usually
> `~runReload` (started first), which silently stops restarting on edits.
>
> The intended pattern for "watch + observe from another client" avoids this
> entirely: run **one** `~runReload` watch, and from the other client **poll the
> one-shot `reloadOutput`** (it is a non-blocking poll by design — see above)
> instead of `~reloadOutput`. A single `~` session restarts reliably on every
> change, and each one-shot `reloadOutput` call returns whatever the app has
> emitted since the last poll.

### Multi-project builds

`runReload` is scoped per project and per config, so each
`<project>/<config>/runReload` manages its own forked JVM and its own capture
file. This has two consequences:

- Aggregated `~runReload` (run from a project that aggregates others) restarts
  only the subproject(s) whose inputs actually changed. Each scope keeps a
  content fingerprint of its classpath, `run / mainClass`, and `runReloadArgs`;
  if a scope's fingerprint is unchanged and its fork is still alive, that scope
  takes a no-op path instead of restarting.
- Stopping is isolated to the matching scope. Exiting `~runReload` stops only
  that scope's fork (matched by project + config), not other subprojects'.
  `reload`/`exit` stop every `runReload` fork across the build.


## Configuration

### Main class

Uses `run / mainClass` by default. Override if needed:

```scala
run / mainClass := Some("com.example.MyApp")
```

Or for Test scope:

```scala
Test / run / mainClass := Some("com.example.TestServer")
```

### JVM options

```scala
run / javaOptions ++= Seq("-Xmx512m", "-Dconfig.file=dev.conf")
```

### App arguments

```scala
runReloadArgs := Seq("--port", "8080")
```

### Environment variables

```scala
run / envVars := Map("DATABASE_URL" -> "jdbc:postgresql://localhost/dev")
```

## How it works

- Uses sbt's `BackgroundJobService` to manage the forked process lifecycle
- Stops prior `runReload` jobs before starting a new one (single-instance per
  project/config by default)
- On compile failure, the running app keeps serving — no restart until the build succeeds
- On watch exit (Enter or client disconnect), only the forked process for the
  exiting scope is terminated via `watchOnTermination`, matched by project +
  config so other subprojects' forks keep running
- On sbt `reload` or `exit`, cleanup runs via `onUnload`, stopping every
  `runReload` fork across the build
- `reloadOutput` is fed by `runReload`, which wraps the fork's background logger
  in a tee that writes each line to `target/reload/<config>-output.log` (under
  sbt 2.x's per-project output `target`, not `<base>/target`). The live
  `~runReload` view is unaffected because the tee still forwards to the original
  logger. `reloadOutput` reads new bytes since its last call (line-aligned), and
  resets to the start of the file whenever `runReload` restarts the fork (which
  truncates the capture file), so it is a non-blocking poll rather than a stream.

## Requirements

- sbt 2.0.0+

## License

Apache-2.0
