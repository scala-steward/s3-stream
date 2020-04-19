package com.bluelabs.s3stream.impl

import scala.math.min

import akka.NotUsed
import akka.util.ByteString
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model.headers

import com.bluelabs.s3stream.{GetObjectRequest, S3Location, HeadObjectRequest}

private[s3stream] trait ParallelDownloadSupport
    extends ObjectOperationsSupport
    with SignAndGet
    with MultipartUploadSupport {

  def getData(
      s3Location: S3Location,
      partSize: Int = MIN_CHUNK_SIZE,
      params: GetObjectRequest = GetObjectRequest.default,
      parallelism: Int
  ): Source[ByteString, NotUsed] = {
    def makeParts =
      getMetadata(s3Location, HeadObjectRequest(params.headers)).map {
        metadata =>
          val contentLength = metadata.contentLength.get
          val intervals = 0L until contentLength by partSize map (s =>
            (s, min(contentLength, s + partSize))
          )
          intervals
            .map {
              case (startIdx, openEndIdx) =>
                () =>
                  retryFuture(
                    getDataOnce(
                      s3Location,
                      params.range(headers.ByteRange(startIdx, openEndIdx - 1))
                    ).runFold(ByteString())(_ ++ _),
                    3
                  )
            }
      }

    StreamUtils
      .singleLazyAsync(makeParts)
      .mapConcat(identity)
      .mapAsync(parallelism) { fun => fun() }

  }

}
