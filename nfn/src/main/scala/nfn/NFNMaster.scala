package nfn

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}

import java.io.{PrintWriter, StringWriter}

import akka.actor._
import akka.util.ByteString
import akka.event.Logging

import network._
import network.UDPConnection._
import nfn.service._
import nfn.NFNMaster._
import ccn.ccnlite.CCNLite
import ccn.packet._
import ccn.{ContentStore, CCNLiteProcess}
import java.net.InetSocketAddress
import myutil.IOHelper
import lambdacalculus.parser.ast.{LambdaNFNPrinter, LambdaLocalPrettyPrinter, Variable, Expr}
import nfn.local.LocalAbstractMachineWorker
import monitor.Monitor


object NFNMaster {

  case class CCNSendReceive(interest: Interest) {
    def this(expr: Expr, local: Boolean = false) = {
      this(Interest(
        expr match {
          case Variable(name, _) => Seq(name)
          case _ => Seq(
            if(local) LambdaLocalPrettyPrinter(expr)
            else LambdaNFNPrinter(expr),
            "NFN"
          )
        }
      ))
    }
  }

  case class CCNAddToCache(content: Content)

  case class ComputeResult(content: Content)

  case class Connect(nodeConfig: NodeConfig)

  case class Thunk(interest: Interest)

  case class Exit()

}

case class NodeConfig(host: String, port: Int, computePort: Int, prefix: String)

object NFNMasterFactory {
  def network(context: ActorRefFactory, nodeConfig: NodeConfig) = {
    context.actorOf(networkProps(nodeConfig), name = "NFNMasterNetwork")
  }

  def networkProps(nodeConfig: NodeConfig) = Props(classOf[NFNMasterNetwork], nodeConfig)

  def local(context: ActorRefFactory) = {
    context.actorOf(localProps, name = "NFNMasterLocal")
  }
  def localProps: Props = Props(classOf[NFNMasterLocal])
}


/**
 * Worker Actor which responds to ccn interest and content packets
 */
trait NFNMaster extends Actor {

  val logger = Logging(context.system, this)
  val name = self.path.name

  val ccnIf = CCNLite

  val cs = ContentStore()
  var pit: Map[Seq[String], Set[ActorRef]] = Map()


  private def createComputeWorker(interest: Interest): ActorRef =
    context.actorOf(Props(classOf[ComputeWorker], self), s"ComputeWorker-${interest.hashCode}")

  private def handleInterest(interest: Interest) = {
    cs.find(interest.name) match {
      case Some(content) => sender ! content
      case None => {
        pit.get(interest.name) match {
          case Some(_) => {
            val pendingFaces = pit(interest.name) + sender
            pit += (interest.name -> pendingFaces )
          }
          case None =>
            val computeWorker = createComputeWorker(interest)
            val updatedSendersForInterest = pit.get(interest.name).getOrElse(Set()) + computeWorker
            pit += (interest.name -> updatedSendersForInterest)
            computeWorker.tell(interest, self)
        }
      }
    }
  }


  // Check pit for an address to return content to, otherwise discard it
  private def handleContent(content: Content) = {
    if(pit.get(content.name).isDefined) {
      pit(content.name) foreach { pendingSender =>  pendingSender ! content}
      pit -= content.name
    } else {
      logger.error(s"Discarding content $content because there is no entry in pit")
    }
  }


  def handlePacket(packet: CCNPacket) = {
    logger.info(s"Received: $packet")
    monitorReceive(packet)
    packet match {
      case i: Interest => handleInterest(i)
      case c: Content => handleContent(c)
    }
  }

  def monitorReceive(packet: CCNPacket)

  override def receive: Actor.Receive = {

    // received Data from network
    // If it is an interest, start a compute request
//    case CCNReceive(packet) => handlePacket(packet)
    case packet:CCNPacket => handlePacket(packet)

    case data: ByteString => {
      val byteArr = data.toByteBuffer.array.clone
      val maybePacket: Option[CCNPacket] = NFNCommunication.parseCCNPacket(ccnIf.ccnbToXml(byteArr))

      logger.debug(s"$name received ${maybePacket.getOrElse("unparsable data")}")
      maybePacket match {
        // Received an interest from the network (byte format) -> spawn a new worker which handles the messages (if it crashes we just assume a timeout at the moment)
        case Some(packet: CCNPacket) => handlePacket(packet)
        case None => logger.warning(s"Received data which cannot be parsed to a ccn packet: ${new String(byteArr)}")
      }
    }

    case CCNSendReceive(interest) => {
      cs.find(interest.name) match {
        case Some(content) => {
          logger.info(s"Received SendReceive request, found content for interest $interest in local CS")
          sender ! content
        }
        case None => {
          logger.info(s"Received SendReceive request, aksing network for $interest ")
          val updatedSendersForInterest = pit.get(interest.name).getOrElse(Set())  + sender
          pit += (interest.name -> updatedSendersForInterest)
          send(interest)
        }
      }
    }

    case Thunk(interest) => {
      logger.debug(s"sending thunk for interest $interest")
      send(Content(interest.name, "THUNK".getBytes))
    }

    // CCN worker itself is responsible to handle compute requests from the network (interests which arrived in binary format)
    // convert the result to ccnb format and send it to the socket
    case ComputeResult(content) => {
      pit.get(content.name) match {
        case Some(workers) => {
          logger.debug("sending compute result to socket")
          send(content)
          sender ! ComputeWorker.Exit()
          pit -= content.name
        }
        case None => logger.error(s"Received result from compute worker which timed out, discarding the result content: $content")
      }
    }

    case CCNAddToCache(content) => {
      logger.info(s"sending add to cache for name ${content.name.mkString("/", "/", "")}")
      sendAddToCache(content)
    }

    // TODO this message is only for network node
    case Connect(otherNodeConfig) => {
      connect(otherNodeConfig)
    }

    case Exit() => {
      exit()
      context.system.shutdown()
    }

  }

  def connect(otherNodeConfig: NodeConfig): Unit
  def send(packet: CCNPacket): Unit
  def sendAddToCache(content: Content): Unit
  def exit(): Unit = ()
}

case class NFNMasterNetwork(nodeConfig: NodeConfig) extends NFNMaster {

  val ccnLiteNFNNetworkProcess = CCNLiteProcess(nodeConfig)
  ccnLiteNFNNetworkProcess.start()

  val nfnSocket = context.actorOf(Props(classOf[UDPConnection],
                                          new InetSocketAddress(nodeConfig.host, nodeConfig.computePort),
                                          Some(new InetSocketAddress(nodeConfig.host, nodeConfig.port))),
                                        name = s"udpsocket-${nodeConfig.computePort}-${nodeConfig.port}")



  override def preStart() = {
    nfnSocket ! Handler(self)
  }


  override def send(packet: CCNPacket): Unit = {
    nfnSocket ! Send(ccnIf.mkBinaryPacket(packet))
    Monitor.monitor ! Monitor.PacketSent(packet, nodeConfig)
  }

  override def sendAddToCache(content: Content): Unit = {
    nfnSocket ! Send(ccnIf.mkAddToCacheInterest(content))
  }

  override def exit(): Unit = {
    ccnLiteNFNNetworkProcess.stop()
  }

  override def connect(otherNodeConfig: NodeConfig): Unit = {
    ccnLiteNFNNetworkProcess.connect(otherNodeConfig)
    Monitor.monitor ! Monitor.Connect(nodeConfig, otherNodeConfig)
  }

  override def monitorReceive(packet: CCNPacket): Unit = {
    Monitor.monitor ! Monitor.PacketReceived(packet, nodeConfig)
  }
}

case class NFNMasterLocal() extends NFNMaster {

  val localAM = context.actorOf(Props(classOf[LocalAbstractMachineWorker], self), name = "localAM")

  override def send(packet: CCNPacket): Unit = localAM ! packet

  override def sendAddToCache(content: Content): Unit = {
    cs.add(content)
  }

  override def connect(otherNodeConfig: NodeConfig): Unit = ???

  override def monitorReceive(packet: CCNPacket): Unit = ???
}


object ComputeWorker {
  case class Exit()
}

/**
 *
 */
case class ComputeWorker(ccnWorker: ActorRef) extends Actor {

  val name = self.path.name
  val logger = Logging(context.system, this)
  val ccnIf = CCNLite

  private var result : Option[String] = None

  def receivedContent(content: Content) = {
    // Received content from request (sendrcv)
    logger.error(s"ComputeWorker received content, discarding it because it does not know what to do with it")
  }

  // Received compute request
  // Make sure it actually is a compute request and forward to the handle method
  def receivedInterest(interest: Interest, requestor: ActorRef) = {
    logger.debug(s"received compute interest: $interest")
    val cmps = interest.name
    val computeCmps = cmps match {
      case Seq("COMPUTE", reqNfnCmps @ _ *) => {
        assert(reqNfnCmps.last == "NFN")
        val computeCmpsMaybeThunk = reqNfnCmps.init

        val (computeCmps, isThunkReq) = if(computeCmpsMaybeThunk.last == "THUNK") {
          computeCmpsMaybeThunk.init -> true
        } else {
          computeCmpsMaybeThunk -> false
        }

        handleComputeRequest(computeCmps, interest, isThunkReq, requestor)
      }
      case _ => logger.error(s"Dropping interest $interest because it is not a compute request")
    }
  }


  /*
   * Parses the compute request and instantiates a callable service.
   * On success, sends a thunk back if required, executes the services and sends the result back.
   */
  def handleComputeRequest(computeCmps: Seq[String], interest: Interest, isThunkRequest: Boolean, requestor: ActorRef) = {
    logger.debug(s"Handling compute request for cmps: $computeCmps")
    val callableServ: Future[CallableNFNService] = NFNService.parseAndFindFromName(computeCmps.mkString(" "), ccnWorker)

    callableServ onComplete {
      case Success(callableServ) => {
        if(isThunkRequest) {
          requestor ! Thunk(interest)
        }

        val result = callableServ.exec
        val content = Content(interest.name, result.toValueName.name.mkString(" ").getBytes)
        logger.debug(s"Finished computation, result: $content")
        requestor ! ComputeResult(content)
      }
      case Failure(e) => {

        logger.error(IOHelper.exceptionToString(e))
      }
    }
  }

  override def receive: Actor.Receive = {
    case content: Content => receivedContent(content)
    case interest: Interest => {
      // Just to make sure we are not closing over sender
      val requestor = sender
      receivedInterest(interest, requestor)
    }
    case Exit() => context.stop(self)
  }
}
