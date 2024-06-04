ThisBuild / version := "1.0.0"

ThisBuild / scalaVersion := "2.13.12"

addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)

ThisBuild / organization := "com.suprnation"
ThisBuild / name := "cats-actors"

organizationName := "SuprNation"
startYear := Some(2024)
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.txt"))

Test / parallelExecution := false

scalacOptions ++= Seq( // use ++= to add to existing options
  "-deprecation",
  "-encoding",
  "utf8", // if an option takes an arg, supply it on the same line
  "-feature", // then put the next option on a new line for easy editing
  "-language:implicitConversions",
  "-language:existentials",
  "-unchecked",
  "-Werror",
  "-Xlint" // exploit "trailing comma" syntax so you can add an option without editing this line
)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.5.2", // if needed for other dependencies
  "org.typelevel" %% "cats-effect" % "3.5.0",
  "co.fs2" %% "fs2-core" % "3.10.2",
  "co.fs2" %% "fs2-io" % "3.10.2",
  "com.typesafe" % "config" % "1.4.3",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)

publishTo := {
  val owner = "cloudmark"
  val repo = "cats-actors"
  if (isSnapshot.value)
    Some("GitHub Package Registry" at s"https://maven.pkg.github.com/$owner/$repo")
  else
    Some("GitHub Package Registry" at s"https://maven.pkg.github.com/$owner/$repo")
}
