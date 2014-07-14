package com.rojoma.json.v3
package util

/** Specifies the mechanism for distinguishing among subclasses in a hierarchy with a tag.
 * @see [[com.rojoma.json.v3.util.NoTag]] */
sealed abstract class TagType

/** Specifies that the base codec should add (and possibly remove) an extra field
 * to the objects generated by the subclasses' [[com.rojoma.json.v3.codec.JsonEncode]]s and
 * [[com.rojoma.json.v3.codec.JsonDecode]]s (and they must be objects).
 *
 * @example {{{
 * abstract class Base
 * case class SubclassA(name: String) extends Base
 * case class SubclassB(x: Int, y: Int) extends Base
 *
 * implicit val aCodec = SimpleJsonCodecBuilder[SubclassA].build("name", _.name)
 * implicit val bCodec = SimpleJsonCodecBuilder[SubclassB].build("x", _.x, "y", _.y)
 *
 * val baseCodec = SimpleHierarchyCodecBuilder[Base](InternalTag("type")).
 *    branch[SubclassA]("a").
 *    branch[SubclassB]("b").
 *    build
 *
 * println(baseCodec.encode(SubclassA("John"))) // { "type" : "a", "name" : "John" }
 * println(baseCodec.encode(SubclassB(1, 2))) // { "type" : "b", "x" : 1, "y" : 2 }
 * }}}
 */
case class InternalTag(fieldName: String, removeTagForSubcodec: Boolean = true) extends TagType

/** Specifies that the base codec should wrap the value generated by subclasses'
 * [[com.rojoma.json.v3.codec.JsonEncode]]s and [[com.rojoma.json.v3.codec.JsonDecode]]s
 * in another object containing a single field, which is the tag for that subclass.
 *
 * @example {{{
 * abstract class Base
 * case class SubclassA(name: String) extends Base
 * case class SubclassB(x: Int, y: Int) extends Base
 *
 * implicit val aCodec = SimpleJsonCodecBuilder[SubclassA].build("name", _.name)
 * implicit val bCodec = SimpleJsonCodecBuilder[SubclassB].build("x", _.x, "y", _.y)
 *
 * val baseCodec = SimpleHierarchyCodecBuilder[Base](TagToValue).
 *    branch[SubclassA]("a").
 *    branch[SubclassB]("b").
 *    build
 *
 * println(baseCodec.encode(SubclassA("John"))) // { "a" : { "name" : "John" } }
 * println(baseCodec.encode(SubclassB(1, 2))) // { "b" : { "x" : 1, "y" : 2 } }
 * }}}
 */
case object TagToValue extends TagType

/** Specifies that the base codec should wrap the value generated by subclasses'
 * [[com.rojoma.json.v3.codec.JsonEncode]]s and [[com.rojoma.json.v3.codec.JsonDecode]]s
 * in another object containing two fields; one for the type-tag and one for the actual
 * value.
 *
 * @example {{{
 * abstract class Base
 * case class SubclassA(name: String) extends Base
 * case class SubclassB(x: Int, y: Int) extends Base
 *
 * implicit val aCodec = SimpleJsonCodecBuilder[SubclassA].build("name", _.name)
 * implicit val bCodec = SimpleJsonCodecBuilder[SubclassB].build("x", _.x, "y", _.y)
 *
 * val baseCodec = SimpleHierarchyCodecBuilder[Base](TagAndValue("type", "value")).
 *    branch[SubclassA]("a").
 *    branch[SubclassB]("b").
 *    build
 *
 * println(baseCodec.encode(SubclassA("John"))) // { "type" : "a", "value" : { "name" : "John" } }
 * println(baseCodec.encode(SubclassB(1, 2))) // { "type" : "b", "value" : { "x" : 1, "y" : 2 } }
 * }}}
 */
case class TagAndValue(typeField: String, valueField: String) extends TagType {
  if(typeField == valueField) throw new IllegalArgumentException("type field and value field must be different")
}

/** Specifies that the base codec should not affect the subclasses'
 * [[com.rojoma.json.v3.codec.JsonEncode]]s and [[com.rojoma.json.v3.codec.JsonDecode]]s
 * at all and that the decoder should simply try each codec in turn, in the order they
 * were provided to the builder, until it finds one that succeeds.
 *
 * @example {{{
 * abstract class Base
 * case class SubclassA(name: String) extends Base
 * case class SubclassB(x: Int, y: Int) extends Base
 *
 * implicit val aCodec = SimpleJsonCodecBuilder[SubclassA].build("name", _.name)
 * implicit val bCodec = SimpleJsonCodecBuilder[SubclassB].build("x", _.x, "y", _.y)
 *
 * val baseCodec = SimpleHierarchyCodecBuilder[Base](NoTag).
 *    branch[SubclassA].
 *    branch[SubclassB].
 *    build
 *
 * println(baseCodec.encode(SubclassA("John"))) // { "name" : "John" }
 * println(baseCodec.encode(SubclassB(1, 2))) // { "x" : 1, "y" : 2 }
 * }}}
 *
 * @see [[com.rojoma.json.v3.util.TagType]]
 */
sealed abstract class NoTag
case object NoTag extends NoTag

