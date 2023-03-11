libraryDependencies += "commons-io" % "commons-io" % "2.11.0"

// Intellij
addSbtPlugin("org.jetbrains" % "sbt-ide-settings" % "1.0.0")
addSbtPlugin("au.com.onegeek" %% "sbt-dotenv" % "2.1.146")
// Packaging
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.6.1")
// Versioning
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.13")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")
// Dependencies
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.1")
// Docker
addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.8.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")
// Linting
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.4.0")
