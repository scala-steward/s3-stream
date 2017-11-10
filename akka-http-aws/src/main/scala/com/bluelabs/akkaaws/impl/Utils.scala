package com.bluelabs.akkaaws.impl

import javax.xml.bind.DatatypeConverter

import akka.util.ByteString

private[akkaaws] object Utils {
  def encodeHex(bytes: Array[Byte]): String = {
    DatatypeConverter.printHexBinary(bytes).toLowerCase
  }

  def encodeHex(bytes: ByteString): String = {
    encodeHex(bytes.toArray)
  }
}
