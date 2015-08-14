package round.logic

import round.formula._
import round.formula.InlineOps._
import TestCommon._

import org.scalatest._

//port of the example from the vmcai paper.
//they are more readable than dumping the VCs from the code
class OtrExample extends FunSuite {

  val a = Variable("A").setType(FSet(pid))

  val v = Variable("v").setType(Int) 

  val mailbox = UnInterpretedFct("mailbox", Some(pid ~> FSet(Product(List(Int,pid)))))

  val data = UnInterpretedFct("data",Some(pid ~> Int))
  val data1 = UnInterpretedFct("data1",Some(pid ~> Int))
  val data0 = UnInterpretedFct("data0",Some(pid ~> Int))
  val decided = UnInterpretedFct("decided", Some(pid ~> Bool))
  val decided1 = UnInterpretedFct("decided1", Some(pid ~> Bool))

  val primeMap = Map[Symbol,Symbol](
    data -> data1,
    decided -> decided1
  )
  def prime(f: Formula) = FormulaUtils.mapSymbol( x => primeMap.getOrElse(x, x), f)

  val agreement = ForAll(List(i,j), Implies(And(decided(i), decided(j)), Eq(data(i),data(j))))
  val integrity = ForAll(List(i), Implies(decided(i), And(decided1(i), Eq(data(i), data1(i)))))
  val termination = ForAll(List(i), decided(i))
  val validity = ForAll(List(i), Exists(List(j), Eq(data(i), data0(j))))
  val validityPrimed = ForAll(List(i), Exists(List(j), Eq(data1(i), data0(j))))

  def twoThird(f: Formula): Formula = {
    Lt(Times(n, Literal(2)), Times(Cardinality(f), Literal(3)))
  }

  val invariantAgreement = Or(
    ForAll(List(i), Not(decided(i))),
    Exists(List(v,a), And(
      Eq(a, Comprehension(List(i), Eq(data(i), v))),
      twoThird(a),
      ForAll(List(i), Implies(decided(i), Eq(data(i), v)))
    ))
  )

  val invariantProgress1 = Exists(List(v,a), And(
    Eq(a, Comprehension(List(i), Eq(data(i), v))),
    Eq(Cardinality(a), n),
    ForAll(List(i), Implies(decided(i), Eq(data(i), v)))
  ))
  
  val invariantProgress2 =
    Exists(List(v), ForAll(List(i), And(decided(i), Eq(data(i), v))))

  val initialState = ForAll(List(i), Eq(decided(i), False()))

  //TODO something seems wrong with the TR, mailbox, or mmor

  //min most often received
  val mmor = UnInterpretedFct("mmor", Some(pid ~> Int))


  //transition relation
  val tr = And(
    //aux fun
    //TODO mmor not quite right
    ForAll(List(i,v), Or(
      Lt( Cardinality(Comprehension(List(j), In(Tuple(v,j), mailbox(i)))),
          Cardinality(Comprehension(List(j), In(Tuple(mmor(i),j), mailbox(i))))),
      Leq(mmor(i), v)
    )),
    //send, mailbox
    ForAll(List(i,j), Eq(In(i,ho(j)), In(Tuple(data(i),j), mailbox(i)))),
    ForAll(List(i), Eq(Cardinality(mailbox(i)), Cardinality(ho(i)))),
    //update
    ForAll(List(i), And(
      Implies(twoThird(mailbox(i)),
        And(Eq(data1(i), mmor(i)),
            Exists(List(a), And(
              Eq(a, Comprehension(List(j), In(Tuple(mmor(i),j), mailbox(i)))),
              Implies(twoThird(a), Eq(decided1(i), True())),
              Implies(Not(twoThird(a)), Eq(decided1(i), decided(i)))
        )))
      ),
      Implies(Not(twoThird(mailbox(i))),
        And(Eq(decided(i), decided1(i)), Eq(data1(i), data(i)))
      )
    ))
  )

  //sightly different encoding of the mailbox
  val tr2 = And(
    //aux fun
    //TODO mmor not quite right
    ForAll(List(i,k), Or(
      Lt( Cardinality(Comprehension(List(j), And(In(j,ho(i)), Eq(data(j),data(k))))),
          Cardinality(Comprehension(List(j), And(In(j,ho(i)), Eq(data(j),mmor(i)))))),
      Leq(mmor(i),data(k))
    )),
    ForAll(List(i), Exists(List(j), Eq(mmor(i), data(j)))),
    //update
    ForAll(List(i), And(
      Implies(twoThird(ho(i)),
        And(Eq(data1(i), mmor(i)),
            Exists(List(a), And(
              Eq(a, Comprehension(List(j), And(In(j,ho(i)), Eq(data(j),mmor(i))))),
              Implies(twoThird(a), Eq(decided1(i), True())),
              Implies(Not(twoThird(a)), Eq(decided1(i), decided(i)))
        )))
      ),
      Implies(Not(twoThird(ho(i))),
        And(Eq(decided(i), decided1(i)), Eq(data1(i), data(i)))
      )
    ))
  )
  
  //encoding of the mailbox with a map
  val mailboxM = UnInterpretedFct("mailbox", Some(pid ~> FMap(pid, Int)))
  def twoThirdMap(f: Formula) = Gt(Size(f), Divides(Times(IntLit(2), n), IntLit(3)))
  def valueIs(f: Formula) = Comprehension(List(j), And( IsDefinedAt(mailboxM(i), j),
                                                        Eq(LookUp(mailboxM(i), j), f)))
  val mmorDef = ForAll(List(i,v), And(
      valueIs(v).card <= valueIs(mmor(i)).card,
      Implies( valueIs(v).card === valueIs(mmor(i)).card, mmor(i) <= v)
    ))

  val tr3 = And(
    //aux fun: mmor
    mmorDef,
    //send, mailbox
    ForAll(List(i), Eq(KeySet(mailboxM(i)), ho(i))),
    ForAll(List(i,j), Eq(LookUp(mailboxM(i), j), data(j))),
    //update
    ForAll(List(i), And(
      Implies(twoThirdMap(mailboxM(i)),
        And(Eq(data1(i), mmor(i)),
            Exists(List(a), And(
              Eq(a, valueIs(mmor(i))),
              Implies(twoThird(a), Eq(decided1(i), True())),
              Implies(Not(twoThird(a)), Eq(decided1(i), decided(i)))
        )))
      ),
      Implies(Not(twoThirdMap(mailboxM(i))),
        And(Eq(decided(i), decided1(i)), Eq(data1(i), data(i)))
      )
    ))
  )

  val magicRound = Exists(List(a), And(
    Lt(Times(n, Literal(2)), Times(Cardinality(a), Literal(3))),
    ForAll(List(i), Eq(ho(i), a))
  ))
  
  test("initial state implies invariant") {
    assertUnsat(List(initialState, Not(invariantAgreement)))
  }

  test("invariant implies agreement") {
    assertUnsat(List(invariantAgreement, Not(agreement)))
  }
  
  test("invariant implies termination") {
    assertUnsat(List(invariantProgress2, Not(termination)))
  }

  test("validity holds initially") {
    assertUnsat(List(ForAll(List(i), Eq(data0(i), data(i))), Not(validity)))
  }

//XXX already this blows up, also depth beyond 0 does not matter. something is wrong in the quantifier inst
//test("mmor unsat") {
//  val fs = List(
//    mmorDef,
//    ForAll(List(i,j), And(
//      IsDefinedAt(mailboxM(i), j) === ho(i).contains(j),
//      LookUp(mailboxM(i), j) === data(j)
//    )),
//    Comprehension(List(i), data(i) === v).card > ((n * 2) / 3),
//    ForAll(List(i), ho(i).card > ((n * 2) / 3) ),
//    mmor(k) !== v
//  )
//  assertUnsat(fs, 60000, true, cl2_2, Some("test_mmor.smt2"))
//}

//test("invariant is inductive") {
//  val fs = List(
//    //ForAll(List(i), Eq(Size(mailboxM(i)),Literal(0))), //try the else branch
//    //ForAll(List(i), Eq(Cardinality(mailbox(i)),Literal(0))), //try the else branch
//    invariantAgreement,
//    //tr,
//    //tr2,
//    tr3,
//    Not(prime(invariantAgreement))
//  )
//  //assertUnsat(fs, 10000, true, cl2_3)
//  //assertUnsat(fs, 10000, true, cl2_3, Some("test2_3.smt2"))
//  assertUnsat(fs, 60000, true, cl2_3, Some("test_mf.smt2"), true)
//}

//test("1st magic round") {
//  val fs = List(
//    invariantAgreement,
//    magicRound
//    tr,
//    Not(prime(invariantProgress1))
//  )
//  assertUnsat(fs)
//}

//test("invariant 1 is inductive") {
//  val fs = List(
//    invariantProgress1,
//    tr,
//    Not(prime(invariantProgress1))
//  )
//  assertUnsat(fs)
//}

//test("2nd magic round") {
//  val fs = List(
//    invariantProgress1,
//    magicRound
//    tr,
//    Not(prime(invariantProgress2))
//  )
//  assertUnsat(fs)
//}

//test("invariant 2 is inductive") {
//  val fs = List(
//    invariantProgress2,
//    //tr,
//    tr2,
//    Not(prime(invariantProgress2))
//  )
//  //assertUnsat(fs)
//  getModel(fs, 60000, Some("test.smt2"))
//}

//test("integrity") {
//  val fs = List(
//    invariantAgreement,
//    prime(invariantAgreement),
//    tr,
//    Not(integrity)
//  )
//  assertUnsat(fs)
//}

//test("validity is inductive") {
//  val fs = List(
//    //ForAll(List(i), Eq(Cardinality(mailbox(i)),Literal(0))), //try the else branch
//    validity,
//    //tr,
//    tr2,
//    Not(prime(validity))
//  )
//  assertUnsat(fs, 60000)
//}

}
