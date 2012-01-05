package com.rojoma.json
package io

import org.scalatest.FunSuite
import org.scalatest.matchers.MustMatchers

class JsonEventIteratorTests extends FunSuite with MustMatchers {
  def r(s: String) = new java.io.StringReader(s)
  def i(s: String) = new JsonEventIterator(new TokenIterator(r(s)))
  def e(s: String) = i(s).next().event

  test("reading single tokens that are legitimate start-of-datum tokens") {
    e("\"hello\"") must equal (StringEvent("hello"))
    e("true") must equal (IdentifierEvent("true"))
    e("1.432") must equal (NumberEvent(BigDecimal("1.432")))
    e("[") must equal (StartOfArrayEvent)
    e("{") must equal (StartOfObjectEvent)
  }

  test("reading non start-of-datum tokens fails") {
    evaluating { e("]") } must produce [JsonUnexpectedToken]
    evaluating { e("}") } must produce [JsonUnexpectedToken]
    evaluating { e(":") } must produce [JsonUnexpectedToken]
    evaluating { e(",") } must produce [JsonUnexpectedToken]
  }

  test("skipRestOfCompound() before calling next() does nothing") {
    i("[1,2,3]").skipRestOfCompound().next().event must equal (StartOfArrayEvent)
  }

  test("skipRestOfCompound() before next() but after hasNext does nothing") {
    val it = i("[1,2,3]")
    it.hasNext
    it.skipRestOfCompound().next().event must equal (StartOfArrayEvent)
  }

  test("skipRestOfCompound() after calling next() to enter skips rest of the datum") {
    var it = i("[1,2,3]")
    it.next()
    it.skipRestOfCompound().toSeq must be ('empty)

    it = i("[['a','b','c'],2,3]")
    it.next()
    it.skipRestOfCompound().toSeq must be ('empty)

    it = i("{hello:'world',smiling:'gnus'}")
    it.next()
    it.skipRestOfCompound().toSeq must be ('empty)
  }

  test("skipRestOfCompound() within a nested object skips the rest of the inner datum") {
    val it = i("[['a','b','c'],2,3]")
    it.next().event must equal (StartOfArrayEvent)
    it.next().event must equal (StartOfArrayEvent)
    it.skipRestOfCompound().next().event must equal (NumberEvent(BigDecimal(2)))
  }

  test("skipNextDatum() in a multi-object stream leaves it positioned at the start of next object") {
    var it = i("[1,2,3] 'gnu'")
    it.skipNextDatum()
    it.next().event must equal (StringEvent("gnu"))
  }

  test("skipRestOfCompound() between top-level objects does nothing") {
    var it = i("[1,2,3] 'gnu'")
    it.skipNextDatum()
    it.skipRestOfCompound().next().event must equal (StringEvent("gnu"))
  }

  test("skipRestOfCompound() at the end does not raise NoSuchElementException") {
    var it = i("5")
    it.next()
    it.skipRestOfCompound()
    it.hasNext must be (false)
  }

  test("skipRestOfCompound() in an incomplete object raises JsonEOF") {
    var it = i("[1,2,3")
    it.next()
    evaluating(it.skipRestOfCompound()) must produce[JsonEOF]
  }

  test("skipNextDatum() at EOF produces NoSuchElementException") {
    var it = i("")
    evaluating(it.skipNextDatum()) must produce[NoSuchElementException]
  }

  test("skipNextDatum() at the top level reads a whole object") {
    i("5").skipNextDatum().toSeq must be ('empty)
    i("[1,2,3]").skipNextDatum().toSeq must be ('empty)
  }

  test("skipNextDatum() of an incomplete object raises JsonEOF") {
    var it = i("[1,2,3")
    evaluating(it.skipNextDatum()) must produce[JsonEOF]
  }

  test("skipNextDatum() within an array skips one item") {
    var it = i("[1,2,3]")
    it.next()
    it.skipNextDatum().next().event must equal (NumberEvent(BigDecimal(2)))
  }

  test("skipNextDatum() at the end of an array does not move") {
    var it = i("[1]")
    it.next()
    it.next()
    it.skipNextDatum().next().event must equal (EndOfArrayEvent)
  }

  test("skipNextDatum() within an object skips both field and datum") {
    var it = i("{'hello':'world','smiling','gnus'}")
    it.next()
    it.skipNextDatum()
    it.next().event must equal (FieldEvent("smiling"))

    it = i("{'hello':'world','smiling','gnus'}")
    it.next()
    it.next() // position before "world"
    it.skipNextDatum()
    it.next().event must equal (FieldEvent("smiling"))
  }


  test("skipNextDatum() at the end of object does not move") {
    var it = i("{'hello':'world'}")
    it.next()
    it.next()
    it.next().event must equal (StringEvent("world"))
    it.skipNextDatum().next().event must equal (EndOfObjectEvent)
  }
}