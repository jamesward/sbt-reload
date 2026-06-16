sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("com.jamesward" % "sbt-reload" % v)
  case _       => sys.error("The system property 'plugin.version' is not defined.")
}
