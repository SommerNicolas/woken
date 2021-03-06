package api

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import spray.http.StatusCodes._
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.RequestContext
import akka.actor.OneForOneStrategy
import scala.concurrent.duration._
import spray.http.StatusCode

import core._

trait PerRequest extends Actor with ActorLogging {

  import context._
  import DefaultMarshallers._

  def r: RequestContext
  def target: ActorRef
  def message: RestMessage

  setReceiveTimeout(180.seconds) // TODO: make configurable, align with spray.can.timeout
  target ! message

  // TODO: status code parameter redundant

  def receive = {
    case res: RestMessage => complete(OK, res)(res.marshaller.asInstanceOf[ToResponseMarshaller[RestMessage]])
    case v: core.Validation    => complete(BadRequest, v)
    case v: Error         => complete(BadRequest, v)
    case ReceiveTimeout   => complete(GatewayTimeout, Error("Request timeout"))
    case e: Any           => log.error(s"Unhandled message: $e")
  }

  def complete[T <: AnyRef](status: StatusCode, obj: T)(implicit marshaller: ToResponseMarshaller[T]) = {
    r.complete(obj)(marshaller)
    stop(self)
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        complete(InternalServerError, Error(e.getMessage))
        Stop
      }
    }
}

object PerRequest {
  case class WithActorRef(r: RequestContext, target: ActorRef, message: RestMessage) extends PerRequest

  case class WithProps(r: RequestContext, props: Props, message: RestMessage) extends PerRequest {
    lazy val target = context.actorOf(props)
  }
}

trait PerRequestCreator {

  import PerRequest._

  def context: ActorRefFactory

  def perRequest(r: RequestContext, target: ActorRef, message: RestMessage) =
    context.actorOf(Props(new WithActorRef(r, target, message)))

  def perRequest(r: RequestContext, props: Props, message: RestMessage) =
    context.actorOf(Props(new WithProps(r, props, message)))
}
