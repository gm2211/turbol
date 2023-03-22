import sbt.{Def, _}

object dependencies {
  val versionOfScala = "3.2.2"

  // Functional
  val fs2Version = "3.6.1"
  val zioVersion = "2.0.9"
  val zioInteropCatsVersion = "23.0.0.0"

  // Logging
  val logbackVersion = "1.4.5"
  val scalaLoggingVersion = "3.9.5"

  // Reflection / macros
  val lihaoyiSourcecodeVersion = "0.3.0"

  // Storage
  val h2Version = "2.1.214"
  val postgresVersion = "42.5.4"
  val doobieVersion = "1.0.0-RC1"

  // Serialization
  val circeVersion = "0.14.5"
  val circeYamlVersion = "0.14.2"

  // Server
  val http4sVersion = "0.23.18"

  // Utils
  val apacheCommonsVersion = "2.11.0"

  // Dependencies for JVM part of code
  val backendDeps = Def.setting(
    Seq(
      // Config
      "commons-io" % "commons-io" % apacheCommonsVersion,
      // Database
      "org.postgresql" % "postgresql" % postgresVersion,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      // Functional
      "co.fs2" %% "fs2-core" % fs2Version,
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-interop-cats" % zioInteropCatsVersion,
      // Logging
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      // Reflection / macros
      "com.lihaoyi" %% "sourcecode" % lihaoyiSourcecodeVersion,
      // Serialization
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-yaml" % circeYamlVersion,
      // Server
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    )
  )

  // Test dependencies
  val backendTestDeps = Def.setting(
    Seq(
      "com.h2database" % "h2" % h2Version
    ).map(_ % Test)
  )
}
