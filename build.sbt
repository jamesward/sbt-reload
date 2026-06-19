sbtPlugin := true
enablePlugins(SbtPlugin)

organization := "com.jamesward"
name := "sbt-reload"
homepage     := Some(url("https://github.com/jamesward/sbt-reload"))
licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

developers := List(
  Developer(
    "jamesward",
    "James Ward",
    "james@jamesward.com",
    url("https://jamesward.com")
  )
)

versionScheme := Some("semver-spec")

javacOptions ++= Seq("-source", "17", "-target", "17")
scalacOptions ++= Seq("-release", "17")

libraryDependencies += "org.scalameta" %% "munit" % "1.3.3" % Test

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}",
)
scriptedBufferLog := false
