package com.bluelabs.s3stream


import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Host,RawHeader}
import akka.util.ByteString

object HttpRequests {

  def s3Request(s3Location: S3Location, method: HttpMethod = HttpMethods.GET, uriFn: (Uri => Uri) = identity): HttpRequest = {
    HttpRequest(method)
      .withHeaders(Host(requestHost(s3Location)))
      .withUri(uriFn(requestUri(s3Location)))
  }

  def initiateMultipartUploadRequest(s3Location: S3Location,serverSideEncryption: Boolean): HttpRequest = {
    val r = s3Request(s3Location, HttpMethods.POST, _.withQuery(Query("uploads")))
    if (!serverSideEncryption) r
    else r.withHeaders(RawHeader("x-amz-server-side​-encryption","AES256"))
  }

  def abortMultipartUploadRequest(s3Location: S3Location,uploadId: String): HttpRequest = {
     s3Request(s3Location, HttpMethods.DELETE, _.withQuery(Query("uploadId"->uploadId)))

  }

  def getRequest(s3Location: S3Location): HttpRequest = {
    s3Request(s3Location)
  }

  def uploadPartRequest(upload: MultipartUpload, partNumber: Int, payload: ByteString): HttpRequest = {
    s3Request(upload.s3Location,
              HttpMethods.PUT,
              _.withQuery(Query("partNumber" -> partNumber.toString, "uploadId" -> upload.uploadId))
    ).withEntity(payload)
  }

  def completeMultipartUploadRequest(upload: MultipartUpload, parts: Seq[(Int, String)])(implicit ec: ExecutionContext): Future[HttpRequest] = {
    val payload = <CompleteMultipartUpload>
        {
          parts.map{case (partNumber, etag) => <Part><PartNumber>{partNumber}</PartNumber><ETag>{etag}</ETag></Part>}
        }
      </CompleteMultipartUpload>
    for {
      entity <- Marshal(payload).to[RequestEntity]
    } yield {
      s3Request(upload.s3Location,
                HttpMethods.POST,
                _.withQuery(Query("uploadId" -> upload.uploadId))
      ).withEntity(entity)
    }
  }


  def requestHost(s3Location: S3Location): Uri.Host = Uri.Host(s"${s3Location.bucket}.s3.amazonaws.com")

  def requestUri(s3Location: S3Location): Uri = Uri(s"/${s3Location.key}").withHost(requestHost(s3Location)).withScheme("https")
}
