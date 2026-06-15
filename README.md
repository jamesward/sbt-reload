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
- Stops prior `runReload` jobs before starting a new one (single-instance by default)
- On compile failure, the running app keeps serving — no restart until the build succeeds
- On watch exit (Enter or client disconnect), the forked process is terminated via `watchOnTermination`
- On sbt `reload` or `exit`, cleanup runs via `onUnload`

## Requirements

- sbt 2.0.0+

## License

Apache-2.0
