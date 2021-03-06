package com.rojoma.json.v3
package `-impl`.util

import scala.collection.mutable

import codec._
import util.{JsonKey, JsonKeyStrategy, Strategy, LazyCodec, NullForNone}

import MacroCompat._

abstract class AutomaticJsonCodecBuilderImpl[T] extends MacroCompat with MacroCommon {
  val c: Context
  import c.universe._
  implicit val Ttag: c.WeakTypeTag[T]

  private val T = weakTypeOf[T]
  private val Tname = TypeTree(T)

  private def identityStrat(x: String) = x
  private def underscoreStrat(x: String) = CamelSplit(x).map(_.toLowerCase).mkString("_")

  private def freshTermName(): TermName = toTermName(c.freshName())

  private def nameStrategy(thing: Symbol, default: String => String): String => String = {
    checkAnn(thing, typeOf[JsonKeyStrategy])
    thing.annotations.reverse.find { ann => isType(ann.tree.tpe, typeOf[JsonKeyStrategy]) } match {
      case Some(ann) =>
        findValue(ann) match {
          case Some(strat : Symbol) =>
            try {
              Strategy.valueOf(strat.name.decodedName.toString) match {
                case Strategy.Identity =>
                  identityStrat _
                case Strategy.Underscore =>
                  underscoreStrat _
              }
            } catch {
              case _: IllegalArgumentException =>
                default
            }
          case _ =>
            default
        }
      case None =>
        default
    }
  }

  private val defaultNameStrategy = nameStrategy(T.typeSymbol, identityStrat)

  // since v2 and v3 share the same names for their annotations, warn if we find one that isn't
  // the same type but is the same name and don't find one that IS the right type.
  def checkAnn(param: Symbol, t: Type) {
    param.annotations.find { ann => ann.tree.tpe.erasure.typeSymbol.name == t.typeSymbol.name && !isType(ann.tree.tpe, t) }.foreach { ann =>
      if(!param.annotations.exists { ann => isType(ann.tree.tpe, t) }) {
        c.warning(param.pos, "Found non-v3 `" + t.typeSymbol.name.decodedName + "' annotation; did you accidentally import v2's?")
      }
    }
  }

  private def hasLazyAnnotation(param: TermSymbol) = {
    checkAnn(param, typeOf[LazyCodec])
    param.annotations.exists { ann => isType(ann.tree.tpe, typeOf[LazyCodec]) }
  }

  private def computeJsonName(param: TermSymbol): String = {
    var name = nameStrategy(param, defaultNameStrategy)(param.name.decodedName.toString)
    checkAnn(param, typeOf[JsonKey])
    for(ann <- param.annotations if isType(ann.tree.tpe, typeOf[JsonKey]))
      findValue(ann) match {
        case Some(s: String) =>
          name = s
        case _ =>
          // pass
      }
    name
  }

  private def findAccessor(param: TermSymbol) =
    param.name.asInstanceOf[TermName]

  private def findCodecType(param: TermSymbol) = {
    val tpe = param.typeSignature.asSeenFrom(T, T.typeSymbol)
    if(isType(tpe.erasure, typeOf[Option[_]].erasure)) {
      val TypeRef(_, _, c) = tpe
      c.head
    } else {
      tpe
    }
  }
  private def isOption(param: TermSymbol) = {
    val tpe = param.typeSignature.asSeenFrom(T, T.typeSymbol)
    isType(tpe.erasure, typeOf[Option[_]].erasure)
  }
  private def hasNullForNameAnnotation(param: TermSymbol) = {
    checkAnn(param, typeOf[NullForNone])
    param.annotations.exists(ann => isType(ann.tree.tpe, typeOf[NullForNone]))
  }

  private case class FieldInfo(encName: TermName, decName: TermName, isLazy: Boolean, jsonName: String, accessorName: TermName, missingMethodName: TermName, errorAugmenterMethodName: TermName, codecType: Type, isOption: Boolean, isNullForNone: Boolean)

  private val fieldss = locally {
    val seenNames = new mutable.HashSet[String]
    val buffer = new mutable.ListBuffer[List[FieldInfo]]
    for {
      member <- T.members
      if member.isMethod && member.asMethod.isPrimaryConstructor
    } {
      val mem = member.asMethod
      if(mem.owner == T.typeSymbol) {
        for {
          params <- mem.paramLists
        } {
          if(params.isEmpty || !params.head.asTerm.isImplicit) {
            val fieldList =
              for { rawParam <- params }
              yield {
                val param = rawParam.asTerm
                val name = computeJsonName(param)
                if(seenNames(name)) {
                  c.abort(param.pos, s"The name `$name' is already used by the codec for $Tname")
                } else seenNames += name
                FieldInfo(
                  freshTermName(),
                  freshTermName(),
                  hasLazyAnnotation(param),
                  name,
                  findAccessor(param),
                  freshTermName(),
                  freshTermName(),
                  findCodecType(param),
                  isOption(param),
                  hasNullForNameAnnotation(param)
                )
              }
            buffer += fieldList
          }
        }
      }
    }
    buffer.toList
  }
  private val fields = fieldss.flatten

  // three names for temporary variables used during the encoding/decoding process
  private val tmp = freshTermName()
  private val tmp2 = freshTermName()
  private val tmp3 = freshTermName()

  // the name of the thing being encoded or decoded
  private val param = freshTermName()

  private def encodes = fields.map { fi =>
    val enc = q"_root_.com.rojoma.json.v3.codec.JsonEncode[${TypeTree(fi.codecType)}]"
    if(fi.isLazy) {
      q"private[this] lazy val ${fi.encName} = $enc"
    } else {
      q"private[this] val ${fi.encName} = $enc"
    }
  }

  private def encoder = locally {
    val encoderMap = freshTermName()
    def encoderMapUpdates = for(fi <- fields) yield {
      if(fi.isOption) {
        if(fi.isNullForNone) {
          q"""$encoderMap(${fi.jsonName}) = {
                val $tmp = $param.${fi.accessorName}
                if($tmp.isInstanceOf[_root_.scala.Some[_]]) ${fi.encName}.encode($tmp.get)
                else _root_.com.rojoma.json.v3.ast.JNull
              }"""
        } else {
          q"""val $tmp = $param.${fi.accessorName}
              if($tmp.isInstanceOf[_root_.scala.Some[_]]) $encoderMap(${fi.jsonName}) = ${fi.encName}.encode($tmp.get)"""
        }
      } else {
        q"$encoderMap(${fi.jsonName}) = ${fi.encName}.encode($param.${fi.accessorName})"
      }
    }

    q"""def encode($param: $Tname) = {
          val $encoderMap = new _root_.scala.collection.mutable.LinkedHashMap[_root_.scala.Predef.String, _root_.com.rojoma.json.v3.ast.JValue]
          ..$encoderMapUpdates
          _root_.com.rojoma.json.v3.ast.JObject($encoderMap)
        }"""
  }

  private def decodes = fields.map { fi =>
    val dec = q"_root_.com.rojoma.json.v3.codec.JsonDecode[${TypeTree(fi.codecType)}]"
    if(fi.isLazy) {
      q"private[this] lazy val ${fi.decName} = $dec"
    } else {
      q"private[this] val ${fi.decName} = $dec"
    }
  }

  private def missingMethods: List[DefDef] = fields.filter(!_.isOption).map { fi =>
    q"""private[this] def ${fi.missingMethodName}: _root_.scala.Left[_root_.com.rojoma.json.v3.codec.DecodeError, _root_.scala.Nothing] =
          _root_.scala.Left(_root_.com.rojoma.json.v3.codec.DecodeError.MissingField(${fi.jsonName},
                            _root_.com.rojoma.json.v3.codec.Path.empty))"""
  }

  private def errorAugmenterMethods: List[DefDef] = fields.map { fi =>
    q"""private[this] def ${fi.errorAugmenterMethodName}($tmp3 : _root_.scala.Either[_root_.com.rojoma.json.v3.codec.DecodeError, _root_.scala.Any]): _root_.scala.Left[_root_.com.rojoma.json.v3.codec.DecodeError, _root_.scala.Nothing] =
         _root_.scala.Left($tmp3.asInstanceOf[_root_.scala.Left[_root_.com.rojoma.json.v3.codec.DecodeError, _root_.scala.Any]].a.augment(_root_.com.rojoma.json.v3.codec.Path.Field(${fi.jsonName})))"""
  }

  def decoder = locally {
    val obj = freshTermName()
    val tmps = fieldss.map { _.map { _ => freshTermName() } }

    val decoderMapExtractions: List[ValDef] = for((fi,tmp) <- fields.zip(tmps.flatten)) yield {
      val expr = if(fi.isOption) {
        q"""val $tmp2 = $obj.get(${fi.jsonName})
            if($tmp2.isInstanceOf[_root_.scala.Some[_]]) {
              val $tmp3 = ${fi.decName}.decode($tmp2.get)
              if($tmp3.isInstanceOf[_root_.scala.Right[_,_]]) Some($tmp3.asInstanceOf[_root_.scala.Right[_root_.scala.Any, ${TypeTree(fi.codecType)}]].b)
              else if(_root_.com.rojoma.json.v3.ast.JNull == $tmp2.get) _root_.scala.None
              else return ${fi.errorAugmenterMethodName}($tmp3)
            } else _root_.scala.None"""
      } else {
        q"""val $tmp2 = ${obj}.get(${fi.jsonName})
            if($tmp2.isInstanceOf[_root_.scala.Some[_]]) {
              val $tmp3 = ${fi.decName}.decode($tmp2.get)
              if($tmp3.isInstanceOf[_root_.scala.Right[_,_]]) $tmp3.asInstanceOf[_root_.scala.Right[_root_.scala.Any, ${TypeTree(fi.codecType)}]].b
              else return ${fi.errorAugmenterMethodName}($tmp3)
            } else return ${fi.missingMethodName}"""
      }
      q"val $tmp = $expr"
    }
    val create = // not sure how to do this with quasiquote...
      tmps.foldLeft(Select(New(TypeTree(T)), toTermName("<init>")) : Tree) { (seed, arglist) =>
        Apply(seed, arglist.map(Ident(_)))
      }

    q"""def decode($param: _root_.com.rojoma.json.v3.ast.JValue): _root_.scala.Either[_root_.com.rojoma.json.v3.codec.DecodeError, $Tname] =
          if($param.isInstanceOf[_root_.com.rojoma.json.v3.ast.JObject]) {
            val $obj = $param.asInstanceOf[_root_.com.rojoma.json.v3.ast.JObject].fields
            ..$decoderMapExtractions
            _root_.scala.Right($create)
          } else {
            _root_.scala.Left(_root_.com.rojoma.json.v3.codec.DecodeError.InvalidType(
              _root_.com.rojoma.json.v3.ast.JObject,
              $param.jsonType,
              _root_.com.rojoma.json.v3.codec.Path.empty))
          }"""
  }

  private def encode: c.Expr[JsonEncode[T]] = {
    val tree =
      q"""(new _root_.com.rojoma.json.v3.codec.JsonEncode[$Tname] {
            ..$encodes
            $encoder
            override def toString = ${"#<JsonEncode for " + T.toString + ">"}
          }) : _root_.com.rojoma.json.v3.codec.JsonEncode[$Tname]"""

    // println(tree)

    c.Expr[JsonEncode[T]](tree)
  }

  private def decode: c.Expr[JsonDecode[T]] = {
    val tree =
      q"""(new _root_.com.rojoma.json.v3.codec.JsonDecode[$Tname] {
            ..$decodes
            ..$missingMethods
            ..$errorAugmenterMethods
            $decoder
            override def toString = ${"#<JsonDecode for " + T.toString + ">"}
          }) : _root_.com.rojoma.json.v3.codec.JsonDecode[$Tname]"""

    // println(tree)

    c.Expr[JsonDecode[T]](tree)
  }

  private def codec: c.Expr[JsonEncode[T] with JsonDecode[T]] = {
    val encode = toTermName("encode")
    val decode = toTermName("decode")
    val x = toTermName("x")

    val tree = q"""(new _root_.com.rojoma.json.v3.codec.JsonEncode[$Tname] with _root_.com.rojoma.json.v3.codec.JsonDecode[$Tname] {
                     ..$encodes
                     ..$decodes
                     ..$missingMethods
                     ..$errorAugmenterMethods
                     $encoder
                     $decoder
                     override def toString = ${"#<JsonCodec for " + T.toString + ">"}
                   }) : _root_.com.rojoma.json.v3.codec.JsonEncode[$Tname] with _root_.com.rojoma.json.v3.codec.JsonDecode[$Tname]"""

    // println(tree)

    c.Expr[JsonEncode[T] with JsonDecode[T]](tree)
  }
}

object AutomaticJsonCodecBuilderImpl {
  def encode[T : ctx.WeakTypeTag](ctx: Context): ctx.Expr[JsonEncode[T]] = {
    val b = new {
      val c: ctx.type = ctx
      val Ttag = implicitly[c.WeakTypeTag[T]]
    } with AutomaticJsonCodecBuilderImpl[T]
    b.encode
  }

  def decode[T : ctx.WeakTypeTag](ctx: Context): ctx.Expr[JsonDecode[T]] = {
    val b = new {
      val c: ctx.type = ctx
      val Ttag = implicitly[c.WeakTypeTag[T]]
    } with AutomaticJsonCodecBuilderImpl[T]
    b.decode
  }

  def codec[T : ctx.WeakTypeTag](ctx: Context): ctx.Expr[JsonEncode[T] with JsonDecode[T]] = {
    val b = new {
      val c: ctx.type = ctx
      val Ttag = implicitly[c.WeakTypeTag[T]]
    } with AutomaticJsonCodecBuilderImpl[T]
    b.codec
  }
}

