package com.bluelabs.akkaaws.impl

import com.bluelabs.akkaaws.CredentialScope
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private[akkaaws] case class SigningKey(
    credentials: AWSCredentials,
    scope: CredentialScope,
    algorithm: String = "HmacSHA256"
) {

  val rawKey =
    new SecretKeySpec(s"AWS4${credentials.secretAccessKey}".getBytes, algorithm)

  def signature(message: Array[Byte]): Array[Byte] = {
    signWithKey(key, message)
  }

  def hexEncodedSignature(message: Array[Byte]): String = {
    Utils.encodeHex(signature(message))
  }

  def credentialString: String =
    s"${credentials.accessKeyId}/${scope.scopeString}"

  lazy val key: SecretKeySpec =
    wrapSignature(dateRegionServiceKey, "aws4_request".getBytes)

  lazy val dateRegionServiceKey: SecretKeySpec =
    wrapSignature(dateRegionKey, scope.awsService.getBytes)

  lazy val dateRegionKey: SecretKeySpec =
    wrapSignature(dateKey, scope.awsRegion.getBytes)

  lazy val dateKey: SecretKeySpec =
    wrapSignature(rawKey, scope.formattedDate.getBytes)

  private def wrapSignature(
      signature: SecretKeySpec,
      message: Array[Byte]
  ): SecretKeySpec = {
    new SecretKeySpec(signWithKey(signature, message), algorithm)
  }

  private def signWithKey(
      key: SecretKeySpec,
      message: Array[Byte]
  ): Array[Byte] = {
    val mac = Mac.getInstance(algorithm)
    mac.init(key)
    mac.doFinal(message)
  }
}
