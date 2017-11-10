val akkaVersion = "2.5.6"
val akkaHttpVersion = "10.0.10"
val scalatestVersion = "3.0.0"
val akka = "com.typesafe.akka" %% "akka-actor" % akkaVersion
val akkaStream = "com.typesafe.akka" %% "akka-stream" % akkaVersion
val akkaHttp = "com.typesafe.akka" %% "akka-http" % akkaHttpVersion
val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion
val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion
val akkaHttpXML = "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion
val akkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion
val scalatest = "org.scalatest" %% "scalatest" % scalatestVersion

lazy val commonSettings = Seq(
  organization := "io.github.pityka",
  version := "0.0.3-SNAPSHOT",
  scalaVersion := "2.12.4",
  crossScalaVersions := Seq("2.12.4", "2.11.11"),
  scalacOptions ++= Seq("-unchecked",
                        "-deprecation",
                        "-feature",
                        "-Ywarn-unused-import",
                        "-Xlint",
                        "-encoding",
                        "UTF-8",
                        "-Xfatal-warnings",
                        "-language:postfixOps"),
  licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0")),
  pomExtra in Global := {
    <url>https://pityka.github.io/s3-stream/</url>
      <scm>
        <connection>scm:git:github.com/pityka/s3-stream</connection>
        <developerConnection>scm:git:git@github.com:pityka/s3-stream</developerConnection>
        <url>github.com/pityka/s3-stream</url>
      </scm>
      <developers>
        <developer>
          <id>pityka</id>
          <name>Istvan Bartha</name>
          <url>https://pityka.github.io/s3-stream/</url>
        </developer>
      </developers>
  }
)

scalafmtOnCompile in ThisBuild := true

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false)
  .aggregate(s3stream, awsRequests)

lazy val awsRequests = (project in file("akka-http-aws"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-http-aws-fork",
    libraryDependencies ++= Seq(
      "io.github.pityka" %% "akka-http-unboundedqueue" % "1.0.0",
      akkaHttp,
      akkaHttpSprayJson,
      scalatest % Test,
      akkaStreamTestkit % Test)
  )

lazy val s3stream = (project in file("s3-stream"))
  .settings(commonSettings: _*)
  .settings(
    name := "s3-stream-fork",
    libraryDependencies ++= Seq(
      akkaStream,
      akkaHttp,
      akkaHttpXML,
      "io.github.pityka" %% "akka-http-unboundedqueue" % "1.0.0",
      "org.scalaj" %% "scalaj-http" % "2.3.0",
      akkaTestkit % Test,
      akkaStreamTestkit % Test,
      scalatest % Test
    )
  )
  .dependsOn(awsRequests)
