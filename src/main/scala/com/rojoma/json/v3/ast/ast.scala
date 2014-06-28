package com.rojoma.json.v3
package ast

import scala.language.implicitConversions
import scala.{collection => sc}
import scala.reflect.ClassTag
import scala.annotation.implicitNotFound

sealed abstract class JsonInvalidValue(msg: String) extends IllegalArgumentException(msg) {
  def value: Any
}
case class JsonInvalidFloat(value: Float) extends JsonInvalidValue("Attempted to place a NaN or infinite value into a JSON AST.")
case class JsonInvalidDouble(value: Double) extends JsonInvalidValue("Attempted to place a NaN or infinite value into a JSON AST.")

// Closed typeclass to find out what concrete types a subclass of JValue represents
sealed abstract class Json[T <: JValue] {
  val jsonTypes : Set[JsonType]
  override lazy val toString = jsonTypes.toSeq.map(_.toString).sorted.mkString(",")
}

sealed trait JsonType

/** A JSON datum.  This can be safely downcast to a more-specific type
  * using the `cast` method which is implicitly added to this class
  * in the companion object.*/
sealed trait JValue {
  override def toString = io.PrettyJsonWriter.toString(this)

  /** Forces this [[com.rojoma.json.v3.ast.JValue]] to be fully
    * evaluated.  In particular, the compound
    * [[com.rojoma.json.codec.JsonCodec]]s will produce views of their
    * inputs instead of fully-evaluated
    * [[com.rojoma.json.v3.ast.JValue]]s.  This can be problematic if
    * the underlying structure can be mutated before this object is
    * used, or if this object is passed to another thread.
    *
    * What is or is not copied is not defined; the only postcondition
    * is that there are no lazy values left in the returned tree.
    * 
    * @return An equal [[com.rojoma.json.v3.ast.JValue]] with strict values. */
  def forced: JValue

  /** Produces a dynamically typed view of this `JValue` which can be
    * descended using dot-notation for field names or apply-type
    * syntax for arrays.  It can be turned back into a `JValue` with
    * the `static` or `staticOpt` methods.
    *
    * Note that certain field-names (the names common to all objects
    * plus `static`, `apply`, and `applyDynamic` cannot be accessed
    * with simple field-notation.  Instead, pass them as strings to
    * the `apply` method. */
  def dynamic = new com.rojoma.json.v3.dynamic.DynamicJValue(Some(this))

  /* The concrete type of this value. */
  def jsonType: JsonType
}

object JValue {
  final override def toString = "value"

  /** Safe downcast with a fairly nice syntax.
    * This will statically prevent attempting to cast anywhere except
    * a subclass of the value's static type.
    *
    * It can be used for navigation in a JSON tree:
    * {{{
    *   for {
    *     JObject(foo) <- raw.cast[JObject]
    *     JArray(elems) <- foo.get("foo").flatMap(_.cast[JArray])
    *   } yield {
    *     ...something with elems...
    *   } getOrElse(throw "couldn't find interesting elements")
    * }}}
    */
  implicit def toCastable[T <: JValue](x: T) = new `-impl`.ast.DownCaster(x)

  implicit object Concrete extends Json[JValue] {
    val jsonTypes = JAtom.Concrete.jsonTypes ++ JCompound.Concrete.jsonTypes
  }
}

/** A JSON "atom" — anything except arrays or objects.  This and [[com.rojoma.json.v3.ast.JCompound]] form
  * a partition of the set of valid [[com.rojoma.json.v3.ast.JValue]]s. */
sealed abstract class JAtom extends JValue {
  def forced: JAtom
}

object JAtom {
  final override def toString = "atom"

  implicit object Concrete extends Json[JAtom] {
    val jsonTypes = Set[JsonType](JNumber, JString, JBoolean, JNull)
  }
}

/** A number. */
sealed abstract class JNumber extends JAtom {
  def toByte: Byte
  def toShort: Short
  def toInt: Int
  def toLong: Long
  def toBigInt: BigInt
  def toFloat: Float
  def toDouble: Double
  def toBigDecimal: BigDecimal

  final def jsonType = JNumber

  override final def toString = asString
  protected def asString: String

  override def equals(o: Any) = o match {
    case that: JNumber => this.toBigDecimal == that.toBigDecimal
  }

  override final lazy val hashCode = toBigDecimal.hashCode

  def forced: JNumber = this
}

object JNumber extends JsonType {
  final override def toString = "number"

  private val stdCtx = java.math.MathContext.UNLIMITED

  private class JIntNumber(val toInt: Int) extends JNumber {
    def toByte = toInt.toByte
    def toShort = toInt.toShort
    def toLong = toInt.toLong
    def toBigInt = BigInt(toInt)

    def toFloat = toInt.toFloat
    def toDouble = toInt.toDouble
    lazy val toBigDecimal = BigDecimal(toInt, stdCtx)

    def asString = toInt.toString
    override def equals(o: Any) = o match {
      case that: JIntNumber => this.toInt == that.toInt
      case that: JLongNumber => this.toLong == that.toLong
      case that: JBigIntNumber => this.toBigInt == that.toBigInt
      case other => super.equals(other)
    }
  }

  private class JLongNumber(val toLong: Long) extends JNumber {
    def toByte = toLong.toByte
    def toShort = toLong.toShort
    def toInt = toLong.toInt
    def toBigInt = BigInt(toLong)

    def toFloat = toLong.toFloat
    def toDouble = toLong.toDouble
    lazy val toBigDecimal = BigDecimal(toLong, stdCtx)

    def asString = toLong.toString
    override def equals(o: Any) = o match {
      case that: JLongNumber => this.toLong == that.toLong
      case that: JIntNumber => this.toLong == that.toLong
      case that: JBigIntNumber => this.toBigInt == that.toBigInt
      case _ => super.equals(o)
    }
  }

  private class JBigIntNumber(val toBigInt: BigInt) extends JNumber {
    def toByte: Byte = toBigInt.toByte
    def toShort: Short = toBigInt.toShort
    def toInt: Int = toBigInt.toInt
    def toLong: Long = toBigInt.toLong

    def toFloat: Float = toBigInt.toFloat
    def toDouble: Double = toBigInt.toDouble
    lazy val toBigDecimal = BigDecimal(toDouble, stdCtx)

    def asString = toBigInt.toString
    override def equals(o: Any) = o match {
      case that: JBigIntNumber => this.toBigInt == that.toBigInt
      case that: JIntNumber => this.toBigInt == that.toBigInt
      case that: JLongNumber => this.toBigInt == that.toBigInt
      case _ => super.equals(o)
    }
  }

  private class JFloatNumber(val toFloat: Float) extends JNumber {
    if(toFloat.isNaN || toFloat.isInfinite) throw JsonInvalidFloat(toFloat)

    def toByte = toFloat.toByte
    def toShort: Short = toFloat.toShort
    def toInt: Int = toFloat.toInt
    def toLong: Long = toFloat.toLong
    def toBigInt: BigInt = toBigDecimal.toBigInt

    def toDouble: Double = toFloat.toDouble
    lazy val toBigDecimal = BigDecimal(toDouble, stdCtx)

    def asString = toFloat.toString
  }

  private class JDoubleNumber(val toDouble: Double) extends JNumber {
    if(toFloat.isNaN || toFloat.isInfinite) throw JsonInvalidDouble(toDouble)

    def toByte: Byte = toDouble.toByte
    def toShort: Short = toDouble.toShort
    def toInt: Int = toDouble.toInt
    def toLong: Long = toDouble.toLong
    def toBigInt: BigInt = toBigDecimal.toBigInt

    def toFloat: Float = toDouble.toFloat
    lazy val toBigDecimal = BigDecimal(toDouble, stdCtx)

    def asString = toDouble.toString
  }

  private class JBigDecimalNumber(val toBigDecimal: BigDecimal) extends JNumber {
    def toByte: Byte = toBigDecimal.toByte
    def toShort: Short = toBigDecimal.toShort
    def toInt: Int = toBigDecimal.toInt
    def toLong: Long = toBigDecimal.toLong
    def toBigInt: BigInt = toBigDecimal.toBigInt

    def toFloat: Float = toBigDecimal.toFloat
    def toDouble: Double = toBigDecimal.toDouble

    def asString = toBigDecimal.toString
  }

  private class JUncheckedStringNumber(override val asString: String) extends JNumber {
    def toByte: Byte = try { asString.toByte} catch { case _: NumberFormatException => toBigDecimal.toByte }
    def toShort: Short = try { asString.toShort } catch { case _: NumberFormatException => toBigDecimal.toShort }
    def toInt: Int = try { asString.toInt } catch { case _: NumberFormatException => toBigDecimal.toInt }
    def toLong: Long = try { asString.toLong } catch { case _: NumberFormatException => toBigDecimal.toLong }
    def toBigInt = try { BigInt(asString) } catch { case _: NumberFormatException => toBigDecimal.toBigInt }

    def toFloat: Float = try { asString.toFloat } catch { case _: NumberFormatException => toBigDecimal.toFloat }
    def toDouble: Double = try { asString.toDouble } catch { case _: NumberFormatException => toBigDecimal.toDouble }
    lazy val toBigDecimal = BigDecimal(asString, stdCtx)

    override def forced = new JBigDecimalNumber(toBigDecimal)
  }

  def apply(b: Byte): JNumber = new JIntNumber(b)
  def apply(s: Short): JNumber = new JIntNumber(s)
  def apply(i: Int): JNumber = new JIntNumber(i)
  def apply(l: Long): JNumber = new JLongNumber(l)
  def apply(bi: BigInt): JNumber = new JBigIntNumber(bi)
  def apply(f: Float): JNumber = new JFloatNumber(f)
  def apply(d: Double): JNumber = new JDoubleNumber(d)
  def apply(bd: BigDecimal): JNumber = new JBigDecimalNumber(bd)

  def unsafeFromString(s: String): JNumber = new JUncheckedStringNumber(s)

  implicit object Concrete extends Json[JNumber] {
    val jsonTypes = Set[JsonType](JNumber)
  }
}

/** A JSON string.  This does not yet enforce well-formedness with
  * respect to surrogate pairs, but it probably should. */
case class JString(string: String) extends JAtom {
  def jsonType = JString

  def forced: this.type = this
}

object JString extends JsonType {
  override final def toString = "string"

  implicit object Concrete extends Json[JString] {
    val jsonTypes = Set[JsonType](JString)
  }
}

/** A boolean */
case class JBoolean(boolean: Boolean) extends JAtom {
  def jsonType = JBoolean

  def forced: this.type = this
}

object JBoolean extends scala.runtime.AbstractFunction1[Boolean, JBoolean] with JsonType {
  // wish I could override apply(Boolean) to use these.  At least
  // JsonReader will, though.
  val canonicalTrue = JBoolean(true)
  val canonicalFalse = JBoolean(false)

  override final def toString = "boolean"

  implicit object Concrete extends Json[JBoolean] {
    val jsonTypes = Set[JsonType](JBoolean)
  }
}

/** Null. */
sealed abstract class JNull extends JAtom // so the object has a nameable type
case object JNull extends JNull with JsonType {
  final override def toString = "null"

  def jsonType = JNull

  implicit object Concrete extends Json[JNull] {
    val jsonTypes = Set[JsonType](JNull)
  }

  def forced: this.type = this
}

/** The common superclass of arrays and objects.  This and [[com.rojoma.json.v3.ast.JAtom]] form
  * a partition of the set of valid [[com.rojoma.json.v3.ast.JValue]]s. */
sealed trait JCompound extends JValue {
  def forced: JCompound
}

object JCompound {
  final override def toString = "compound"

  implicit object Concrete extends Json[JCompound] {
    val jsonTypes = JArray.Concrete.jsonTypes ++ JObject.Concrete.jsonTypes
  }
}

/** A JSON array, implemented as a thin wrapper around a sequence of [[com.rojoma.json.v3.ast.JValue]]s.
  * In many ways this can be treated as a `Seq`, but it is in fact not one. */
case class JArray(elems: sc.Seq[JValue]) extends Iterable[JValue] with PartialFunction[Int, JValue] with JCompound {
  import com.rojoma.`json-impl`.AnnoyingJArrayHack._

  override def size = elems.size
  def length = elems.length
  override def toList = elems.toList
  override def toStream = elems.toStream
  override def toVector = elems.toVector
  override def toArray[B >: JValue : ClassTag] = elems.toArray[B]

  def apply(idx: Int) = toSeq(idx)
  def isDefinedAt(idx: Int) = toSeq.isDefinedAt(idx)
  def iterator = elems.iterator

  override def toSeq = elems.toSeq
  override def toIndexedSeq = elems.toIndexedSeq

  def forced: JArray = {
    // not just "toSeq.map(_forced)" because the seq might be a Stream or view
    val forcedArray: Vector[JValue] = elems.map(_.forced)(sc.breakOut)
    new JArray(forcedArray) {
      override def forced = this
    }
  }

  override def equals(o: Any): Boolean = {
    o match {
      case that: JArray =>
        this.elems == that.elems
      case _ =>
        false
    }
  }

  def jsonType = JArray
}

object JArray extends scala.runtime.AbstractFunction1[sc.Seq[JValue], JArray] with JsonType {
  val canonicalEmpty = JArray(Vector.empty) // Vector because JsonReader is guaranteed to return JArrays which contain Vectors.
  override final def toString = "array"
  implicit object Concrete extends Json[JArray] {
    val jsonTypes = Set[JsonType](JArray)
  }
}

/** A JSON object, implemented as a thin wrapper around a map from `String` to [[com.rojoma.json.v3.ast.JValue]].
  * In many ways this can be treated as a `Map`, but it is in fact not one. */
case class JObject(val fields: sc.Map[String, JValue]) extends Iterable[(String, JValue)] with PartialFunction[String, JValue] with JCompound {
  override def size = fields.size
  def contains(s: String) = fields.contains(s)
  def apply(key: String) = fields(key)
  def get(key: String) = fields.get(key)
  def getOrElse[B1 >: JValue](key: String, default: => B1): B1 = fields.getOrElse(key, default)
  def isDefinedAt(key: String) = fields.isDefinedAt(key)
  def iterator = fields.iterator
  def keys = fields.keys
  def keysIterator = fields.keysIterator
  def keySet = fields.keySet
  def values = fields.values
  def valuesIterator = fields.valuesIterator
  def mapValues[C](f: JValue => C): sc.Map[String, C] = fields.mapValues(f)
  override def toSeq = fields.toSeq
  override def toMap[T, U] (implicit ev: <:<[(String, JValue), (T, U)]): Map[T, U] = fields.toMap

  def forced: JObject = {
    // would be nice to freeze this into an actual immutable Map in
    // order to preserve ordering and yet be actually unchangable, but
    // instead we'll just trust that the Bad People who are relying on
    // the ordering of fields in their JSON objects are not *so* bad
    // that they'll downcast an sc.Map to an scm.Map.  Unchangability
    // of the result isn't part of "forced"'s contract anyway; merely
    // full evaluation.
    val forcedMap = new sc.mutable.LinkedHashMap[String, JValue]
    for((k, v) <- fields) forcedMap += k -> v.forced
    new JObject(forcedMap) {
      override def forced = this
    }
  }

  def jsonType = JObject
}

object JObject extends scala.runtime.AbstractFunction1[sc.Map[String, JValue], JObject] with JsonType {
  val canonicalEmpty = JObject(Map.empty) // _Not_ LinkedHashMap because all JsonReader guarantees is ordering of elements, which this satisfies.
  override final def toString = "object"
  implicit object Concrete extends Json[JObject] {
    val jsonTypes = Set[JsonType](JObject)
  }
}