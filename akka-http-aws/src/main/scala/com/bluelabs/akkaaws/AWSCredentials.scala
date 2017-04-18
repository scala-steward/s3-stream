package com.bluelabs.akkaaws

import scala.concurrent._
import scala.concurrent.duration._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.util.ByteString
import akka.http.scaladsl.Http
import akka.actor._
import akka.stream._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._

sealed trait AWSCredentials {
  def accessKeyId: String
  def secretAccessKey: String
}

case class BasicCredentials(accessKeyId: String, secretAccessKey: String)
    extends AWSCredentials
case class AWSSessionCredentials(accessKeyId: String,
                                 secretAccessKey: String,
                                 sessionToken: String)
    extends AWSCredentials

object AWSCredentials {
  def apply(accessKeyId: String, secretAccessKey: String): BasicCredentials = {
    BasicCredentials(accessKeyId, secretAccessKey)
  }

  private def parseAWSCredentialsFile: Option[AWSCredentials] = {
    val file = System.getProperty("user.home") + "/.aws/credentials"
    if (new java.io.File(file).canRead) {
      val source = scala.io.Source.fromFile(file)
      val content = source.getLines.toVector
      source.close
      val default = content.dropWhile(line => line.trim != "[default]")
      val ac = default
        .find(_.trim.startsWith("aws_access_key_id"))
        .map(_.split("=")(1).trim)
      val sk = default
        .find(_.trim.startsWith("aws_secret_access_key"))
        .map(_.split("=")(1).trim)
      ac.flatMap { ac =>
        sk.map { sk =>
          BasicCredentials(ac, sk)
        }
      }

    } else None
  }

  def default(implicit as: ActorSystem,
              mat: ActorMaterializer): Future[Option[AWSCredentials]] = {

    val opt = {
      val access = System.getenv("AWS_ACCESS_KEY_ID")
      val secret = System.getenv("AWS_SECRET_ACCESS_KEY")
      if (access != null && secret != null)
        Some(BasicCredentials(access, secret))
      else None
    }.orElse {
      val access = System.getenv("AWS_ACCESS_KEY")
      val secret = System.getenv("AWS_SECRET_KEY")
      if (access != null && secret != null)
        Some(BasicCredentials(access, secret))
      else None
    }.orElse {
      val access = System.getProperty("aws.accessKeyId")
      val secret = System.getProperty("aws.secretKey")
      if (access != null && secret != null)
        Some(BasicCredentials(access, secret))
      else None
    }.orElse(parseAWSCredentialsFile)

    if (opt.isDefined) Future.successful(opt)
    else {
      import as.dispatcher
      val address = "169.254.169.254"

      val request1 = HttpRequest(HttpMethods.GET)
        .withHeaders(Host(address))
        .withUri(
          Uri()
            .withHost(address)
            .withScheme("http")
            .withPath(Uri.Path("/latest/meta-data/iam/security-credentials/")))

      Http()
        .singleRequest(request1)
        .flatMap(r => Unmarshal(r.entity).to[String])
        .flatMap { line =>
          val request2 = HttpRequest(HttpMethods.GET)
            .withHeaders(Host(address))
            .withUri(
              Uri()
                .withHost(address)
                .withScheme("http")
                .withPath(Uri.Path(
                  "/latest/meta-data/iam/security-credentials/" + line)))

          Http()
            .singleRequest(request2)
            .flatMap(r => Unmarshal(r.entity).to[JsValue])
            .map { js =>
              val fields = js.asJsObject.fields
              val ac = fields("AccessKeyId").asInstanceOf[JsString].value
              val sk = fields("SecretAccessKey").asInstanceOf[JsString].value
              val token = fields("Token").asInstanceOf[JsString].value
              Some(AWSSessionCredentials(ac, sk, token))
            }
        } recover {
        case e => None
      }
    }

  }

}
