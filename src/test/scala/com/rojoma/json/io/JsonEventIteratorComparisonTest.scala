package com.rojoma.json
package io

import scala.reflect.ClassTag

import org.scalatest.{FunSuite, MustMatchers}
import org.scalatest.prop.PropertyChecks

import ast.JValue
import testsupport.ArbitraryJValue.ArbitraryJValue

class JsonEventIteratorComparisonTests extends FunSuite with MustMatchers with PropertyChecks {
  def simpleIterator(s: String) = new JsonEventIterator(new JsonTokenIterator(new java.io.StringReader(s)))
  def blockIterator(s: String) = new JsonEventIterator(new BlockJsonTokenIterator(s))
  def fusedIterator(s: String) = new FusedBlockJsonEventIterator(s)

  def attempt[A](a: => A): Either[Exception, A] =
    try {
      Right(a)
    } catch {
      case e: Exception => Left(e)
    }

  def compareJsonExceptions(a: Exception, b: Exception) {
    def mismatch(): Nothing =
      fail("Mismatched exceptions: " + a.getMessage + ", " + b.getMessage)

    def check[T <: Exception : ClassTag](e: Exception)(f: T => Any) {
      if(implicitly[ClassTag[T]].runtimeClass.isInstance(e)) f(e.asInstanceOf[T])
      else mismatch()
    }

    (a,b) match {
      case (nst1: NoSuchTokenException, nst2: NoSuchTokenException) =>
        nst1.position must equal (nst2.position)
      case (j1: JsonReaderException, j2: JsonReaderException) =>
        j1 match {
          case uc1: JsonUnexpectedCharacter =>
            check[JsonUnexpectedCharacter](j2) { uc2 =>
              uc1.character must equal (uc2.character)
              uc1.expected must equal (uc2.expected)
            }
          case num1: JsonNumberOutOfRange =>
            check[JsonNumberOutOfRange](j2) { num2 =>
              num1.number must equal (num2.number)
            }
          case eof1: JsonLexerEOF =>
            check[JsonLexerEOF](j2) { eof2 =>
            }
          case eof1: JsonParserEOF =>
            check[JsonParserEOF](j2) { eof2 =>
            }
          case tok1: JsonUnexpectedToken =>
            check[JsonUnexpectedToken](j2) { tok2 =>
              tok1.token must equal (tok2.token)
              tok1.token.position must equal (tok2.token.position)
              tok1.expected must equal (tok2.expected)
            }
          case _: JsonUnknownIdentifier =>
            fail("JsonUnknownIdentifier thrown from event generator?")
          case _: JsonBadParse =>
            fail("JsonBadParse thrown from event generator?")
        }

        j1.position must equal (j2.position)
      case _ =>
        fail("At least one iterator threw a non-rojoma-json exception: " + a.getClass.getName + ", " + b.getClass.getName)
    }
  }

  def compare(a: Iterator[JsonEvent], b: Iterator[JsonEvent]) {
    while(true) {
      val aNext = attempt(a.hasNext)
      val bNext = attempt(b.hasNext)

      (aNext, bNext) match {
        case (Right(available1), Right(available2)) =>
          available1 must equal (available2)
        case (Left(ex1), Left(ex2)) =>
          compareJsonExceptions(ex1, ex2)
          return
        case (Right(_), Left(_)) =>
          fail("First iterator's hasNext succeeded; second threw an exception")
        case (Left(_), Right(_)) =>
          fail("Second iterator's hasNext succeeded; right threw an exception")
      }

      val aEv = attempt(a.next())
      val bEv = attempt(b.next())
      (aEv, bEv) match {
        case (Right(ev1), Right(ev2)) =>
          ev1 must equal (ev2)
          ev1.position must equal (ev2.position)
        case (Left(ex1), Left(ex2)) =>
          compareJsonExceptions(ex1, ex2)
          return
        case (Right(_), Left(_)) =>
          fail("First iterator produced an event; second threw an exception")
        case (Left(_), Right(_)) =>
          fail("Second iterator produced an event; first threw an exception")
      }
    }
  }

  test("JsonEventIterator (simple) and FusedBlockJsonEventIterator produce the same results on well-formed data") {
    forAll { (datum: JValue, compact: Boolean) =>
      val s = if(compact) CompactJsonWriter.toString(datum) else PrettyJsonWriter.toString(datum)
      compare(simpleIterator(s), fusedIterator(s))
    }
  }

  test("JsonEventIterator (blocked) and FusedBlockJsonEventIterator produce the same results on well-formed data") {
    forAll { (datum: JValue, compact: Boolean) =>
      val s = if(compact) CompactJsonWriter.toString(datum) else PrettyJsonWriter.toString(datum)
      compare(blockIterator(s), fusedIterator(s))
    }
  }

  test("JsonEventIterator (simple) and JsonEventIterator (blocked) produce the same results on well-formed data") {
    forAll { (datum: JValue, compact: Boolean) =>
      val s = if(compact) CompactJsonWriter.toString(datum) else PrettyJsonWriter.toString(datum)
      compare(simpleIterator(s), blockIterator(s))
    }
  }

  def withTruncatedJson(f: String => Unit) {
    forAll { (datum: JValue, compact: Boolean) =>
      val fullS = if(compact) CompactJsonWriter.toString(datum) else PrettyJsonWriter.toString(datum)
      val halfS = fullS.substring(0, fullS.length / 2)
      f(halfS)
    }
  }

  test("JsonEventIterator (simple) and FusedBlockJsonEventIterator produce the same results on truncated data") {
    withTruncatedJson { s =>
      compare(simpleIterator(s), fusedIterator(s))
    }
  }

  test("JsonEventIterator (blocked) and FusedBlockJsonEventIterator produce the same results on truncated data") {
    withTruncatedJson { s =>
      compare(blockIterator(s), fusedIterator(s))
    }
  }

  test("JsonEventIterator (simple) and JsonEventIterator (blocked) produce the same results on truncated data") {
    withTruncatedJson { s =>
      compare(simpleIterator(s), blockIterator(s))
    }
  }

  def withBrokenJson(f: String => Unit) {
    val punct = Array(":",",","{","}", "[","]","//","/*") // It is important that there are 8 of theses
    forAll { (datum: JValue, n: Int, i: Int) =>
      val s = PrettyJsonWriter.toString(datum)
      val split = s.split(" ", -1)
      val nthSpace = if(n == Int.MinValue) 0 else (n.abs % split.length)
      val withTokenAdded = (split.take(nthSpace) ++ Array(punct(i & 7)) ++ split.drop(nthSpace)).mkString(" ")
      f(withTokenAdded)
    }
  }

  test("JsonEventIterator (simple) and FusedBlockJsonEventIterator produce the same results on malformed data") {
    withBrokenJson { s =>
      compare(simpleIterator(s), fusedIterator(s))
    }
  }

  test("JsonEventIterator (blocked) and FusedBlockJsonEventIterator produce the same results on malformed data") {
    withBrokenJson { s =>
      compare(blockIterator(s), fusedIterator(s))
    }
  }

  test("JsonEventIterator (simple) and JsonEventIterator (blocked) produce the same results on malformed data") {
    withBrokenJson { s =>
      compare(simpleIterator(s), blockIterator(s))
    }
  }
}
