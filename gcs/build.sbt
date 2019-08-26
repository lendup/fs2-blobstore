name := "gcs"

libraryDependencies ++= Seq(
  "com.google.cloud" % "google-cloud-storage" % "1.87.0",
  "com.google.cloud" % "google-cloud-nio" % "0.105.0-alpha" % Test
)
