import sbt._
import Keys._

ThisBuild / organization := "com.suprnation"
ThisBuild / version := "2.0.0-RC2"
ThisBuild / organizationName := "SuprNation"
ThisBuild / startYear := Some(2024)
ThisBuild / licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.txt"))

ThisBuild / crossScalaVersions := Seq("2.13.12", "3.3.0")
ThisBuild / scalaVersion := crossScalaVersions.value.head

lazy val commonSettings = Seq(
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.5.2",
    "org.scalatest" %% "scalatest" % "3.2.19" % Test
  ),
  publishMavenStyle := true,
  publishTo := {
    val owner = "suprnation"
    val repo = "cats-actors"
    if (isSnapshot.value)
      Some("GitHub Package Registry".at(s"https://maven.pkg.github.com/$owner/$repo"))
    else
      Some("GitHub Package Registry".at(s"https://maven.pkg.github.com/$owner/$repo"))
  },
  publishConfiguration := publishConfiguration.value.withOverwrite(true),
  publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)
)

lazy val scala2Settings = commonSettings ++ Seq(
  libraryDependencies += "org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full,
  scalacOptions ++= Seq(
    "-language:implicitConversions",
    "-language:existentials"
  )
)

lazy val scala3Settings = commonSettings ++ Seq(
  // Scala 3 specific settings can be added here
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "cats-actors",
    Compile / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "src" / "main" / "scala"
    ),
    Test / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "src" / "test" / "scala"
    ),
    // Ensure the artifacts are published with the Scala version in the name
    Compile / packageBin / artifactPath := {
      val artPath = (Compile / packageBin / artifactPath).value
      val scalaVer = scalaBinaryVersion.value match {
        case "2.13" => "2_13"
        case "3" => "3"
        case other => other.replace('.', '_')
      }
      file(s"${artPath.getParent}/cats-actors_${scalaVer}-${version.value}.jar")
    },
    // Add tasks to create source and javadoc JARs
    Compile / packageSrc / artifactPath := {
      val artPath = (Compile / packageSrc / artifactPath).value
      val scalaVer = scalaBinaryVersion.value match {
        case "2.13" => "2_13"
        case "3" => "3"
        case other => other.replace('.', '_')
      }
      file(s"${artPath.getParent}/cats-actors_${scalaVer}-${version.value}-sources.jar")
    },
    Compile / packageDoc / artifactPath := {
      val artPath = (Compile / packageDoc / artifactPath).value
      val scalaVer = scalaBinaryVersion.value match {
        case "2.13" => "2_13"
        case "3" => "3"
        case other => other.replace('.', '_')
      }
      file(s"${artPath.getParent}/cats-actors_${scalaVer}-${version.value}-javadoc.jar")
    }
  )
