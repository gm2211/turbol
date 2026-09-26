import sbt.{Def, *}

object dependencies {
  val versionOfScala = "3.9.0"

  // Dependency injection
  val macWireVersion = "2.6.7"

  // Functional
  val fs2Version = "3.14.0"
  val catsEffects = "3.7.1"
  val catsCore = "2.13.0"

  // Guava
  val guavaVersion = "33.7.1-jre"

  // Http
  val sttpVersion = "4.0.27"

  // Logging
  val logbackVersion = "1.6.4"
  val scalaLoggingVersion = "3.9.6"

  // Retry logic
  val catsRetryVersion = "4.0.0"

  // Reflection / macros
  val lihaoyiSourcecodeVersion = "0.4.4"

  // Storage
  val embeddedPostgresVersion = "2.2.2"
  val h2Version = "2.5.252"
  val postgresVersion = "42.7.13"
  val doobieVersion = "1.0.0-RC12"

  // Serialization
  val circeVersion = "0.14.16"
  val circeYamlVersion = "0.16.1"

  // Server
  val http4sVersion = "0.23.37"

  // Utils
  val apacheCommonsVersion = "2.22.0"

  // Test
  val scalatestVersion = "3.2.20"

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
      // Dependency injection
      "com.softwaremill.macwire" %% "macros" % macWireVersion % "provided",
      "com.softwaremill.macwire" %% "util" % macWireVersion,
      // Functional
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.typelevel" %% "cats-effect" % catsEffects,
      "org.typelevel" %% "cats-core" % catsCore,
      // Guava
      "com.google.guava" % "guava" % guavaVersion,
      // Http
      "com.softwaremill.sttp.client4" %% "core" % sttpVersion,
      // Retry logic
      "com.github.cb372" %% "cats-retry" % catsRetryVersion,
      // Logging
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Reflection / macros
      "com.lihaoyi" %% "sourcecode" % lihaoyiSourcecodeVersion,
      // Serialization
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-literal" % circeVersion,
      "io.circe" %% "circe-yaml" % circeYamlVersion,
      // Server
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion
    )
  )

  // Test dependencies
  val backendTestDeps = Def.setting(
    Seq(
      "com.h2database" % "h2" % h2Version,
      "io.zonky.test" % "embedded-postgres" % embeddedPostgresVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion,
      "org.scalatest" %% "scalatest-flatspec" % scalatestVersion,
      "org.scalatest" %% "scalatest-matchers-core" % scalatestVersion,
      "org.scalatest" %% "scalatest-shouldmatchers" % scalatestVersion
    ).map(_ % Test)
  )
}
