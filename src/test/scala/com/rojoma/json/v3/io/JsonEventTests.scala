package com.rojoma.json.v3
package io

import org.scalatest.{FunSuite, MustMatchers, EitherValues}
import org.scalatest.prop.PropertyChecks

import testsupport.ArbitraryJValue._
import ast._
import codec._

class JsonEventTests extends FunSuite with MustMatchers with PropertyChecks with EitherValues {
  test("JSON events roundtrip without loss") {
    forAll { (v: JValue) =>
      val tokens = new JsonEventIterator(v.toString).toList
      val decoded = JsonDecode.fromJValue[List[JsonEvent]](JsonEncode.toJValue(tokens)).right.value
      decoded must equal (tokens)
      decoded.map(_.position) must equal (tokens.map(_.position))
    }
  }

  test("JSON events roundtrip sans position without loss except for position") {
    forAll { (v: JValue) =>
      implicit val evCodec = JsonEvent.sansPositionCodec
      val tokens = new JsonEventIterator(v.toString).toList
      val decoded = JsonDecode.fromJValue[List[JsonEvent]](JsonEncode.toJValue(tokens)).right.value
      decoded must equal (tokens)
      decoded.map(_.position).foreach(_ must equal (Position.Invalid))
    }
  }
}
