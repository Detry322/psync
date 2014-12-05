package round.macros

import round.formula._
import round.logic._
import dzufferey.utils.Namer

trait Serialization {
  self: Impl =>
  import c.universe._

  //$tpt -> protected def serialize(payload: $tpt, out: _root_.io.netty.buffer.ByteBuf, withLength: Boolean = true, offset: Int = 8): Int
  //$tpt -> protected def deserialize(in: _root_.io.netty.buffer.ByteBuf, withLength: Boolean = true, offset: Int = 8): $tpt
  
  
  def picklingIO(tpt: Tree) = List(
      q"""protected def serialize(payload: $tpt, out: java.nio.ByteBuffer) {
        import scala.pickling._
        import binary._
        val bytes0 = payload.pickle.value
        val length = bytes0.length
        out.put(bytes0)
      }""",
      q"""protected def deserialize(in: java.nio.ByteBuffer): $tpt = {
        import scala.pickling._
        import binary._
        val length = in.remaining()
        val bytes = Array.ofDim[Byte](length)
        in.get(bytes)
        BinaryPickle(bytes).unpickle[$tpt]
      }"""
    )

  def primitiveIO(tpt: Tree, write: List[Tree], read: List[Tree]) = List(
      q"""protected def serialize(payload: $tpt, out: java.nio.ByteBuffer) {
        ..$write
      }""",
      q"""protected def deserialize(in: java.nio.ByteBuffer): $tpt = {
        ..$read
      }"""
  )

  def primitiveType(t: Type) = {
    import definitions._
    t =:= BooleanTpe ||
    t =:= ByteTpe ||
    t =:= CharTpe ||
    t =:= ShortTpe ||
    t =:= IntTpe ||
    t =:= LongTpe ||
    t =:= FloatTpe ||
    t =:= DoubleTpe
  }

  def primitiveRead(t: Type): Tree = {
    import definitions._
    if (t =:= BooleanTpe) {
      q"in.get() != (0: Byte)"
    } else if (t =:= ByteTpe) {
      q"in.get()"
    } else if (t =:= CharTpe) {
      q"in.getChar()"
    } else if (t =:= ShortTpe) {
      q"in.getShort()"
    } else if (t =:= IntTpe) {
      q"in.getInt()"
    } else if (t =:= LongTpe) {
      q"in.getLong()"
    } else if (t =:= FloatTpe) {
      q"in.getFloat()"
    } else if (t =:= DoubleTpe) {
      q"in.getDouble()"
    } else {
      sys.error("not primitive")
    }
  }
 
  def primitiveWrite(t: Type, value: Tree): Tree = {
    import definitions._
    if (t =:= BooleanTpe) {
      q"if ($value) out.put(1: Byte) else out.put(0: Byte)"
    } else if (t =:= ByteTpe) {
      q"out.put($value)"
    } else if (t =:= CharTpe) {
      q"out.putChar($value)"
    } else if (t =:= ShortTpe) {
      q"out.putShort($value)"
    } else if (t =:= IntTpe) {
      q"out.putInt($value)"
    } else if (t =:= LongTpe) {
      q"out.putLong($value)"
    } else if (t =:= FloatTpe) {
      q"out.putFloat($value)"
    } else if (t =:= DoubleTpe) {
      q"out.putDouble($value)"
    } else {
      sys.error("not primitive")
    }
  }

  def tupleWrite(args: List[Type], value: Tree): List[Tree] = {
    val name = Array(TermName("_1"), TermName("_2"), TermName("_3"), TermName("_4"))
    val res = args.zipWithIndex.map{ case (t, idx) => val m = name(idx); primitiveWrite(t, q"$value.$m") }
    //println("tupleWrite: " + res.mkString(", "))
    res
  }

  def tupleRead(args: List[Type]): List[Tree] = {
    val name = Array(q"_1", q"_2", q"_3", q"_4")
    val (reads, vars) = args.map( t => {
      val v = TermName(c.freshName("tmp"))
      val r = primitiveRead(t) 
      (ValDef(Modifiers(), v, TypeTree(t), r), Ident(v))
    }).unzip
    val res = reads ::: List(q"(..$vars)")
    //println("tupleRead: " + res.mkString(", "))
    res
  }

  def serializationMethods(tpt: Tree): List[Tree] = {
    val t = tpt.tpe
    if (primitiveType(t)) {
      val wr = List(primitiveWrite(t, q"payload"))
      val rd = List(primitiveRead(t))
      primitiveIO(tpt, wr, rd)
    } else {
      t match {
        case IsTuple(args) if args.forall(primitiveType) =>
          val wr = tupleWrite(args, q"payload")
          val rd = tupleRead(args)
          primitiveIO(tpt, wr, rd)
        case _ =>
          //TODO string
          //TODO options
          println("using pickling on " + showRaw(tpt))
          picklingIO(tpt)
      }
    }
  }

}
