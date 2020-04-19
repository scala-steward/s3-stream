package com.bluelabs.s3stream.impl

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.http.scaladsl.model.{Uri, ContentTypes, MediaTypes, HttpCharsets}
import com.bluelabs.s3stream.S3Location

import scala.xml.NodeSeq

private[s3stream] case class MultipartUpload(
    s3Location: S3Location,
    uploadId: String
)

private[s3stream] sealed trait UploadPartResponse {
  def multipartUpload: MultipartUpload
  def index: Int
}

private[s3stream] case class SuccessfulUploadPart(
    multipartUpload: MultipartUpload,
    index: Int,
    etag: String
) extends UploadPartResponse

private[s3stream] case class FailedUploadPart(
    multipartUpload: MultipartUpload,
    index: Int,
    exception: Throwable
) extends UploadPartResponse

private[s3stream] case class FailedUpload(reasons: Seq[Throwable])
    extends Exception(reasons.head.getMessage, reasons.head)

private[s3stream] case class CompleteMultipartUploadResult(
    location: Uri,
    bucket: String,
    key: String,
    etag: String
)

private[s3stream] object Marshalling {
  import ScalaXmlSupport._

  implicit val MultipartUploadUnmarshaller
      : FromEntityUnmarshaller[MultipartUpload] = {
    nodeSeqUnmarshaller(ContentTypes.`application/octet-stream`) map {
      case NodeSeq.Empty => throw Unmarshaller.NoContentException
      case x =>
        MultipartUpload(
          S3Location((x \ "Bucket").text, (x \ "Key").text),
          (x \ "UploadId").text
        )
    }
  }

  implicit val completeMultipartUploadResultUnmarshaller
      : FromEntityUnmarshaller[CompleteMultipartUploadResult] = {
    nodeSeqUnmarshaller(
      MediaTypes.`application/xml` withCharset HttpCharsets.`UTF-8`
    ) map {
      case NodeSeq.Empty => throw Unmarshaller.NoContentException
      case x =>
        CompleteMultipartUploadResult(
          (x \ "Location").text,
          (x \ "Bucket").text,
          (x \ "Key").text,
          (x \ "Etag").text
        )
    }
  }
}
