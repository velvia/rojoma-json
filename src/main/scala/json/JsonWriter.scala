package json
package io

import scala.{collection => sc}

import ast._

case class JsonInvalidFloat(value: Double) extends RuntimeException("Attempted to serialize a NaN or infinite value into a JSON stream.")

/** An object that can serialize [[json.ast.JValue]]s. The intention
  * is to produce a (series of) JSON objects. */
trait JsonWriter {
  /** Write one [[json.ast.JValue]].
    * @throws `IOException` if a low-level IO exception occurs.
    * @throws [[json.io.JsonInvalidFloat]] if a NaN or infinite floating-point value is written.
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
      case JFloatingPoint(dbl) =>
        writeDouble(dbl)
      case JIntegral(i) =>
        writeLong(i)
    }
  }

  protected def writeArray(elements: sc.Seq[JValue])
  protected def writeObject(fields: sc.Map[String, JValue])
  protected def writeString(s: String)
  protected def writeBoolean(b: Boolean)
  protected def writeNull()
  protected def writeDouble(dbl: Double)
  protected def writeLong(l: Long)
}

