import Dependencies._
import sbt.Keys._

lazy val commonSettings = Seq(
  organization := "com.bluelabs",
  version := "0.1",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
)

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  aggregate(s3stream)

lazy val awsRequests = (project in file("akka-http-aws")).
  settings(commonSettings: _*).
  settings(
    name := "akka-http-aws",
    libraryDependencies ++= awsSignatureDeps
  )

lazy val s3stream = (project in file("s3-stream")).
  settings(commonSettings: _*).
  settings(
    name := "s3-stream",
    libraryDependencies ++= s3StreamDeps
  ).dependsOn(awsRequests)