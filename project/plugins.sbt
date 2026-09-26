libraryDependencies += "commons-io" % "commons-io" % "2.22.0"

// Intellij
addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.4")
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.3.0")
// Packaging
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.12.0")
// Versioning
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.2.0")
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.5.0")
// Dependencies
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.0")
// Formatting
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
// Linting
addSbtPlugin("com.github.sbt" % "sbt-header" % "5.11.0")
