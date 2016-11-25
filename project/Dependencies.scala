import sbt._

object Dependencies {
  // Versions
  lazy val akkaVersion = "2.4.14"
  lazy val akkaHttpVersion = "10.0.0"
  lazy val scalatestVersion = "2.2.6"

  val akka = "com.typesafe.akka" %% "akka-actor" % akkaVersion
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % akkaVersion
  val akkaHttp = "com.typesafe.akka" %% "akka-http" % akkaHttpVersion
  val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion
  val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion
  val akkaHttpXML = "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion
  val akkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion


  val scalatest = "org.scalatest" %% "scalatest" % scalatestVersion

  val awsSignatureDeps = Seq(akkaHttp,akkaHttpSprayJson, scalatest % Test, akkaStreamTestkit % Test)

  val s3StreamDeps = Seq( akkaStream, akkaHttp, akkaHttpXML,
    akkaTestkit % Test, akkaStreamTestkit % Test, scalatest % Test)

}
