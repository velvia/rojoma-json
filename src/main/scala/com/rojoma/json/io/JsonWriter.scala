package com.rojoma.json
package io

import scala.{collection => sc}

import ast._

/** An object that can serialize [[com.rojoma.json.ast.JValue]]s. The intention
  * is to produce a (series of) JSON objects. */
trait JsonWriter {
  /** Write one [[com.rojoma.json.ast.JValue]].
    * @throws `IOException` if a low-level IO exception occurs.
    * @throws [[com.rojoma.json.io.JsonInvalidFloat]] if a NaN or infinite floating-point value is written.
    */
  @throws(classOf[java.io.IOException])
  def write(jobject: JValue) {
    jobject match {
      case JArray(elements) =>
        writeArray(elements)
      case JObject(fields) =>
        writeObject(fields)
      case JString(str) =>
        writeString(str)
      case JBoolean(bool) =>
        writeBoolean(bool)
      case JNull =>
        writeNull()
      case JNumber(num) =>
        writeNumber(num)
    }
  }

  protected def writeArray(elements: sc.Seq[JValue])
  protected def writeObject(fields: sc.Map[String, JValue])
  protected def writeString(s: String)
  protected def writeBoolean(b: Boolean)
  protected def writeNull()
  protected def writeNumber(num: BigDecimal)
}

