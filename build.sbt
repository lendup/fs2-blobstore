name := "fs2-blobstore"

inThisBuild(Seq(
  scalaVersion := "2.13.0",
  crossScalaVersions := Seq("2.11.12", "2.12.8", "2.13.0"),
  organization := "com.lendup.fs2-blobstore"
))

lazy val fs2blobstore = project.in(file("."))
  .settings(moduleName := "root")
  .aggregate(core, s3, sftp, box)

lazy val core = project

lazy val s3 = project.dependsOn(core % "compile->compile;test->test")

lazy val sftp = project.dependsOn(core % "compile->compile;test->test")

lazy val box = project.dependsOn(core % "compile->compile;test->test")
