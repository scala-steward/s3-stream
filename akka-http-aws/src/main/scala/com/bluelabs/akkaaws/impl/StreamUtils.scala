package com.bluelabs.akkaaws.impl

import java.security.MessageDigest

import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.util.ByteString

import scala.concurrent.Future

private[akkaaws] object StreamUtils {
  def digest(
      algorithm: String = "SHA-256"
  ): Sink[ByteString, Future[ByteString]] = {
    Flow[ByteString]
      .fold(MessageDigest.getInstance(algorithm)) {
        case (digest: MessageDigest, bytes: ByteString) => {
          digest.update(bytes.asByteBuffer); digest
        }
      }
      .map { case md: MessageDigest => ByteString(md.digest()) }
      .toMat(Sink.head[ByteString])(Keep.right)
  }
}
