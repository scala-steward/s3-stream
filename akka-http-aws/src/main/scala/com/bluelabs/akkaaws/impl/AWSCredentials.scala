package com.bluelabs.akkaaws.impl

import scala.concurrent._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.model.headers.{Host}
import akka.actor._
import akka.stream._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import java.time._
import java.util.concurrent.atomic.AtomicReference

private[akkaaws] trait CredentialProvider {
  def credentials(
      implicit as: ActorSystem,
      mat: Materializer
  ): Future[AWSCredentials]
}

private[akkaaws] object CredentialProvider {
  def default(
      implicit as: ActorSystem,
      mat: Materializer
  ): Future[CredentialProvider] =
    CredentialImpl.defaultCredentialProviderChain.map {
      case Some(cred) => new CredentialImpl.CredentialProviderImpl(cred)
      case None       => throw new RuntimeException("No credential found")
    }(as.dispatcher)

  def static(accessKeyId: String, secretAccessKey: String) =
    new CredentialProvider {
      def credentials(
          implicit as: ActorSystem,
          mat: Materializer
      ): Future[AWSCredentials] =
        Future.successful(
          CredentialImpl.BasicCredentials(accessKeyId, secretAccessKey)
        )
    }

}

private[akkaaws] sealed trait AWSCredentials {
  def accessKeyId: String
  def secretAccessKey: String
  def maySessionToken: Option[String]
}

private object CredentialImpl {

  sealed trait AWSCredentialsWithRefresh extends AWSCredentials {
    def accessKeyId: String
    def secretAccessKey: String

    def refresh(
        implicit as: ActorSystem,
        am: Materializer
    ): Future[(AWSCredentialsWithRefresh, Boolean)]
  }

  class CredentialProviderImpl(p: AWSCredentialsWithRefresh)
      extends CredentialProvider {
    private[this] val currentCredentials = new AtomicReference(p)

    private def rotate(c: AWSCredentialsWithRefresh) = {
      currentCredentials.compareAndSet(currentCredentials.get, c)
    }

    def credentials(implicit as: ActorSystem, mat: Materializer) =
      currentCredentials.get.refresh.map {
        case (credentials, updated) =>
          if (updated) { this.rotate(credentials) }
          credentials
      }(mat.executionContext)
  }

  case class BasicCredentials(accessKeyId: String, secretAccessKey: String)
      extends AWSCredentialsWithRefresh {
    def refresh(implicit as: ActorSystem, am: Materializer) =
      Future.successful(this -> false)
    def maySessionToken = None
  }

  case class SessionCredentials(
      accessKeyId: String,
      secretAccessKey: String,
      sessionToken: String,
      expiration: Instant
  ) extends AWSCredentialsWithRefresh {

    def maySessionToken = Some(sessionToken)

    def refresh(implicit as: ActorSystem, am: Materializer) = {
      import as.dispatcher
      val log = akka.event.Logging(as.eventStream, "awscredentials")
      if (java.time.Instant.now.isAfter(expiration.minusSeconds(60 * 5))) {
        log.debug(
          "Refreshing session credentials because they will expire in 5 minutes."
        )
        fetchFromMetadata.map(_.get -> true)
      } else Future.successful(this -> false)
    }
  }

  private def parseAWSCredentialsFile = {
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
      ac.flatMap { ac => sk.map { sk => BasicCredentials(ac, sk) } }

    } else None
  }

  def defaultCredentialProviderChain(
      implicit as: ActorSystem,
      mat: Materializer
  ) = {

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
      }
      .orElse {
        val access = System.getProperty("aws.accessKeyId")
        val secret = System.getProperty("aws.secretKey")
        if (access != null && secret != null)
          Some(BasicCredentials(access, secret))
        else None
      }
      .orElse(parseAWSCredentialsFile)

    if (opt.isDefined) Future.successful(opt)
    else fetchFromMetadata

  }

  def fetchFromMetadata(implicit as: ActorSystem, mat: Materializer) = {
    import as.dispatcher
    val address = "169.254.169.254"
    val log = akka.event.Logging(as.eventStream, "aws-metadata")
    val request1 = HttpRequest(HttpMethods.GET)
      .withHeaders(Host(address))
      .withUri(
        Uri()
          .withHost(address)
          .withScheme("http")
          .withPath(Uri.Path("/latest/meta-data/iam/security-credentials/"))
      )

    httpqueue
      .HttpQueue(as)
      .queue(request1)
      .flatMap(r => Unmarshal(r.entity).to[String])
      .flatMap { line =>
        val request2 = HttpRequest(HttpMethods.GET)
          .withHeaders(Host(address))
          .withUri(
            Uri()
              .withHost(address)
              .withScheme("http")
              .withPath(
                Uri.Path("/latest/meta-data/iam/security-credentials/" + line)
              )
          )

        httpqueue
          .HttpQueue(as)
          .queue(request2)
          .flatMap(r =>
            Unmarshal(r.entity.withContentType(ContentTypes.`application/json`))
              .to[JsValue]
          )
          .map { js =>
            val fields = js.asJsObject.fields
            val ac = fields("AccessKeyId").asInstanceOf[JsString].value
            val sk = fields("SecretAccessKey").asInstanceOf[JsString].value
            val token = fields("Token").asInstanceOf[JsString].value
            val expirationString =
              fields("Expiration").asInstanceOf[JsString].value
            Some(
              SessionCredentials(
                ac,
                sk,
                token,
                ZonedDateTime.parse(expirationString).toInstant
              )
            )
          }
      } recover {
      case e =>
        log.error(e, "metadata fetch fail")
        None
    }
  }

}
