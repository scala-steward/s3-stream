package com.bluelabs.s3stream

import akka.actor.ActorSystem
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.testkit.TestKit
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.{AnyFlatSpecLike => FlatSpecLike}

class StreamUtilsSpec(_system: ActorSystem)
    extends TestKit(_system)
    with FlatSpecLike
    with Matchers
    with ScalaFutures {
  def this() = this(ActorSystem("StreamUtilsSpec"))

  @scala.annotation.nowarn
  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system).withDebugLogging(true)
  )

  "counter" should "increment starting from 0" in {
    val testSource = impl.StreamUtils.counter()
    testSource.runWith(TestSink.probe[Int]).request(2).expectNext(0, 1)
  }

  it should "allow specifying an initial value" in {
    val testSource = impl.StreamUtils.counter(5)
    testSource.runWith(TestSink.probe[Int]).request(3).expectNext(5, 6, 7)
  }

}
