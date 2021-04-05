package com.bluelabs.s3stream.impl

import scala.concurrent.Future
import scala.concurrent.duration._

import com.bluelabs.akkaaws.{Signer}

import akka.NotUsed
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, HttpHeader}
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import scala.util.Try
import com.bluelabs.s3stream.{
  S3Location,
  S3RequestMethod,
  GetObjectRequest,
  ObjectMetadata,
  HeadObjectRequest,
  PutObjectRequest
}

private[s3stream] trait ObjectOperationsSupport
    extends BasicS3HttpRequests
    with SignAndGet {

  def s3ObjectOperationFlow: Flow[
    (S3Location, S3RequestMethod),
    (Try[HttpResponse], (S3Location, S3RequestMethod)),
    NotUsed
  ] =
    Flow[(S3Location, S3RequestMethod)]
      .mapAsync(1) { case (loc, method) =>
        signingKey.flatMap(signingKey =>
          Signer
            .signedRequest(s3Request(loc, method), signingKey)
            .map(r => (r, (loc, method)))
        )
      }
      .via(Http().superPool[(S3Location, S3RequestMethod)]())

  def delete(s3Location: S3Location): Future[HttpResponse] =
    signAndGetResponse(deleteRequest(s3Location))
      .flatMap(_.toStrict(30 seconds))

  def get(
      s3Location: S3Location,
      method: GetObjectRequest = GetObjectRequest.default
  ): Future[HttpResponse] =
    signAndGetResponse(getRequest(s3Location, method))

  def getDataOnce(
      s3Location: S3Location,
      method: GetObjectRequest = GetObjectRequest.default
  ): Source[ByteString, NotUsed] =
    StreamUtils
      .singleLazyAsync(get(s3Location, method))
      .map(_.entity.dataBytes)
      .flatMapConcat(identity)

  def getMetadata(
      s3Location: S3Location,
      method: HeadObjectRequest = HeadObjectRequest.default
  ): Future[ObjectMetadata] =
    signingKey
      .flatMap(signingKey =>
        Signer.signedRequest(headRequest(s3Location, method), signingKey)
      )
      .map { request =>
        val headers = request.headers.map(h => h.name -> h.value)
        val response = scalaj.http
          .Http(request.uri.toString)
          .method("HEAD")
          .headers(headers)
          .asString
        val status = response.code
        val akkaResponseHeaders =
          response.headers.flatMap { case (name, values) =>
            values.flatMap { value =>
              HttpHeader.parse(name, value) match {
                case HttpHeader.ParsingResult.Ok(h, _) => List(h)
                case _                                 => Nil
              }
            }
          }.toList
        ObjectMetadata(
          HttpResponse(status = status, headers = akkaResponseHeaders)
        )
      }

  def singleUploadFlow: Flow[
    (S3Location, PutObjectRequest, ByteString),
    (Try[HttpResponse], (S3Location, S3RequestMethod)),
    NotUsed
  ] =
    Flow[(S3Location, PutObjectRequest, ByteString)]
      .mapAsync(1) { case (loc, method, payload) =>
        signingKey
          .flatMap(signingKey =>
            Signer
              .signedRequest(putRequest(loc, method, payload), signingKey)
          )
          .map(r => (r, (loc, method)))
      }
      .via(Http().superPool[(S3Location, S3RequestMethod)]())

  def put(
      s3Location: S3Location,
      payload: ByteString,
      method: PutObjectRequest = PutObjectRequest.default
  ): Future[HttpResponse] = {
    val req = putRequest(s3Location, method, payload)

    for {
      signingKey <- signingKey
      signedReq <- Signer.signedRequest(req, signingKey)
      response <- singleRequest(signedReq)
      strict <- response.toStrict(30 seconds)
    } yield {
      strict
    }
  }

}
