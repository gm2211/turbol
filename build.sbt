import au.com.onegeek.sbtdotenv.SbtDotenv.autoImport.{envFileName, envFromFile}
import com.typesafe.sbt.SbtGit
import sbt.Keys.{libraryDependencies, resolvers}
import sbt.{Compile, Def, Resolver}
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._
import sbtrelease.Vcs
import org.apache.commons.io.FileUtils
import scala.sys.process.Process

import java.nio.charset.StandardCharsets

name := "turbol"
scalaVersion := dependencies.versionOfScala
Compile / scalacOptions ++= Seq(
  "-Ypartial-unification",
  "-new-syntax",
  "-rewrite",
  "-deprecation"
)
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

enablePlugins(GitVersioning)
enablePlugins(UniversalPlugin)

val versionRegex = "[v]?([0-9]+.[0-9]+.[0-9]+)-?(.*)?".r
git.baseVersion := "0.0.0"
git.useGitDescribe := true
git.gitTagToVersionNumber := {
  case versionRegex(version, "")     => Some(version)
  case versionRegex(version, commit) => Some(s"$version-$commit")
  case _                             => None
}

inThisBuild(
  Seq(
    scalaVersion := dependencies.versionOfScala,
    organization := "com.gm2211.turbol",
    envFileName := "run.env",
    resolvers += Resolver.sbtPluginRepo("releases"),
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers += "Yahoo repo" at "https://dl.bintray.com/yahoo/maven/",
    publishArtifact := false,
    publish / skip := true,
    versionScheme := Some("semver-spec")
  )
)

// Reusable settings for all modules
lazy val compileScalastyle = taskKey[Unit]("compileScalastyle")
lazy val ensureDockerBuildx =
  taskKey[Unit]("Ensure that docker buildx configuration exists")
lazy val dockerBuildWithBuildx =
  taskKey[Unit]("Build docker images using buildx")
lazy val dockerBuildxSettings = Seq(
  ensureDockerBuildx := {
    if (Process("docker buildx inspect multi-arch-builder").! == 1) {
      Process(
        "docker buildx create --use --name multi-arch-builder",
        baseDirectory.value
      ).!
    }
  },
  dockerBuildWithBuildx := {
    streams.value.log("Building and pushing image with Buildx")
    dockerAliases.value.foreach(alias =>
      Process(
        "docker buildx build --platform=linux/arm64,linux/amd64 --push -t " +
          alias + " .",
        baseDirectory.value / "target" / "docker" / "stage"
      ).!
    )
  },
  Docker / publish := Def
    .sequential(
      Docker / publishLocal,
      ensureDockerBuildx,
      dockerBuildWithBuildx
    )
    .value
)

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
    moduleName := "turbol-backend",
    Compile / ideOutputDirectory := Some(
      target.value.getParentFile / "out/production"
    ),
    // Linting and formatting
    scalastyleFailOnWarning := true,
    compileScalastyle := (Compile / scalastyle).toTask("").value,
    compile := (
      (Compile / compile) dependsOn (compileScalastyle, Compile / scalafmtAll, Compile / scalafmtCheck)
    ).value,
    // Deps
    libraryDependencies ++= dependencies.backendDeps.value,
    libraryDependencies ++= dependencies.backendTestDeps.value,
    // Docker
    dockerRepository := Some("docker.io"),
    dockerUsername := Some("gm2211"),
    dockerBaseImage := "openjdk:19",
    Docker / packageName := "turbol",
    version := SbtGit.git.gitDescribedVersion.value.getOrElse(""),
    dockerUpdateLatest := true,
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
    // Run
      Compile / mainClass := Some ("com.gm2211.turbol.backend.Launcher"),
    // Test
    Test / ideOutputDirectory := Some(target.value.getParentFile / "out/test"),
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

  SbtGit.GitKeys.gitRunner.value
    .apply("add", deploymentVariablesFilename)(file("."), Logger.Null)
  SbtGit.GitKeys.gitRunner.value.apply(
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
