inThisBuild(Seq(
  scalaVersion := "2.12.10",
  crossScalaVersions := Seq("2.12.10", "2.13.0"),
  organization := "com.github.fs2-blobstore",
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer("rolandomanrique", "Rolando Manrique", "", url("http://github.com/rolandomanrique")),
    Developer("stew", "Stew O'Connor", "", url("https://github.com/stew")),
    Developer("gafiatulin", "Victor Gafiatulin", "", url("https://github.com/gafiatulin")),
    Developer("jgogstad", "Jostein Gogstad", "", url("https://github.com/jgogstad"))
  ),
  homepage := Some(url("https://github.com/fs2-blobstore/fs2-blobstore")),
  startYear := Some(2018)
)
)

lazy val fs2blobstore = project.in(file("."))
  .settings(
    moduleName := "root",
    skip in publish := true
  )
  .aggregate(core, s3, sftp, box, gcs)

lazy val core = project

lazy val s3 = project.dependsOn(core % "compile->compile;test->test")

lazy val sftp = project.dependsOn(core % "compile->compile;test->test")

lazy val box = project.dependsOn(core % "compile->compile;test->test")

lazy val gcs = project.dependsOn(core % "compile->compile;test->test")
