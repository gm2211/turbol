import au.com.onegeek.sbtdotenv.SbtDotenv.autoImport.{envFileName, envFromFile}
import scala.sys.process.*
import com.typesafe.sbt.SbtGit
import sbt.Keys.{libraryDependencies, resolvers}
import sbt.{Compile, Def, Resolver}
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations.*
import sbtrelease.Vcs
import org.apache.commons.io.FileUtils
import scala.sys.process.Process

import java.nio.charset.StandardCharsets

name := "turbol"
scalaVersion := dependencies.versionOfScala

enablePlugins(GitVersioning)
enablePlugins(UniversalPlugin)

val versionRegex = "v?([0-9]+.[0-9]+.[0-9]+)-?(.*)?".r
git.baseVersion := "0.0.0"
git.useGitDescribe := true
git.gitTagToVersionNumber := {
  case versionRegex(version, "") => Some(version)
  case versionRegex(version, commit) => Some(s"$version-$commit")
  case _ => None
}

inThisBuild(
  Seq(
    scalaVersion := dependencies.versionOfScala,
    organization := "com.gm2211.turbol",
    envFileName := "backend/var/conf/run.env",
    resolvers += Resolver.sbtPluginRepo("releases"),
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers += "Yahoo repo" at "https://dl.bintray.com/yahoo/maven/",
    publishArtifact := false,
    publish / skip := true,
    versionScheme := Some("semver-spec"),
    Compile / scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Yretain-trees",
      "-explaintypes",
      "-unchecked",
      "-language:implicitConversions",
      "-Werror",
      "-Wunused:all",
      "-Wunused:imports",
      "-Wvalue-discard"
    ),
    Compile / javacOptions ++= Seq(
      "-source",
      "19",
      "-target",
      "19",
      "-Xlint:unchecked",
      "-Xlint:deprecation",
      "-Xmx3500m",
      "-Xss4M"
    )
  )
)

// Reusable settings for all modules
lazy val ensureDockerBuildx =
  taskKey[Unit]("Ensure that docker buildx configuration exists")
lazy val dockerBuildAndPushWithBuildx =
  taskKey[Unit]("Build docker images using buildx")
val publishDocker = taskKey[Unit]("Publish Docker images")
lazy val dockerBuildxSettings = Seq(
  ensureDockerBuildx := {
    if (Process("docker buildx inspect multi-arch-builder").! == 1) {
      Process(
        "docker buildx create --use --name multi-arch-builder",
        baseDirectory.value
      ).!
    }
  },
  dockerBuildAndPushWithBuildx := {
    println(s"Building and pushing image with Buildx ${dockerCommands.value}")
    dockerAliases
      .value
      .foreach { alias =>
        println("FOOOOO")
        if (false) {
          Process(
            "docker buildx build --platform=linux/arm64,linux/amd64 --push -t " +
              alias + " .",
            baseDirectory.value / "target" / "docker" / "stage"
          ).!
        }
      }
  },
  Docker / publish := Def
    .sequential(
      Docker / publishLocal,
      ensureDockerBuildx,
      dockerBuildAndPushWithBuildx
    )
    .value,
  publishDocker := (Docker / publish).value
)

// Build tasks
lazy val publishFrontendDocker =
  taskKey[Unit]("Runs build script for frontend docker image")

// Modules
lazy val frontend = project
  .in(file("frontend"))
  .settings(
    publishFrontendDocker := {
      if (false) {
        val exitCode =
          Seq("bash", s"${baseDirectory.value}/docker-build-and-push.sh").!
        if (exitCode != 0) {
          throw new RuntimeException("Failed to build frontend docker image")
        }
      }
    }
  )

lazy val grib = project
  .in(file("grib"))

lazy val deployment = project
  .in(file("deployment"))

lazy val root = project
  .in(file("."))
  .aggregate(backend)
  .settings(
    Compile / run := (backend / Compile / run).evaluated
  )

// Backend
lazy val backend = project
  .in(file("backend"))
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(JavaServerAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(dockerBuildxSettings)
  .settings(
    (Docker / publish) := (Docker / publish)
      .dependsOn(frontend / publishFrontendDocker)
      .value
  )
  .settings(
    moduleName := "turbol-backend",
    // Linting and formatting
    compile := (
      (Compile / compile) dependsOn (Compile / scalafmtAll, Compile / scalafmtCheck)
    ).value,
    // Deps
    libraryDependencies ++= dependencies.backendDeps.value,
    libraryDependencies ++= dependencies.backendTestDeps.value,
    // Docker
    dockerRepository := Some("docker.io"),
    dockerUsername := Some("gm2211"),
    dockerBaseImage := "openjdk:19-jdk-bullseye",
    Docker / packageName := "turbol",
    version := SbtGit.git.gitDescribedVersion.value.getOrElse(""),
    dockerAliases := Seq(
      DockerAlias(
        dockerRepository.value,
        dockerUsername.value,
        (Docker / packageName).value,
        Some(version.value)
      ),
      DockerAlias(
        dockerRepository.value,
        dockerUsername.value,
        (Docker / packageName).value,
        Some("latest")
      )
    ),
    // Trick to get sbt-native-packager to create these directories without having to write my own dockerfile
    // we don't actually need to expose these volumes, but it doesn't matter since we deploy with k8s
    dockerExposedVolumes := Seq("/opt/docker/var/log", "/opt/docker/var/conf"),
    // Run
    Compile / mainClass := Some("com.gm2211.turbol.Launcher"),
    // Test
    Test / fork := true,
    Test / envFileName := "backend/var/conf/test.env",
    Test / envVars := (Test / envFromFile).value,
    // Copyright
    headerLicense := Some(
      HeaderLicense.Custom(
        """|Copyright 2020 Giulio Mecocci
           |
           |All rights reserved.
           |""".stripMargin
      )
    )
  )

// Release
val checkIsDevelop = taskKey[Unit](
  "Makes sure the current branch is develop - useful when releasing."
)
checkIsDevelop := {
  assert(
    SbtGit.git.gitCurrentBranch.value.contains("develop"),
    "Current branch is not develop - cannot release from here."
  )
}

lazy val initialVcsChecks = {
  st: State =>
    def vcs(st: State): Vcs = {
      Project
        .extract(st)
        .get(releaseVcs)
        .getOrElse(
          sys.error(
            "Aborting release. Working directory is not a repository of a recognized VCS."
          )
        )
    }

    val extracted = Project.extract(st)
    val hasUntrackedFiles = vcs(st).hasUntrackedFiles
    val hasModifiedFiles = vcs(st).hasModifiedFiles
    if (hasModifiedFiles) {
      sys.error(s"""Aborting release: unstaged modified files
         |
         |Modified files:
         |
         |${vcs(st).modifiedFiles.mkString(" - ", "\n", "")}
        """.stripMargin)
    }
    if (hasUntrackedFiles && !extracted.get(releaseIgnoreUntrackedFiles)) {
      sys.error(
        s"""Aborting release: untracked files. Remove them or specify 'releaseIgnoreUntrackedFiles := true' in settings
         |
         |Untracked files:
         |
         |${vcs(st).untrackedFiles.mkString(" - ", "\n", "")}
          """.stripMargin
      )
    }

    st.log.info("Starting release process off commit: " + vcs(st).currentHash)
    st
}

val releaseVersionTask: Def.Initialize[Task[String]] = Def.task {
  import sbtrelease.Version
  val releaseVersion = Version(version.value)
    .map(v => v.bumpMinor.withoutQualifier.string)
    .getOrElse {
      throw new IllegalArgumentException(s"Invalid version: ${version.value}")
    }
  releaseVersion
}
val updateVersionToDeploy =
  taskKey[Unit]("Updates the version that is deployed in production.")
updateVersionToDeploy := {
  val deploymentVariablesFilename = "deployment/digital-ocean/variables.tf"
  val content =
    FileUtils.readFileToString(
      file(deploymentVariablesFilename),
      StandardCharsets.UTF_8
    )
  val versionToBeDeployed = releaseVersionTask.value

  println(s"Version to be deployed $versionToBeDeployed")

  val updatedContent: String =
    content.replaceAll(
      """
        |variable prod_app_version \{
        |  default = ".*"
        |\}""".stripMargin,
      s"""
         |variable prod_app_version {
         |  default = "$versionToBeDeployed"
         |}""".stripMargin
    )

  FileUtils.writeStringToFile(
    file(deploymentVariablesFilename),
    updatedContent,
    StandardCharsets.UTF_8
  )

  SbtGit
    .GitKeys
    .gitRunner
    .value
    .apply("add", deploymentVariablesFilename)(file("."), Logger.Null)
  SbtGit
    .GitKeys
    .gitRunner
    .value
    .apply(
      "commit",
      "-m",
      "Updated app version to be deployed."
    )(file("."), Logger.Null)
}

releaseTagName := { releaseVersionTask.value }
releaseUseGlobalVersion := false
releaseProcess := Seq(
  ReleaseStep((x: State) => x, initialVcsChecks),
  releaseStepTask(checkIsDevelop),
  checkSnapshotDependencies,
  runClean,
  releaseStepTask(updateVersionToDeploy),
  tagRelease,
  pushChanges
)
