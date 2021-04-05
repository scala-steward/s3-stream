package com.bluelabs.s3stream.impl

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpRequest, RequestEntity}
import akka.http.scaladsl.model.Uri.Query
import akka.util.ByteString
import com.bluelabs.s3stream.{
  S3Location,
  PostObjectRequest,
  DeleteObjectRequest,
  PutObjectRequest
}

private[s3stream] trait MultipartUploadHttpRequests
    extends BasicS3HttpRequests {

  protected def initiateMultipartUploadRequest(
      s3Location: S3Location,
      method: PostObjectRequest
  ): HttpRequest =
    s3Request(s3Location, method, _.withQuery(Query("uploads")))

  protected def abortMultipartUploadRequest(
      s3Location: S3Location,
      uploadId: String
  ): HttpRequest =
    s3Request(
      s3Location,
      DeleteObjectRequest,
      _.withQuery(Query("uploadId" -> uploadId))
    )

  protected def uploadPartRequest(
      upload: MultipartUpload,
      partNumber: Int,
      payload: ByteString
  ): HttpRequest =
    s3Request(
      upload.s3Location,
      PutObjectRequest.default,
      _.withQuery(
        Query(
          "partNumber" -> partNumber.toString,
          "uploadId" -> upload.uploadId
        )
      )
    ).withEntity(payload)

  protected def completeMultipartUploadRequest(
      upload: MultipartUpload,
      parts: Seq[(Int, String)]
  )(implicit ec: ExecutionContext): Future[HttpRequest] = {
    val payload = <CompleteMultipartUpload>
                    {
      parts.map { case (partNumber, etag) =>
        <Part><PartNumber>{partNumber}</PartNumber><ETag>{etag}</ETag></Part>
      }
    }
                  </CompleteMultipartUpload>
    for {
      entity <- Marshal(payload).to[RequestEntity]
    } yield {
      s3Request(
        upload.s3Location,
        PostObjectRequest.default,
        _.withQuery(Query("uploadId" -> upload.uploadId))
      ).withEntity(entity)
    }
  }

}
