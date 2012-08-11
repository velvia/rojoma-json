package com.rojoma.json
package util

import ast._
import testsupport.ArbitraryJValue._
import testsupport.ArbitraryValidString._

import org.scalatest.FunSuite
import org.scalatest.matchers.MustMatchers
import org.scalatest.prop.PropertyChecks

import org.scalacheck.{Gen, Arbitrary}

import io.JsonTokenGeneratorTests._

class JArrayProducerTest extends FunSuite with MustMatchers with PropertyChecks {
  def r(targets: List[JValue], s: List[String]) = {
    def loop(state: JArrayProducer, targets: List[JValue], inputs: List[WrappedCharArray]): String = {
      inputs match {
        case hd :: tl =>
          state(hd) match {
            case JArrayProducer.More(newState) =>
              loop(newState, targets, tl)
            case JArrayProducer.Element(value, newState, remainingInput) =>
              value must equal (targets.head)
              loop(newState, targets.tail, remainingInput :: tl)
            case JArrayProducer.EndOfList(_, remainingInput) =>
              targets must equal (Nil)
              remainingInput.toString + tl.mkString
            case e: JArrayProducer.Error =>
              targets must equal (Nil)
              JArrayProducer.throwError(e)
          }
        case Nil =>
          state.endOfInput() match {
            case JArrayProducer.FinalEndOfList(_) =>
              targets must equal (Nil)
              ""
            case e: JArrayProducer.EndError =>
              targets must equal (Nil)
              JArrayProducer.throwError(e)
          }
      }
    }
    loop(JArrayProducer.newProducer, targets, s.map(WrappedCharArray(_)))
  }

  def withSplitString(s: String)(f: List[String] => Unit) {
    forAll(splitPoints(s)) { p =>
      whenever(p.forall { i => 0 <= i && i <= s.length }) {
        f(splitAt(s, p))
      }
    }
  }

  def badRead[T: Manifest](expected: List[JValue], s: String) {
    withSplitString(s) { ss =>
      evaluating(r(expected, ss)) must produce[T]
    }
  }

  test("Reading well-formed values") {
    forAll(splittableJson[JArray], ArbitraryValidString.arbitrary) { (jps, suffix) =>
      val (jvalue, pretty, splits) = jps
      val trueSuffix = if(jvalue.isInstanceOf[JNull] || jvalue.isInstanceOf[JBoolean] || jvalue.isInstanceOf[JNumber]) " " + suffix
                       else suffix
      val s = JsonUtil.renderJson(jvalue, pretty = pretty) + trueSuffix
      whenever(splits.forall(i => 0 <= i && i <= s.length)) {
        r(jvalue.toList, splitAt(s, splits)) must equal(trueSuffix)
      }
    }
  }

  test("Reading ill-formed values") {
    badRead[io.JsonEOF](Nil, "")
    badRead[io.JsonEOF](Nil, "[")
    badRead[io.JsonUnexpectedToken](Nil, "[,")
    badRead[io.JsonUnexpectedCharacter](List(JNumber(5), JNumber(6)), "[5,6,!]")
  }
}