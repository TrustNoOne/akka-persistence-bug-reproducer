package bug

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

class TestActor extends PersistentActor with ActorLogging {

  override def persistenceId = "bug-reproducer1"

  override def receiveRecover = {
    case s: String         ⇒ log.debug(s"recovered $s")
    case RecoveryCompleted ⇒ log.info("recovery completed")
  }

  override def receiveCommand = {
    case s: String ⇒ persist(Tagged(s, Set("tag1"))) { x ⇒
      log.debug(s"persisted $x")
    }
    // explicit stop to be sure we stop after all the persistence handlers have been executed
    case 'stop ⇒ context stop self
  }
}


class BugReproducer extends Actor with ActorLogging {

  val messages = List.fill(100)("some event")

  def receive = {
    case 'start =>
      log.info("Creating test actor")
      val ref1 = context.watch(context.actorOf(Props[TestActor]))
      log.info(s"Sending ${messages.size} events to persist")
      messages foreach (ref1 ! _)
      log.info("Stopping test actor and waiting for termination")
      ref1 ! 'stop

      context.become(waitingForFirstTermination(sender))
  }

  def waitingForFirstTermination(replyTo: ActorRef): Receive = {
    case Terminated(_) =>
      log.info("test actor terminated, creating it again")
      val ref2 = context.watch(context.actorOf(Props[TestActor]))

      log.info(s"Sending ${messages.size} events to persist")
      messages foreach (ref2 ! _)
      ref2 ! 'stop

      context.become(waitingEnd(replyTo))
  }

  def waitingEnd(replyTo: ActorRef): Receive = {
    case Terminated(_) =>
      log.info("Completed OK")
      replyTo ! Done
  }

}


object BugReproducer extends App {
  val system = ActorSystem()
  implicit val timeout: Timeout = 1.minute

  try {
    val ref = system.actorOf(Props[BugReproducer])
    Await.result(ref ? 'start, Duration.Inf)
  } catch {
    case t: Throwable => t.printStackTrace()
  }

  Await.result(system.terminate(), Duration.Inf)

}
