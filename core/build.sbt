name := "core"

val fs2Version = "1.1.0-M1"

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "co.fs2" %% "fs2-io" % fs2Version,
  "org.scalatest" %% "scalatest" % "3.0.8" % "test",
  "org.typelevel" %% "cats-effect-laws" % "2.0.0-M4" % "test"
)

scalacOptions ++= (
  if (scalaVersion.value != "2.13.0" )
    {
      "-Ypartial-unification" :: Nil
    }
  else
    Nil
  )
