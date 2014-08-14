package round.verification

import round.formula._

object Utils {

  val procType = UnInterpreted("ProcessID")
  //TODO a method to fix the ProcessID vs Int problem ...

  val procI = Variable("i").setType(procType)
  val procJ = Variable("j").setType(procType)

  //x → x(i)
  def skolemify(x: Variable): UnInterpretedFct = {
    UnInterpretedFct(x.toString, Some(Function(List(procType), x.tpe)))
  }
  def skolemify(x: Variable, i: Formula): Formula = {
    Application(skolemify(x), List(i)).setType(x.tpe)
  }

  val oldPrefix = "__old__"
  val initPrefix = "__init__"
    
  def removeOldPrefix(f: Formula) =
    FormulaUtils.mapSymbol({
      case f @ UnInterpretedFct(nme,t,p) if nme startsWith oldPrefix =>
         UnInterpretedFct(nme drop oldPrefix.length,t,p)
      case f => f
    }, f)
  
  def removeInitPrefix(f: Formula) =
    FormulaUtils.mapSymbol({
      case f @ UnInterpretedFct(nme,t,p) if nme startsWith initPrefix =>
         UnInterpretedFct(nme drop initPrefix.length,t,p)
      case f => f
    }, f)
  
  //TODO extends purification to skolemization (get the new free vars)
  // x → x(i) if x ∈ vars
  def localize(vars: Set[Variable], i: Variable, f: Formula) = {
    def map(f: Formula): Formula = f match {
      case v @ Variable(_) if vars contains v => skolemify(v, i)
      case other => other
    }
    FormulaUtils.map(map, f)
  }
  
  def itemForFormula(title: String, f: Formula) = {
    new dzufferey.report.GenericItem(
        title,
        TextPrinter.toString(f),
        HtmlPrinter.toString(f))
  }
  
  def itemForFormula(title: String, fs: List[Formula]) = {
    new dzufferey.report.GenericItem(
        title,
        TextPrinter.toStringTbl(fs),
        HtmlPrinter.toStringTbl(fs))
  }


}
