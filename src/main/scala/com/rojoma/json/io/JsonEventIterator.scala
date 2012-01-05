package com.rojoma.json
package io

sealed abstract class JsonEvent
case object StartOfObjectEvent extends JsonEvent
case object EndOfObjectEvent extends JsonEvent
case object StartOfArrayEvent extends JsonEvent
case object EndOfArrayEvent extends JsonEvent
case class FieldEvent(name: String) extends JsonEvent
case class IdentifierEvent(text: String) extends JsonEvent
case class NumberEvent(number: BigDecimal) extends JsonEvent
case class StringEvent(string: String) extends JsonEvent

case class PositionedJsonEvent(event: JsonEvent, row: Int, column: Int)

class JsonEventIterator(input: Iterator[PositionedJsonToken]) extends BufferedIterator[PositionedJsonEvent] {
  import JsonEventIterator._

  private val underlying = input.buffered
  private var stack = new StateStack
  private var available: PositionedJsonEvent = null
  private var atTop = true

  def hasNext: Boolean = {
    if(available != null) {
      true
    } else {
      atTop = stack.isEmpty
      if(underlying.hasNext) {
        if(atTop) stack.push(AwaitingDatum) // start of new top-level object
        do {
          val token = underlying.next()
          available = stack.pop.handle(token, stack)
        } while(available == null && !stack.isEmpty)
      }
      available != null
    }
  }

  def head = {
    if(available == null && !hasNext) {
      underlying.next() // force NoSuchElementException
    }
    available
  }

  def next() = {
    val result = head
    available = null
    result
  }

  /**
   * Finish reading the "current" object or list, where "current" is
   * defined as "the most recent compound object started by `next()`.
   * If a top-level object has not been started, this does nothing.
   *
   * Throws `JsonEOF` if the end-of-input occurs before finishing
   * this object.
   */
  def skipRestOfCompound(): this.type= {
    hasNext // hasNext to make sure atTop is in an accurate state
    if(!atTop) {
      try {
        var count = 0
        do {
          val ev = next().event
          ev match {
            case StartOfObjectEvent | StartOfArrayEvent => count += 1
            case EndOfObjectEvent | EndOfArrayEvent => count -= 1
            case _ => /* nothing */
          }
        } while(count >= 0)
      } catch {
        case NoSuchTokenException(r,c) => throw JsonEOF(r,c)
        case _: NoSuchElementException => throw JsonEOF(-1, -1)
      }
    }
    this
  }

  @inline
  final def dropRestOfCompound() = skipRestOfCompound()

  /** Skips the next datum that would be returned entirely.  If the next event
   * is the start of a list or object, `skipRestOfCompound()` is called to
   * pass over it. If it's a field event, the field and its associated value
   * are skipped. If it's the end of a list or object, no position change is
   * made and the next call to `head` or `next()` will still return the end
   * event.  Otherwise, it's an atom and is consumed.
   *
   * If the iterator is empty at the start of this call, `NoSuchElementException`
   * is raised.  If it runs out while skipping the datum, `JsonEOF` is raised.
   */
  def skipNextDatum(): this.type = head.event match {
    case StartOfObjectEvent | StartOfArrayEvent =>
      next()
      skipRestOfCompound()
    case FieldEvent(_) =>
      next()
      skipNextDatum()
    case EndOfObjectEvent | EndOfArrayEvent =>
      this
    case _ =>
      next()
      this
  }

  @inline
  final def dropNextDatum() = skipNextDatum()
}

object JsonEventIterator {
  private type StateStack = scala.collection.mutable.ArrayStack[State]

  private abstract class State {
    protected def error(got: PositionedJsonToken, expected: String): Nothing =
      throw JsonUnexpectedToken(got.token, expected, got.row, got.column)

    protected def p(token: PositionedJsonToken, ev: JsonEvent) =
      PositionedJsonEvent(ev, token.row, token.column)

    def handle(token: PositionedJsonToken, stack: StateStack): PositionedJsonEvent
  }

  private val AwaitingDatum: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenOpenBrace =>
        stack.push(AwaitingFieldNameOrEndOfObject)
        p(token, StartOfObjectEvent)
      case TokenOpenBracket =>
        stack.push(AwaitingEntryOrEndOfArray)
        p(token, StartOfArrayEvent)
      case TokenIdentifier(text) =>
        p(token, IdentifierEvent(text))
      case TokenNumber(number) =>
        p(token, NumberEvent(number))
      case TokenString(string) =>
        p(token, StringEvent(string))
      case _ =>
        error(token, "datum")
    }
  }

  private val AwaitingEntryOrEndOfArray: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenOpenBrace =>
        stack.push(AwaitingCommaOrEndOfArray)
        stack.push(AwaitingFieldNameOrEndOfObject)
        p(token, StartOfObjectEvent)
      case TokenOpenBracket =>
        stack.push(AwaitingCommaOrEndOfArray)
        stack.push(AwaitingEntryOrEndOfArray)
        p(token, StartOfArrayEvent)
      case TokenIdentifier(text) =>
        stack.push(AwaitingCommaOrEndOfArray)
        p(token, IdentifierEvent(text))
      case TokenNumber(number) =>
        stack.push(AwaitingCommaOrEndOfArray)
        p(token, NumberEvent(number))
      case TokenString(string) =>
        stack.push(AwaitingCommaOrEndOfArray)
        p(token, StringEvent(string))
      case TokenCloseBracket =>
        p(token, EndOfArrayEvent)
      case _ =>
        error(token, "datum or end of list")
    }
  }

  private val AwaitingCommaOrEndOfArray: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenComma =>
        stack.push(AwaitingCommaOrEndOfArray)
        stack.push(AwaitingDatum)
        null
      case TokenCloseBracket =>
        p(token, EndOfArrayEvent)
      case _ =>
        error(token, "comma or end of list")
    }
  }

  private val AwaitingFieldNameOrEndOfObject: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenCloseBrace =>
        p(token, EndOfObjectEvent)
      case TokenString(text) =>
        stack.push(AwaitingCommaOrEndOfObject)
        stack.push(AwaitingKVSep)
        p(token, FieldEvent(text))
      case TokenIdentifier(text) =>
        stack.push(AwaitingCommaOrEndOfObject)
        stack.push(AwaitingKVSep)
        p(token, FieldEvent(text))
      case _ =>
        error(token, "field name or end of object")
    }
  }

  private val AwaitingFieldName: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenString(text) =>
        stack.push(AwaitingKVSep)
        p(token, FieldEvent(text))
      case TokenIdentifier(text) =>
        stack.push(AwaitingKVSep)
        p(token, FieldEvent(text))
      case _ =>
        error(token, "field name")
    }
  }

  private val AwaitingKVSep: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenColon =>
        stack.push(AwaitingDatum)
        null
      case _ =>
        error(token, "colon")
    }
  }

  private val AwaitingCommaOrEndOfObject: State = new State {
    def handle(token: PositionedJsonToken, stack: StateStack) = token.token match {
      case TokenComma =>
        stack.push(AwaitingCommaOrEndOfObject)
        stack.push(AwaitingFieldName)
        null
      case TokenCloseBrace =>
        p(token, EndOfObjectEvent)
      case _ =>
        error(token, "comma or end of object")
    }
  }
}
