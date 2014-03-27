package spray.contrib.socketio.examples.benchmark

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import com.typesafe.config.ConfigFactory
import rx.lang.scala.Observable
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.frame.Frame
import spray.contrib.socketio.{ SocketIONamespaceExtension, SocketIOExtension, SocketIOServerConnection }
import spray.contrib.socketio.namespace.Namespace
import spray.contrib.socketio.namespace.Namespace.OnData
import spray.contrib.socketio.namespace.Namespace.OnEvent
import spray.contrib.socketio.packet.EventPacket

object SocketIOTestServer extends App {

  object SocketIOServer {
    def props(resolver: ActorRef) = Props(classOf[SocketIOServer], resolver)
  }
  class SocketIOServer(val resolver: ActorRef) extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(SocketIOWorker.props(serverConnection, resolver))
        serverConnection ! Http.Register(conn)
    }
  }

  object SocketIOWorker {
    def props(serverConnection: ActorRef, resolver: ActorRef) = Props(classOf[SocketIOWorker], serverConnection, resolver)
  }
  class SocketIOWorker(val serverConnection: ActorRef, val resolver: ActorRef) extends SocketIOServerConnection {

    def genericLogic: Receive = {
      case x: Frame =>
    }
  }

  implicit val system = ActorSystem()
  SocketIOExtension(system)
  implicit val resolver = SocketIONamespaceExtension(system).resolver

  val observer = new Observer[OnEvent] with Serializable {
    override def onNext(value: OnEvent) {
      value match {
        case OnEvent("chat", args, context) =>
          spray.json.JsonParser(args) // test spray-json performance too.
          if (isBroadcast) {
            value.broadcast("", EventPacket(-1L, false, value.endpoint, "chat", args))
          } else {
            value.replyEvent("chat", args)
          }
        case _ =>
          println("observed: " + value)
      }
    }
  }

  val channel = Subject[OnData]()
  // there is no channel.ofType method for RxScala, why?
  channel.flatMap {
    case x: OnEvent => Observable.items(x)
    case _          => Observable.empty
  }.subscribe(observer)

  SocketIONamespaceExtension(system).startNamespace("")
  SocketIONamespaceExtension(system).namespace("") ! Namespace.Subscribe(channel)

  val server = system.actorOf(SocketIOServer.props(resolver), name = "socketio-server")

  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val host = config.getString("host")
  val port = config.getInt("port")
  val isBroadcast = config.getBoolean("broadcast")
  IO(UHttp) ! Http.Bind(server, host, port)
}
