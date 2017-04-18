package com.bluelabs.s3stream

import java.time.LocalDate

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.bluelabs.akkaaws.{
  AWSCredentials,
  CredentialScope,
  Signer,
  SigningKey
}

import akka.NotUsed
import akka.actor.{ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.{Attributes, ActorMaterializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import Marshalling._
import scala.util.Try

trait ObjectOperationsSupport extends BasicS3HttpRequests with SignAndGet {

  def s3ObjectOperationFlow: Flow[(S3Location, S3RequestMethod),
                                  (Try[HttpResponse],
                                   (S3Location, S3RequestMethod)),
                                  NotUsed] =
    Flow[(S3Location, S3RequestMethod)]
      .mapAsync(1) {
        case (loc, method) =>
          Signer
            .signedRequest(s3Request(loc, method), signingKey)
            .map(_ -> (loc, method))
      }
      .via(Http().superPool[(S3Location, S3RequestMethod)]())

  def delete(s3Location: S3Location): Future[HttpResponse] =
    signAndGetResponse(deleteRequest(s3Location))
      .flatMap(_.toStrict(30 seconds))

  def get(s3Location: S3Location,
          method: GetObjectRequest = GetObjectRequest.default)
    : Future[HttpResponse] =
    signAndGetResponse(getRequest(s3Location, method))

  def getData(s3Location: S3Location,
              method: GetObjectRequest = GetObjectRequest.default)
    : Source[ByteString, NotUsed] =
    Source
      .fromFuture(get(s3Location, method))
      .map(_.entity.dataBytes)
      .flatMapConcat(identity)

  def getMetadata(s3Location: S3Location,
                  method: HeadObjectRequest = HeadObjectRequest.default)
    : Future[ObjectMetadata] =
    signAndGetResponse(headRequest(s3Location, method)).map(h => {
      h.discardEntityBytes(); ObjectMetadata(h)
    })

  def singleUploadFlow: Flow[
    (S3Location, PutObjectRequest, ByteString),
    (Try[HttpResponse], (S3Location, S3RequestMethod)),
    NotUsed] =
    Flow[(S3Location, PutObjectRequest, ByteString)]
      .mapAsync(1) {
        case (loc, method, payload) =>
          Signer
            .signedRequest(putRequest(loc, method, payload), signingKey)
            .map(_ -> (loc, method))
      }
      .via(Http().superPool[(S3Location, S3RequestMethod)]())

  def put(s3Location: S3Location,
          payload: ByteString,
          method: PutObjectRequest = PutObjectRequest.default)
    : Future[HttpResponse] = {
    val req = putRequest(s3Location, method, payload)

    for {
      signedReq <- Signer.signedRequest(req, signingKey)
      response <- singleRequest(signedReq)
      strict <- response.toStrict(30 seconds)
    } yield {
      strict
    }
  }

}
