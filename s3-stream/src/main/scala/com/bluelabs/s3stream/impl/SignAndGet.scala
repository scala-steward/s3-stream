package com.bluelabs.s3stream.impl

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.bluelabs.akkaaws.{Signer, SigningKeyProvider}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, ResponseEntity}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source

private[s3stream] trait SignAndGet {

  implicit def system: ActorSystem

  implicit def ec: ExecutionContext = system.dispatcher

  protected def signingKey: Future[SigningKeyProvider]

  protected def singleRequest(rq: HttpRequest): Future[HttpResponse]

  def signAndGetResponse(request: HttpRequest): Future[HttpResponse] =
    for {
      signingKey <- signingKey
      req <- Signer.signedRequest(request, signingKey)
      res <- singleRequest(req)
    } yield res

  def signAndGetAs[T](
      request: HttpRequest
  )(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] =
    signAndGet(request).flatMap(entity => Unmarshal(entity).to[T])

  def signAndGet(request: HttpRequest): Future[ResponseEntity] =
    for {
      signingKey <- signingKey
      req <- Signer.signedRequest(request, signingKey)
      res <- singleRequest(req)
      t <- entityForSuccess(res)
    } yield t

  private def entityForSuccess(resp: HttpResponse): Future[ResponseEntity] =
    resp match {
      case HttpResponse(status, _, entity, _) if status.isSuccess() =>
        Future.successful(entity)
      case HttpResponse(status, _, entity, _) => {
        Unmarshal(entity).to[String].flatMap {
          case err =>
            Future.failed(new Exception(err))
        }
      }
    }

  protected def makeCounterSource[T](f: Future[T]): Source[(T, Int), NotUsed] =
    Source
      .future(f)
      .mapConcat { case r => Stream.continually(r) }
      .zip(StreamUtils.counter(1))

}
