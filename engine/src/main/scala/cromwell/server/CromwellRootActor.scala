package cromwell.server

import akka.actor.SupervisorStrategy.Escalate
import akka.actor.{Actor, ActorInitializationException, OneForOneStrategy, Props}
import akka.event.Logging
import com.typesafe.config.ConfigFactory
import cromwell.engine.workflow.{WorkflowManagerActor, WorkflowStoreActor}
import cromwell.services.{ServiceRegistryActor, ServiceRegistryClient}

/**
  * An actor which is purely used as a supervisor for the rest of Cromwell, allowing us to have more fine grained
  * control on top level supervision, etc.
  *
  * For now there are only two entries into Cromwell - either using SingleWorkflowRunnerActor or CromwellServerActor.
  * This is intended to be mixed in with those entry points.
  */
 abstract class CromwellRootActor extends Actor with ServiceRegistryClient {
  private val logger = Logging(context.system, this)

  override val serviceRegistryActor = context.actorOf(ServiceRegistryActor.props(ConfigFactory.load()), "ServiceRegistryActor")
  val workflowStoreActor = context.actorOf(WorkflowStoreActor.props(serviceRegistryActor), "WorkflowStoreActor")
  val workflowManagerActor = context.actorOf(WorkflowManagerActor.props(workflowStoreActor, serviceRegistryActor), "WorkflowManagerActor")

  override def receive = {
    case _ => logger.error("CromwellRootActor is receiving a message. It prefers to be left alone!")
  }

  /**
    * Validate that all of the direct children actors were successfully created, otherwise error out the initialization
    * of Cromwell by passing a Throwable to the guardian.
    */
  override val supervisorStrategy = OneForOneStrategy() {
    case aie: ActorInitializationException => throw new Throwable(s"Unable to create actor for ActorRef ${aie.getActor}", aie.getCause)
    case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
  }
}
