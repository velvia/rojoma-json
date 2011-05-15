package json
package io

import scala.{collection=>sc}

import java.io.{Writer, StringWriter}

import ast.JValue

/** An object that will write [[json.ast.JValue]]s in a non-human-friendly
  * "compact" format with no spaces or newlines.  This does many small
  * writes, so it is probably a good idea to wrap the `Writer` in a `BufferedWriter`. */
class CompactJsonWriter(output: Writer) extends JsonWriter {
  protected def writeArray(elements: sc.Seq[JValue]) {
    output.write('[')
    var didOne = false
    for(element <- elements) {
      if(didOne) output.write(',')
      else didOne = true
      write(element)
    }
    output.write(']')
  }

  protected def writeObject(fields: sc.Map[String, JValue]) {
    output.write('{')
    var didOne = false
    for((k, v) <- fields) {
      if(didOne) output.write(',')
      else didOne = true
      writeString(k)
      output.write(':')
      write(v)
    }
    output.write('}')
  }

  protected def writeBoolean(b: Boolean) {
    output.write(if(b) "true" else "false")
  }

  protected def writeNull() {
    output.write("null")
  }

  protected def writeDouble(x: Double) {
    if(x.isNaN || x.isInfinite) throw JsonInvalidFloat(x)
    output.write(x.toString)
  }

  protected def writeLong(x: Long) {
    output.write(x.toString)
  }

  protected def writeString(s: String) {
    WriterUtils.writeString(s, output)
  }
}

object CompactJsonWriter {
  /** Utility function for writing a single datum to a `Writer`.
    * @throws `IOException` if a low-level IO exception occurs.
    * @throws [[json.io.JsonInvalidFloat]] if a NaN or infinite floating-point value is written.
    * @see [[json.io.CompactJsonWriter]] */
  def toWriter(w: Writer, datum: JValue) = new CompactJsonWriter(w).write(datum)

  /** Utility function for writing a single datum to a `String`.
    * @return The encoded JSON object.
    * @throws [[json.io.JsonInvalidFloat]] if a NaN or infinite floating-point value is written.
    * @see [[json.io.CompactJsonWriter]] */
  def toString(datum: JValue) = {
    val w = new StringWriter
    toWriter(w, datum)
    w.toString
  }
}
