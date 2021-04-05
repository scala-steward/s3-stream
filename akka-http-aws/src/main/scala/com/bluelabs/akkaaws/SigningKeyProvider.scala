package com.bluelabs.akkaaws

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.stream.Materializer

object SigningKeyProvider {
  def default(
      region: String
  )(implicit as: ActorSystem, mat: Materializer): Future[SigningKeyProvider] =
    impl.CredentialProvider.default.map(cred =>
      new SigningKeyProvider(
        cred,
        CredentialScope(LocalDate.now(), region, "s3")
      )
    )(as.dispatcher)

  def static(
      accessKeyId: String,
      secretAccessKey: String,
      region: String
  ): SigningKeyProvider =
    new SigningKeyProvider(
      impl.CredentialProvider
        .static(accessKeyId, secretAccessKey),
      CredentialScope(LocalDate.now(), region, "s3")
    )

}

case class CredentialScope(
    date: LocalDate,
    awsRegion: String,
    awsService: String
) {
  private[akkaaws] val formattedDate: String =
    date.format(DateTimeFormatter.BASIC_ISO_DATE)

  private[akkaaws] def scopeString =
    s"$formattedDate/$awsRegion/$awsService/aws4_request"
}

class SigningKeyProvider(
    credentialsProvider: impl.CredentialProvider,
    scope: CredentialScope,
    algorithm: String = "HmacSHA256"
) {
  private[akkaaws] def signingKey(implicit
      as: ActorSystem,
      mat: Materializer
  ) = {
    import mat.executionContext
    credentialsProvider.credentials.map(credentials =>
      new impl.SigningKey(credentials, scope, algorithm)
    )
  }
}
