name := "core"

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % "0.10.4",
  "co.fs2" %% "fs2-io" % "0.10.4",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test"
)

scalacOptions += "-Ypartial-unification"
