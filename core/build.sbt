name := "core"

val fs2Version = "1.0.0"

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "co.fs2" %% "fs2-io" % fs2Version,
  "org.scalatest" %% "scalatest" % "3.0.4" % "test",
  "org.typelevel" %% "cats-effect-laws" % "1.0.0" % "test"
)

scalacOptions += "-Ypartial-unification"
