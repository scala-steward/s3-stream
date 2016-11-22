import Dependencies._
import sbt.Keys._

lazy val commonSettings = Seq(
  organization := "io.github.pityka",
  version := "0.0.4-fork1",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
)


lazy val root = (project in file(".")).
  aggregate(s3stream, awsRequests)

lazy val awsRequests = (project in file("akka-http-aws")).
  settings(commonSettings: _*).
  settings(
    name := "akka-http-aws-fork",
    libraryDependencies ++= awsSignatureDeps
  )

lazy val s3stream = (project in file("s3-stream")).
  settings(commonSettings: _*).
  settings(
    name := "s3-stream-fork",
    libraryDependencies ++= s3StreamDeps
  ).dependsOn(awsRequests)
