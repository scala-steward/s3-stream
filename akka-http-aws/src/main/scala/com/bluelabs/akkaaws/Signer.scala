package com.bluelabs.akkaaws

import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import akka.stream.Materializer
import akka.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}

object Signer {
  private val dateFormatter = DateTimeFormatter.ofPattern("YYYYMMdd'T'HHmmssX")

  def signedRequest(request: HttpRequest,
                    keyProvider: SigningKeyProvider,
                    date: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC))(
      implicit as: ActorSystem,
      mat: Materializer): Future[HttpRequest] = {
    implicit val ec: ExecutionContext = mat.executionContext
    keyProvider.signingKey.flatMap { key =>
      val hashedBody =
        request.entity.dataBytes.runWith(impl.StreamUtils.digest()).map {
          case hash =>
            impl.Utils.encodeHex(hash.toArray)
        }

      hashedBody.map {
        case hb =>
          val headersToAdd = Seq(
            RawHeader("x-amz-date", date.format(dateFormatter)),
            RawHeader("x-amz-content-sha256", hb)) ++ sessionHeader(
            key.credentials)
          val reqWithHeaders =
            request.withHeaders(request.headers ++ headersToAdd)
          val cr = impl.CanonicalRequest.from(reqWithHeaders)
          val authHeader =
            authorizationHeader("AWS4-HMAC-SHA256", key, date, cr)
          reqWithHeaders.withHeaders(reqWithHeaders.headers ++ Seq(authHeader))
      }

    }
  }

  private def sessionHeader(creds: impl.AWSCredentials): Option[HttpHeader] = {
    creds.maySessionToken match {
      case None => None
      case Some(sessionToken) =>
        Some(RawHeader("X-Amz-Security-Token", sessionToken))
    }
  }

  private def authorizationHeader(
      algorithm: String,
      key: impl.SigningKey,
      requestDate: ZonedDateTime,
      canonicalRequest: impl.CanonicalRequest): HttpHeader = {
    RawHeader(
      "Authorization",
      authorizationString(algorithm, key, requestDate, canonicalRequest))
  }

  private def authorizationString(
      algorithm: String,
      key: impl.SigningKey,
      requestDate: ZonedDateTime,
      canonicalRequest: impl.CanonicalRequest): String = {
    s"$algorithm Credential=${key.credentialString}, SignedHeaders=${canonicalRequest.signedHeaders}, Signature=${key.hexEncodedSignature(
      stringToSign(algorithm, key, requestDate, canonicalRequest).getBytes())}"
  }

  private def stringToSign(algorithm: String,
                           signingKey: impl.SigningKey,
                           requestDate: ZonedDateTime,
                           canonicalRequest: impl.CanonicalRequest): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashedRequest = impl.Utils.encodeHex(
      digest.digest(canonicalRequest.canonicalString.getBytes()))
    s"$algorithm\n${requestDate.format(dateFormatter)}\n${signingKey.scope.scopeString}\n$hashedRequest"
  }

}
