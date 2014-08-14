package round.runtime

import scala.xml._
import round._

object Config {

  private def parseReplica(e: Node) = {
    Replica(new ProcessID((e \ "id").text.toShort),
            (e \ "address").text,
            (e \ "port").text.toInt)
  }

  private def parseOption(e: Node): (String, String) = {
    ((e \ "name").text, (e \ "value").text)
  }

  def parse(file: String) = {
    val tree = XML.loadFile(file)
    val params = (tree \ "parameters" \\ "param").map(parseOption)
    val peers = (tree \ "peers" \\ "replica").map(parseReplica)
    (peers.toList,
     params.foldLeft(Map[String,String]())( (acc, kv) => acc + kv ))
  }

}
