name := "gcs"

libraryDependencies ++= Seq(
  "com.google.cloud" % "google-cloud-storage" % "1.98.0",
  "com.google.cloud" % "google-cloud-nio" % "0.116.0-alpha" % Test
)
