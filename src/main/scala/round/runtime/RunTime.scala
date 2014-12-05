package round.runtime

import round._
import round.predicate._
import java.nio.ByteBuffer
import dzufferey.utils.LogLevel._
import dzufferey.utils.Logger


class RunTime[IO](val alg: Algorithm[IO]) {

  private var srv: Option[PacketServer] = None

  private var options = Map.empty[String, String]

  private var defltHandler: Message => Unit = null
  
  private var allocator: ByteBufferPool = null

  /** Start an instance of the algorithm. */
  def startInstance(
      instanceId: Short,
      io: IO,
      messages: Set[Message] = Set.empty)
  {
    Logger("RunTime", Info, "starting instance " + instanceId)
    srv match {
      case Some(s) =>
        //an instance is actually encapsulated by one process
        val grp = s.directory.group
        val process = alg.process(grp.self, io)
        process.setGroup(grp)
        process.allocateResources(allocator)
        process.postInit
        val predicate = new ToPredicate(grp, instanceId, s.channel, s.dispatcher, process, options)
        //register the instance and send the first round of messages
        predicate.start
        //msg that are already received
        for(m <- messages) {
          if (!Flags.userDefinable(m.flag) && m.flag != Flags.dummy) {
            predicate.receive(m)
          } else {
            m.release
          }
        }
      case None =>
        sys.error("service not running")
    }
  }

  /** Stop a running instance of the algorithm. */
  def stopInstance(instanceId: Short) {
    Logger("RunTime", Info, "stopping instance " + instanceId)
    srv match {
      case Some(s) =>
        s.dispatcher.findInstance(instanceId).map(_.stop)
      case None =>
        sys.error("service not running")
    }
  }

  /** Start the service that ... */
  def startService(
    defaultHandler: Message => Unit,
    peers: List[Replica],
    options: Map[String, String]
  ) {
    if (srv.isDefined) {
      //already running
      return
    }
    
    //create the group
    val me = new ProcessID(options("id").toShort)
    val grp = Group(me, peers)

    allocator = ByteBufferPool.apply() //TODO use option to modify the allocator

    //start the server
    val port = 
      if (grp contains me) grp.get(me).port
      else options("port").toInt
    Logger("RunTime", Info, "starting service on port " + port)
    val pktSrv = new PacketServer(port, grp, allocator, defaultHandler, options)
    srv = Some(pktSrv)
    pktSrv.start
  }

  def startService(
    defaultHandler: Message => Unit,
    configFile: String,
    additionalOpt: Map[String, String]
  ) {

    //parse config
    val (peers,param1) = Config.parse(configFile)
    options = param1 ++ additionalOpt

    startService(defaultHandler, peers, options)    
  }

  def shutdown {
    srv match {
      case Some(s) =>
        Logger("RunTime", Info, "stopping service")
        s.close
      case None =>
    }
    srv = None
    executor.shutdownNow
  }
  
  //for additional tasks
  private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
  //private val executor = java.util.concurrent.Executors.newCachedThreadPool()

  def submitTask[T](fct: () => T) = {
    assert(srv.isDefined)
    executor.submit(new java.util.concurrent.Callable[T]{
      def call: T = fct()
    })
  }

  /** the first 8 bytes of the payload must be empty */
  def sendMessage(dest: ProcessID, tag: Tag, payload: ByteBuffer => Unit) = {
    assert(Flags.userDefinable(tag.flag) || tag.flag == Flags.dummy) //TODO in the long term, we might want to remove the dummy
    assert(srv.isDefined)
    val grp = srv.get.directory
    val dst = grp.idToInet(dest)
    val buffer = allocator.get
    buffer.putLong(tag.underlying)
    payload(buffer)
    buffer.flip
    val channel = srv.get.channel
    channel.send(buffer, dst)
    allocator.recycle(buffer)
    //channel.flush
  }

  def directory = {
    assert(srv.isDefined)
    srv.get.directory
  }

}
