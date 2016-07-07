package cromwell

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorRef

object MainSpecDebug {
  var mainSpecOn = false

  private def printDbg(label: String, msg: String) = {
    println(s">>MSDBG $label ${OffsetDateTime.now}\n$msg\n<<MSDBG $label ${OffsetDateTime.now}\n")
  }

  val atomicInt = new AtomicInteger(0)

  def mainSpecDebug[T](msg: => String)(block: => T): T = mainSpecPrint(msg, mainSpecOn)(block)

  def mainSpecPrint[T](msg: => String)(block: => T): T = mainSpecPrint(msg, print = true)(block)

  private def mainSpecPrint[T](msg: => String, print: Boolean)(block: => T): T = {
    val index = if (print) atomicInt.incrementAndGet() else -1

    if (print) {
      printDbg(s"$index START", msg)
    }
    try {
      val result = block
      if (print) {
        printDbg(s"$index SUCCESS", msg)
      }
      result
    } catch {
      case throwable: Throwable =>
        if (print) {
          printDbg(s"$index FAIL", msg)
          throwable.printStackTrace(Console.out)
        }
        throw throwable
    }
  }

  def mainSpecPartial[A, B](msg: String, recipient: ActorRef, sender: => ActorRef)
                           (stateFunction: PartialFunction[A, B]): PartialFunction[A, B] = {
    new PartialFunction[A, B] {
      override def isDefinedAt(event: A) = {
        val result = stateFunction.isDefinedAt(event)
        if (!result) {
          if (mainSpecOn) {
            printDbg("UNHANDLED", s"subject = $msg\nto   = $recipient\nfrom = $sender\nbody = $event")
          }
        }
        result
      }

      override def apply(event: A) = {
        mainSpecDebug(s"*** handling ***\nsubject = $msg\nto   = $recipient\nfrom = $sender\nbody = $event") {
          stateFunction.apply(event)
        }
      }
    }
  }
}
