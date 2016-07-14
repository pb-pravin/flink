package org.apache.flink.mesos.scheduler

import java.util.concurrent.ThreadLocalRandom

import akka.actor.{Actor, LoggingFSM, Props}
import org.apache.flink.configuration.Configuration
import org.apache.flink.mesos.scheduler.ReconciliationCoordinator._
import org.apache.flink.mesos.scheduler.messages.{Connected, Disconnected, StatusUpdate}
import org.apache.mesos.{MesosSchedulerDriver, Protos}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
  * Coordinates task reconciliation between Mesos and the scheduler.
  *
  * Implements the reconciliation procedures as outlined here:
  * http://mesos.apache.org/documentation/latest/reconciliation/
  *
  */
class ReconciliationCoordinator(
    flinkConfig: Configuration,
    schedulerDriver: MesosSchedulerDriver) extends Actor with LoggingFSM[TaskState,ReconciliationData] {

  startWith(Suspended, ReconciliationData())

  when(Suspended) {
    case Event(reconcile: Reconcile, data: ReconciliationData) =>
      val tasks = reconcile.tasks.map(task => (task.getTaskId,task))
      stay using data.copy(remaining = if(reconcile.replace) tasks.toMap else data.remaining ++ tasks)

    case Event(msg: Connected, data: ReconciliationData) =>
      if(data.remaining.nonEmpty) goto(Reconciling)
      else goto(Idle) using ReconciliationData()
  }

  when(Idle) {
    case Event(reconcile: Reconcile, _) =>
      goto(Reconciling) using {
        val tasks = reconcile.tasks.map(task => (task.getTaskId,task))
        ReconciliationData(remaining = tasks.toMap)
      }
  }

  onTransition {
    case _ -> Reconciling =>
      log.info(s"Reconciliation requested for ${nextStateData.remaining.size} task(s)")
      schedulerDriver.reconcileTasks(nextStateData.remaining.values.asJavaCollection)
  }

  when(Reconciling, stateTimeout = INITIAL_RECONCILIATION_TIMEOUT) {

    case Event(reconcile: Reconcile, data: ReconciliationData) =>
      // initiate reconciliation for additional tasks (even while reconciliation is ongoing)
      schedulerDriver.reconcileTasks(reconcile.tasks.asJavaCollection)
      val tasks = reconcile.tasks.map(task => (task.getTaskId,task))
      stay using data.copy(remaining = if(reconcile.replace) tasks.toMap else data.remaining ++ tasks)

    case Event(update: StatusUpdate, data: ReconciliationData) =>
      // status information arrived for a task
      val remaining = data.remaining - update.status().getTaskId
      if(remaining.isEmpty) {
        log.info("Reconciliation completed")
        goto(Idle) using ReconciliationData()
      } else {
        stay using data.copy(remaining = remaining)
      }

    case Event(StateTimeout, data: ReconciliationData) =>
      // timeout waiting for task status information
      log.warning("Reconciliation is proceeding slowly; re-sending the reconciliation request.")
      schedulerDriver.reconcileTasks(data.remaining.values.asJavaCollection)
      stay using data.copy(retries = data.retries + 1) forMax(backoff(data.retries))
  }

  whenUnhandled {
    case Event(update: StatusUpdate, _) =>
      // discard status updates when not in reconciliation state
      stay()

    case Event(msg: Disconnected, data: ReconciliationData) =>
      goto(Suspended) using data.copy(retries = 0)
  }

  initialize()
}

object ReconciliationCoordinator {

  val INITIAL_RECONCILIATION_TIMEOUT = 1 minutes
  val RECONCILIATION_MIN_BACKOFF = 5 seconds
  val RECONCILIATION_MAX_BACKOFF = 1 minute

  /**
    * An abstract FSM state.
    */
  sealed trait TaskState

  /**
    * The state of active reconciliation.
    */
  case object Reconciling extends TaskState

  /**
    * The state of idling when reconciliation is not underway.
    */
  case object Idle extends TaskState

  /**
    * The state of being disconnected from Mesos.
    */
  case object Suspended extends TaskState

  /**
    * The state data of the reconciliation coordinator.
    *
    * @param remaining
    * @param retries
    */
  case class ReconciliationData(remaining: Map[Protos.TaskID,Protos.TaskStatus] = Map(), retries: Int = 0)

  /**
    * Initiates the task reconciliation process.
    *
    * @param tasks
    */
  case class Reconcile(tasks: Seq[Protos.TaskStatus], replace: Boolean = false)

  /**
    * Calculate an exponential backoff duration.
    */
  private def backoff(
      retries: Int,
      minBackoff: FiniteDuration = RECONCILIATION_MIN_BACKOFF,
      maxBackoff: FiniteDuration = RECONCILIATION_MAX_BACKOFF,
      randomFactor: Double = 0.2): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    maxBackoff.min(minBackoff * math.pow(2, math.min(retries, 30))) * rnd match {
      case f: FiniteDuration => f
      case _ => maxBackoff
    }
  }

  /**
    * Create the properties for a reconciliation coordinator.
    */
  def createActorProps[T <: ReconciliationCoordinator](
      actorClass: Class[T],
      flinkConfig: Configuration,
      schedulerDriver: MesosSchedulerDriver): Props = {

    Props.create(actorClass, flinkConfig, schedulerDriver)
  }
}
