package round.logic

import round.formula._

import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._
import dzufferey.utils.Namer

//the definition of a comprehension
//TODO trim the scope when the body is defined
case class SetDef(scope: Set[Variable], id: Formula, body: Option[Binding]) {
    
  Logger.assert(id.tpe match { case FSet(_) => true; case _ => false }, "SetDef", "SetDef had not FSet type: " + id.tpe + "\n" + id + " = " + body)

  def tpe = id.tpe

  def contentTpe = tpe match {
    case FSet(t) => t
    case other => Logger.logAndThrow("SetDef", Error, "SetDef had not FSet type: " + other + "\n" + id + " = " + body)
  }

  def fresh: SetDef = {
    val newScope = scope.map(v => v -> Variable(Namer(v.name)).setType(v.tpe)).toMap
    val scope1 = newScope.values.toSet
    val id1 = FormulaUtils.alpha(newScope, id)
    val body1 = body.map(FormulaUtils.alpha(newScope, _).asInstanceOf[Binding])
    SetDef(scope1, id1, body1)
  }

  //TODO also take the scope in the normalization
  def normalize: SetDef = {
    val newBody = body.map( Simplify.deBruijnIndex(_).asInstanceOf[Binding] )
    val n = SetDef(scope, id, newBody)
    //Logger("SetDef", Debug, "before: " + this)
    //Logger("SetDef", Debug, "after:  " + n)
    n
  }

  def ccNormalize(cClasses: CongruenceClasses): SetDef = {
    val n1 = normalize
    val newId = if (n1.scope.isEmpty) cClasses.normalize(n1.id) else n1.id
    val newBody = n1.body.map( cClasses.normalize(_).asInstanceOf[Binding] )
    SetDef(n1.scope, newId, newBody)
  }

  //assume normalized
  def similar(sd: SetDef) = {
    scope == sd.scope && body.isDefined && body == sd.body
  }

}

object SetDef {

  protected def normalizeSetBody( sDefs: Iterable[SetDef],
                                  cClasses: CongruenceClasses
                                ): (List[SetDef], Map[Formula, Formula]) = {
    val init = (List[SetDef](), Map[Formula,Formula]())
    sDefs.foldLeft(init)( (acc, d0) => {
      val d = d0.ccNormalize(cClasses)
      val (ds, subst) = acc
      ds.find(_.similar(d)) match {
        case Some(s) => (ds, subst + (d.id -> s.id))
        case None => (d :: ds, subst)
      }
    })
  }

  def normalize(sDefs: Iterable[SetDef],
                cClasses: CongruenceClasses = new CongruenceClasses(Nil, Map.empty) 
               ): (List[SetDef], Map[Formula, Formula]) = {
    normalizeSetBody(sDefs, cClasses)
  }

  //assume normalized
  protected def merge(s1: SetDef, s2: SetDef): SetDef = {
    if (s1.body.isDefined && s2.body.isDefined) {
      val scope = s1.scope ++ s2.scope
      val body = (s1.body.get, s2.body.get) match {
        case (Comprehension(List(i1), b1), Comprehension(List(i2), b2)) =>
          //TODO better way
          assert(!b2.freeVariables.contains(i1) && !b2.boundVariables.contains(i1), "TODO not sure how to merge")
          val b2p = FormulaUtils.replace(i2, i1, b2)
          Comprehension(List(i1), And(b1, b2p))
        case (c1, c2) =>
          Logger.logAndThrow("SetDef", Error, "merge: " + c1 + ", " + c2)
      }
      SetDef(scope, s1.id, Some(body))
    } else if (s1.body.isDefined) s1
    else s2
  }

  def mergeEqual( sDefs: Iterable[SetDef],
                  cClasses: CongruenceClasses
                ): Iterable[SetDef] = {
    var acc = Map[Formula, SetDef]()
    val map = sDefs.foldLeft(Map[Formula, SetDef]())( (acc, s) => {
      val r = cClasses.repr(s.id)
      if (acc contains r) {
        acc + (r -> merge(acc(r), s))
      } else {
        acc + (r -> s)
      }
    })
    map.values
  }

}
