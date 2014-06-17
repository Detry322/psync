package round.formula

import scala.collection.immutable.{Set => SSet}

//TODO what about some way to do something with the formula (+, -, *, &&, ||, ^, ...)
//TODO substitution of variables
//TODO look at the gist.logic for some example (also type checking)

sealed abstract class Formula {

  def toStringFull = "(" + toString + ": " + tpe + ")"

  var tpe: Type = Wildcard
  //TODO type checking

  def setType(t: Type): this.type = {
    tpe = t
    this
  }

  def alpha(map: Map[Variable, Variable]): Formula

  val freeVariables: SSet[Variable]
  val boundVariables: SSet[Variable]
}

object Formula {

  protected def flatten1(i: InterpretedFct, f: Formula): List[Formula] = f match {
    case Application(`i`, lst) => lst.flatMap(flatten1(i, _))
    case Application(other, lst) => List(Application(other, lst map flatten))
    case Binding(b, v, f) => List(Binding(b, v, flatten(f)))
    case other => List(other)
  }

  def flatten(f: Formula): Formula = f match {
    case Application(Plus, lst) => Application(Plus, lst.flatMap(flatten1(Plus, _)))
    case Application(And, lst) => Application(And, lst.flatMap(flatten1(And, _)))
    case Application(Or, lst) => Application(Or, lst.flatMap(flatten1(Or, _)))
    case Application(other, lst) => Application(other, lst map flatten)
    case Binding(b, v, f) => Binding(b, v, flatten(f))
    case other => other
  }
}

case class Literal[T](value: T) extends Formula {

  override def toString = value.toString

  def alpha(map: Map[Variable, Variable]) = this
  lazy val freeVariables = SSet[Variable]()
  lazy val boundVariables = SSet[Variable]()

}

case class Variable(name: String) extends Formula {

  override def toString = name

  def alpha(map: Map[Variable, Variable]) = map.getOrElse(this, this)
  lazy val freeVariables = SSet[Variable](this)
  lazy val boundVariables = SSet[Variable]()

}

case class Application(fct: Symbol, args: List[Formula]) extends Formula {

  override def toString = fct.toString + args.mkString("(",", ",")")

  def alpha(map: Map[Variable, Variable]) = Application(fct, args.map(_.alpha(map)))
  lazy val freeVariables = (SSet[Variable]() /: args)(_ ++ _.freeVariables)
  lazy val boundVariables = (SSet[Variable]() /: args)(_ ++ _.boundVariables)

}

sealed abstract class Symbol {
  def tpe: Type
}

case class UninterpretedFct(symbol: String) extends Symbol {
  def tpe = UnInterpreted(symbol)
}

sealed abstract class InterpretedFct(symbol: String, aliases: String*) extends Symbol {
  def apply(arg: Formula, args: Formula*): Formula = {
    sys.error("TODO")
  }
}

case object Not extends InterpretedFct("¬", "~", "!") {
  def tpe = Bool ~> Bool
}

case object And extends InterpretedFct("∧", "&&") {
  def tpe = Bool ~> Bool ~> Bool
}
case object Or extends InterpretedFct("∨", "||") {
  def tpe = Bool ~> Bool ~> Bool
}
case object Implies extends InterpretedFct("⇒", "=>") {
  def tpe = Bool ~> Bool ~> Bool
}

case object Eq extends InterpretedFct("=", "⇔") {
  def tpe = {
    val fv = Type.freshTypeVar
    fv ~> fv ~> Bool
  }
}
case object Neq extends InterpretedFct("≠", "!=", "~=") {
  def tpe = {
    val fv = Type.freshTypeVar
    fv ~> fv ~> Bool
  }
}

case object Plus extends InterpretedFct("+") {
  def tpe = Int ~> Int ~> Int
}
case object Minus extends InterpretedFct("-") {
  def tpe = Int ~> Int ~> Int
}
case object Times extends InterpretedFct("∙", "*") {
  def tpe = Int ~> Int ~> Int
}
case object Divides extends InterpretedFct("/") {
  def tpe = Int ~> Int ~> Int
}

case object Leq extends InterpretedFct("≤", "<=") {
  def tpe = Int ~> Int ~> Bool
}
case object Geq extends InterpretedFct("≥", ">=") {
  def tpe = Int ~> Int ~> Bool
}
case object Lt extends InterpretedFct("<") {
  def tpe = Int ~> Int ~> Bool
}
case object Gt extends InterpretedFct(">") {
  def tpe = Int ~> Int ~> Bool
}

case object Union extends InterpretedFct("∪") {
  def tpe = {
    val fv = Type.freshTypeVar
    Set(fv) ~> Set(fv) ~> Set(fv)
  }
}

case object Intersection extends InterpretedFct("∩") {
  def tpe = {
    val fv = Type.freshTypeVar
    Set(fv) ~> Set(fv) ~> Set(fv)
  }
}

case object SubsetEq extends InterpretedFct("⊆") {
  def tpe = {
    val fv = Type.freshTypeVar
    Set(fv) ~> Set(fv) ~> Bool
  }
}

case object SupersetEq extends InterpretedFct("⊇") {
  def tpe = {
    val fv = Type.freshTypeVar
    Set(fv) ~> Set(fv) ~> Bool
  }
}

case object In extends InterpretedFct("∈", "in") {
  def tpe = {
    val fv = Type.freshTypeVar
    fv ~> Set(fv) ~> Bool
  }
}

case object Contains extends InterpretedFct("∋") {
  def tpe = {
    val fv = Type.freshTypeVar
    Set(fv) ~> fv ~> Bool
  }
}

case object Cardinality extends InterpretedFct("card") {
  def tpe = {
    val fv = Type.freshTypeVar
    Set(fv) ~> Int
  }
}


sealed abstract class BindingType

case class Binding(binding: BindingType, vs: List[Variable], f: Formula) extends Formula {

  override def toString = binding + " " + vs.mkString(""," ","") + ". " + f

  def alpha(map: Map[Variable, Variable]) = Binding(binding, vs, f.alpha(map -- vs))
  lazy val freeVariables = f.freeVariables -- vs
  lazy val boundVariables = f.boundVariables ++ vs

}

case object ForAll extends BindingType {
  def unapply(f: Formula): Option[(List[Variable],Formula)] = f match {
    case Binding(ForAll, v, f) => Some(v,f)
    case _ => None
  }
  def apply(vs:List[Variable], f: Formula) = {
    val fa = Binding(ForAll, vs, f)
    fa.tpe = Bool
    fa
  }
}
case object Exists extends BindingType {
  def unapply(f: Formula): Option[(List[Variable],Formula)] = f match {
    case Binding(Exists, v, f) => Some(v,f)
    case _ => None
  }
  def apply(vs:List[Variable], f: Formula) = {
    val ex = Binding(Exists, vs, f)
    ex.tpe = Bool
    ex
  }
}
