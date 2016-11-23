package com.bluelabs.s3stream

import java.time.LocalDate

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{ Failure, Success }

import com.bluelabs.akkaaws.{AWSCredentials, CredentialScope, Signer, SigningKey}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.{Attributes, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString

case class S3Location(bucket: String, key: String)

case class MultipartUpload(s3Location: S3Location, uploadId: String)

sealed trait UploadPartResponse {
  def multipartUpload: MultipartUpload
  def index: Int
}

case class SuccessfulUploadPart(multipartUpload: MultipartUpload, index: Int, etag: String) extends UploadPartResponse

case class FailedUploadPart(multipartUpload: MultipartUpload, index: Int, exception: Throwable) extends UploadPartResponse

case class FailedUpload(reasons: Seq[Throwable]) extends Exception
case class CompleteMultipartUploadResult(location: Uri, bucket: String, key: String, etag: String)

class S3Stream(credentials: AWSCredentials, region: String = "us-east-1")(implicit system: ActorSystem, mat: Materializer) {
  import Marshalling._

  val MIN_CHUNK_SIZE = 5242880
  val signingKey = SigningKey(credentials, CredentialScope(LocalDate.now(), region, "s3"))

  def download(s3Location: S3Location): Source[ByteString, NotUsed] = {
    import mat.executionContext
    Source.fromFuture(signAndGet(HttpRequests.getRequest(s3Location)).map(_.dataBytes))
          .flatMapConcat(identity)
  }

  def getMetadata(s3Location: S3Location): Future[HttpResponse] =
    signAndGetResponse(HttpRequests.headRequest(s3Location))


  def upoadData(s3Location:S3Location, payload: ByteString, serverSideEncryption: Boolean) : Future[HttpResponse] = {
    import mat.executionContext
    val req = HttpRequests.putRequest(s3Location,serverSideEncryption,payload)

    for {
      signedReq <- Signer.signedRequest(req, signingKey)
      response <- Http().singleRequest(signedReq)
    } yield {
      response
    }
  }


  /**
    * Uploades a stream of ByteStrings to a specified location as a multipart upload.
    *
    * @param s3Location
    * @param chunkSize
    * @param chunkingParallelism
    * @return
    */
  def multipartUpload(s3Location: S3Location, chunkSize: Int = MIN_CHUNK_SIZE, chunkingParallelism: Int = 4,serverSideEncryption:Boolean = false): Sink[ByteString, Future[Option[CompleteMultipartUploadResult]]] = {
    import mat.executionContext

    val (flow,mp) = chunkAndRequest(s3Location, chunkSize,serverSideEncryption)(chunkingParallelism)

      flow.log("s3-upload-response").withAttributes(Attributes.logLevels(onElement = Logging.DebugLevel, onFailure = Logging.WarningLevel, onFinish = Logging.InfoLevel))
      .toMat(completionSink(s3Location,mp))(Keep.right)
  }

  def initiateMultipartUpload(s3Location: S3Location,serverSideEncryption:Boolean): Future[MultipartUpload] = {
    import mat.executionContext

    val req = HttpRequests.initiateMultipartUploadRequest(s3Location,serverSideEncryption)
    val response = for {
      signedReq <- Signer.signedRequest(req, signingKey)
      response <- Http().singleRequest(signedReq)
    } yield {
      response
    }
    response.flatMap {
      case HttpResponse(status, _, entity, _) if status.isSuccess() => Unmarshal(entity).to[MultipartUpload]
      case HttpResponse(status, _, entity, _) => {
        Unmarshal(entity).to[String].flatMap { case err =>
          Future.failed(new Exception(err))
        }
      }
    }
  }

  def completeMultipartUpload(s3Location: S3Location, parts: Seq[SuccessfulUploadPart]): Future[CompleteMultipartUploadResult] = {
    import mat.executionContext

    for (
        req <- HttpRequests.completeMultipartUploadRequest(parts.head.multipartUpload, parts.map { case p => (p.index, p.etag) });
        res <- signAndGetAs[CompleteMultipartUploadResult](req)
    ) yield res
  }

  /**
    * Initiates a multipart upload. Returns a source of the initiated upload with upload part indicess
    *
    * @param s3Location The s3 location to which to upload to
    * @return
    */
  def initiateUpload(s3Location: S3Location,serverSideEncryption:Boolean): (Source[(MultipartUpload, Int), NotUsed],Future[MultipartUpload]) = {
    val mp = initiateMultipartUpload(s3Location,serverSideEncryption)
    (Source.fromFuture(mp)
      .mapConcat{case r => Stream.continually(r)}
      .zip(StreamUtils.counter(1)),mp)
  }

  /**
    * Transforms a flow of ByteStrings into a flow of HTTPRequests to upload to S3.
    *
    * @param s3Location
    * @param chunkSize
    * @param parallelism
    * @return
    */
  def createRequests(s3Location: S3Location, chunkSize: Int = MIN_CHUNK_SIZE, parallelism: Int = 4, serverSideEncryption: Boolean = false): (Flow[ByteString, (HttpRequest, (MultipartUpload, Int)), NotUsed],Future[MultipartUpload]) = {
    assert(chunkSize >= MIN_CHUNK_SIZE, "Chunk size must be at least 5242880B. See http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadUploadPart.html")
    val (requestInfo,multipartupload) = initiateUpload(s3Location,serverSideEncryption)
    (Flow[ByteString]
      .via(new Chunker(chunkSize))
      .zipWith(requestInfo){case (payload, (uploadInfo, chunkIndex)) => (HttpRequests.uploadPartRequest(uploadInfo, chunkIndex, payload), (uploadInfo, chunkIndex))}
      .mapAsync(parallelism){case (req, info) => Signer.signedRequest(req, signingKey).zip(Future.successful(info)) },multipartupload)
  }

  def chunkAndRequest(s3Location: S3Location, chunkSize: Int = MIN_CHUNK_SIZE, serverSideEncryption: Boolean=false)(parallelism: Int = 4): (Flow[ByteString, UploadPartResponse, NotUsed],Future[MultipartUpload]) = {
    val (flow,mp) = createRequests(s3Location, chunkSize, parallelism,serverSideEncryption)

      (flow.via(Http().superPool[(MultipartUpload, Int)]())
        .map {
          case (Success(r), (upload, index)) => {
            r.entity.dataBytes.runWith(Sink.ignore)
            val etag = r.headers.find(_.lowercaseName() == "etag").map(_.value())
            etag.map((t) => SuccessfulUploadPart(upload, index, t)).getOrElse(FailedUploadPart(upload, index, new RuntimeException("Cannot find etag")))
          }
          case (Failure(e), (upload, index)) => FailedUploadPart(upload, index, e)
        },mp)
  }

  def completionSink(s3Location: S3Location,mp: Future[MultipartUpload]): Sink[UploadPartResponse, Future[Option[CompleteMultipartUploadResult]]] = {
    import mat.executionContext

    Sink.seq[UploadPartResponse].mapMaterializedValue { case responseFuture: Future[Seq[UploadPartResponse]] =>
      responseFuture.flatMap { case responses: Seq[UploadPartResponse] =>
        val successes = responses.collect { case r: SuccessfulUploadPart => r }
        val failures = responses.collect { case r: FailedUploadPart => r }
        if(responses.isEmpty) {
          Future.failed(new RuntimeException("No Responses"))
        } else if(failures.isEmpty) {
          Future.successful(successes.sortBy(_.index))
        } else {
          Future.failed(FailedUpload(failures.map(_.exception)))
        }
      }.flatMap(completeMultipartUpload(s3Location, _).map(x => Some(x)))
      .recoverWith{
        case e => mp.flatMap{ case MultipartUpload(_,id) =>
          val req = HttpRequests.abortMultipartUploadRequest(s3Location,id)
          signAndGet(req).map(_ => None)
        }
      }
    }
  }

  private def signAndGetResponse(request: HttpRequest): Future[HttpResponse] = {
    import mat.executionContext
    for (
        req <- Signer.signedRequest(request, signingKey);
        res <- Http().singleRequest(req)

    ) yield res
  }

  private def signAndGetAs[T](request: HttpRequest)(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] = {
    import mat.executionContext
    signAndGet(request).flatMap(entity => Unmarshal(entity).to[T])
  }

  private def signAndGet(request: HttpRequest): Future[ResponseEntity] = {
    import mat.executionContext
    for (
        req <- Signer.signedRequest(request, signingKey);
        res <- Http().singleRequest(req);
        t <- entityForSuccess(res)
    ) yield t
  }

  private def entityForSuccess(resp: HttpResponse)(implicit ctx: ExecutionContext): Future[ResponseEntity] = {
    resp match {
      case HttpResponse(status, _, entity, _) if status.isSuccess() => Future.successful(entity)
      case HttpResponse(status, _, entity, _) => {
        Unmarshal(entity).to[String].flatMap { case err =>
          Future.failed(new Exception(err))
        }
      }
    }
  }
}
