name := "fs2-blobstore"

inThisBuild(Seq(
  scalaVersion := "2.12.10",
  crossScalaVersions := Seq("2.12.10", "2.13.0"),
  organization := "com.lendup.fs2-blobstore"
))

enablePlugins(DisablePublishingPlugin)

lazy val fs2blobstore = project.in(file("."))
  .settings(moduleName := "root")
  .aggregate(core, s3, sftp, box, gcs)

lazy val core = project

lazy val s3 = project.dependsOn(core % "compile->compile;test->test")

lazy val sftp = project.dependsOn(core % "compile->compile;test->test")

lazy val box = project.dependsOn(core % "compile->compile;test->test")

lazy val gcs = project.dependsOn(core % "compile->compile;test->test")
