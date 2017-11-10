package com.bluelabs.s3stream

import com.bluelabs.akkaaws.{SigningKeyProvider}
import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}

trait S3ClientSupport
    extends impl.ObjectOperationsSupport
    with impl.MultipartUploadSupport
    with impl.ParallelDownloadSupport

trait QueuedRequest {
  implicit def system: ActorSystem
  implicit def mat: Materializer
  def singleRequest(rq: HttpRequest) = httpqueue.HttpQueue(system).queue(rq)
}

trait DefaultKey {
  implicit def system: ActorSystem
  implicit def mat: Materializer
  def region: String
  def signingKey = SigningKeyProvider.default(region)
}

trait StaticKey {
  implicit def system: ActorSystem
  implicit def mat: Materializer
  def region: String
  def accessKeyId: String
  def secretAccessKey: String

  def signingKey =
    Future.successful(
      SigningKeyProvider.static(accessKeyId, secretAccessKey, region))
}

class S3ClientQueued(val region: String)(implicit val system: ActorSystem,
                                         val mat: ActorMaterializer)
    extends S3ClientSupport
    with QueuedRequest
    with DefaultKey

class S3ClientQueuedStatic(val region: String,
                           val accessKeyId: String,
                           val secretAccessKey: String)(
    implicit val system: ActorSystem,
    val mat: ActorMaterializer)
    extends S3ClientSupport
    with QueuedRequest
    with StaticKey
