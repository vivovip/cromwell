package cromwell.server

import java.util.concurrent.TimeoutException

import akka.actor.Props
import akka.util.Timeout
import com.typesafe.config.Config
import cromwell.webservice.{APIResponse, CromwellApiService, SwaggerService}
import lenthall.spray.SprayCanHttpService._
import spray.http.HttpHeaders.`Content-Type`
import spray.http.MediaTypes._
import spray.http.{ContentType, MediaTypes, _}
import lenthall.spray.WrappedRoute._
import lenthall.config.ScalaConfig._
import cromwell.webservice.WorkflowJsonSupport._
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

// Note that as per the language specification, this is instantiated lazily and only used when necessary (i.e. server mode)
object CromwellServer extends CromwellSystem {
  implicit val timeout = Timeout(5.seconds)
  import scala.concurrent.ExecutionContext.Implicits.global

  val service = actorSystem.actorOf(CromwellServerActor.props(conf), "CromwellServerActor")
  val webserviceConf = conf.getConfig("webservice")

  def run(): Future[Any] = {
    val interface = webserviceConf.getString("interface")
    val port = webserviceConf.getInt("port")
    val futureBind = service.bind(interface = interface, port = port)
    futureBind andThen {
      case Success(_) =>
        actorSystem.log.info("Cromwell service started...")
        actorSystem.awaitTermination()
      case Failure(throwable) =>
        /*
        TODO:
        If/when CromwellServer behaves like a better async citizen, we may be less paranoid about our async log messages
        not appearing due to the actor system shutdown. For now, synchronously print to the stderr so that the user has
        some idea of why the server failed to start up.
         */
        Console.err.println(s"Binding failed interface $interface port $port")
        throwable.printStackTrace(Console.err)
        shutdownActorSystem()
    }
  }
}

class CromwellServerActor(config: Config) extends CromwellRootActor with CromwellApiService with SwaggerService {
  implicit def executionContext = actorRefFactory.dispatcher
  override def actorRefFactory = context

  val possibleRoutes = workflowRoutes.wrapped("api", config.getBooleanOr("api.routeUnwrapped")) ~ swaggerUiResourceRoute
  val timeoutError = APIResponse.error(new TimeoutException("The server was not able to produce a timely response to your request.")).toJson.prettyPrint

  override def receive = handleTimeouts orElse runRoute(possibleRoutes)

  def handleTimeouts: Receive = {
    case Timedout(_: HttpRequest) =>
      sender() ! HttpResponse(StatusCodes.InternalServerError, HttpEntity(ContentType(MediaTypes.`application/json`), timeoutError)).withHeaders(`Content-Type`(`application/json`))
  }
}

object CromwellServerActor {
  def props(config: Config): Props = {
    Props(new CromwellServerActor(config))
  }
}
