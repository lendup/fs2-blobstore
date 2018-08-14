name := "fs2-blobstore"

inThisBuild(Seq(
  scalaVersion := "2.12.6",
  organization := "com.lendup.fs2-blobstore"
))

enablePlugins(DisablePublishingPlugin)

lazy val fs2blobstore = project.in(file("."))
  .settings(moduleName := "root")
  .aggregate(core, s3, sftp, box)

lazy val core = project

lazy val s3 = project.dependsOn(core % "compile->compile;test->test")

lazy val sftp = project.dependsOn(core % "compile->compile;test->test")

lazy val box = project.dependsOn(core % "compile->compile;test->test")
