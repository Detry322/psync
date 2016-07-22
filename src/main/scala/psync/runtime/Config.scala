package psync.runtime

import scala.xml._
import psync._

object Config {

  private def parseReplica(e: Node) = {
    val sslOption = e.find(_.label == "ssl").map{ node =>
      CertificateInfo( (node \ "cert").text, (node \ "key").text)
    }

    Replica(new ProcessID((e \ "id").text.toShort),
            (e \ "address").text,
            (e \\ "port").map(_.text.toInt).toSet,
            sslOption)
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
