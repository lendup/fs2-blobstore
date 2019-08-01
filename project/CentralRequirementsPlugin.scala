import sbt._, Keys._

object CentralRequirementsPlugin extends AutoPlugin {
  // tells sbt to automatically enable this plugin where ever
  // the sbt-rig plugin is enabled (which should be all sub-modules)
  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    // this tells sonatype what profile to use
    // (usually this is what you registered when you signed up
    // for maven central release via their OSS JIRA ticket process)
    // inform central who was explicitly involved in developing
    // this project. Note that this is *required* by central.
    developers ++= List(
      Developer("rolandomanrique", "Rolando Manrique", "", url("http://github.com/rolandomanrique")),
      Developer("stew", "Stew O'Connor", "", url("https://github.com/stew"))
    ),
    // what license are you releasing this under?
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    // where can users find information about this project?
    homepage := Some(url("https://github.com/lendup/fs2-blobstore")),
    // show users where the source code is located
    scmInfo := Some(ScmInfo(url("https://github.com/lendup/fs2-blobstore"), "git@github.com:lendup/fs2-blobstore.git")),
    startYear := Some(2018)
  )
}