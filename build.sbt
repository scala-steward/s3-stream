inThisBuild(
  List(
    organization := "io.github.pityka",
    homepage := Some(url("https://pityka.github.io/s3-stream/")),
    licenses := List(
      ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))
    ),
    developers := List(
      Developer(
        "pityka",
        "Istvan Bartha",
        "bartha.pityu@gmail.com",
        url("https://github.com/pityka/s3-stream")
      )
    )
  )
)

val akkaVersion = "2.6.16"
val akkaHttpVersion = "10.2.9"
val scalatestVersion = "3.2.10"
val scodecVersion = "1.1.34"
val scodec = "org.scodec" %% "scodec-bits" % scodecVersion
val akka = "com.typesafe.akka" %% "akka-actor" % akkaVersion % Provided
val akkaStream = "com.typesafe.akka" %% "akka-stream" % akkaVersion  % Provided
val akkaHttp = "com.typesafe.akka" %% "akka-http" % akkaHttpVersion % Provided
val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion
val akkaStreamTestkit =
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion
val akkaHttpXML = "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion % Provided
val akkaHttpSprayJson =
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion % Provided
val scalatest = "org.scalatest" %% "scalatest" % scalatestVersion

lazy val commonSettings = Seq(
  organization := "io.github.pityka",
  scalaVersion := "2.13.8",
  crossScalaVersions := Seq("2.12.16", "2.13.8"),
  scalacOptions ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-encoding",
    "UTF-8",
    "-Xfatal-warnings",
    "-language:postfixOps"
  )
)

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false, crossScalaVersions := Nil)
  .aggregate(s3stream, awsRequests)

lazy val awsRequests = (project in file("akka-http-aws"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-http-aws-fork",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.5.0",
      akkaHttp,
      scodec,
      akkaStream,
      akkaHttpSprayJson,
      scalatest % Test,
      akkaStreamTestkit % Test
    )
  )

lazy val s3stream = (project in file("s3-stream"))
  .settings(commonSettings: _*)
  .settings(
    name := "s3-stream-fork",
    libraryDependencies ++= Seq(
      akkaStream,
      akkaHttp,
      akkaHttpXML,
      "org.scalaj" %% "scalaj-http" % "2.4.2",
      akkaTestkit % Test,
      akkaStreamTestkit % Test,
      scalatest % Test
    )
  )
  .dependsOn(awsRequests)
