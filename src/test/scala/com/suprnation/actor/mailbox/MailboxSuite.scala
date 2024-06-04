package com.suprnation.actor.mailbox

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._
import com.suprnation.actor.dispatch.SystemMessage
import com.suprnation.actor.dispatch.mailbox.Mailboxes
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object MailboxSuite {
  def loopUntilTrue(effect: IO[Boolean]): IO[Unit] =
    effect.flatMap { condition =>
      if (condition) {
        IO.unit // Stop looping when condition is true
      } else {
        // Recursively call loopUntilTrue with the same effect
        loopUntilTrue(effect)
      }
    }

}

/** This test suite is geared towards creating a realistic scenario which creates increasingly more complex systems.
  */
class MailboxSuite extends AsyncFlatSpec with Matchers {
  type AnyWithDeferred = (Any, Option[Deferred[IO, Any]])

  def onSystemReceive(buffer: Ref[IO, List[Any]])(systemMessage: SystemMessage[IO]): IO[Unit] =
    buffer.update(xs => xs ++ List(systemMessage))

  def onUserReceive(buffer: Ref[IO, List[Any]])(message: AnyWithDeferred): IO[Unit] =
    message match {
      case (current, deferred) =>
        buffer.update(xs => xs ++ List(current)) >>
          (deferred match {
            case Some(d) => d.complete(())
            case None    => ().pure[IO]
          }).void
    }

  it should "allow the mailbox to close" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> None)
      _ <- IO.sleep(1 second)
      _ <- mailbox.close
      _ <- f.cancel
      result <- mailbox.isClosed
    } yield result).unsafeToFuture().map { result =>
      result should be(true)
    }
  }

  it should "process messages in sequence" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> None)
      _ <- MailboxSuite.loopUntilTrue(IO.sleep(1 second) >> mailbox.isIdle)
      _ <- mailbox.close
      result <- buffer.get
      _ <- f.cancel

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2))
    }
  }

  it should "allow suspension of messages" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      doneFromSecond <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> Some(doneFromSecond))
      _ <-
        doneFromSecond.get // We know that we have processed until 2, if we enqueue 3, 4, 5 we should not see them!
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.enqueue(5 -> None)
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2))
    }
  }

  it should "allow suspension of messages multiple times" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      doneFromSecond <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> Some(doneFromSecond))
      _ <-
        doneFromSecond.get // We know that we have processed until 2, if we enqueue 3, 4, 5 we should not see them!
      _ <- mailbox.suspend
      _ <- mailbox.suspend
      _ <- mailbox.suspend
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.enqueue(5 -> None)
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2))
    }
  }

  it should "allow us to call resume if the system is already processing" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      done <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> None)
      _ <- mailbox.resume
      _ <- mailbox.resume
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.enqueue(5 -> done.some)
      _ <- done.get
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2, 3, 4, 5))
    }
  }

  it should "allow us to resume a suspended mailbox" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      done <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> None)
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.enqueue(5 -> done.some)
      _ <- mailbox.resume
      _ <- done.get
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2, 3, 4, 5))
    }
  }

  it should "allow us to suspend and resume" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      done <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.enqueue(2 -> None)
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.enqueue(5 -> done.some)
      _ <- mailbox.resume
      _ <- done.get
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2, 3, 4, 5))
    }
  }

  it should "allow us to track suspension and resume and make sure they are equal (not equal)" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      done <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.suspend
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(2 -> None)
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.enqueue(5 -> done.some)
      // Mailbox cannot resume since it was suspended two times
      _ <- mailbox.resume
      // Check that here we do not receive any result.
      _ <- IO.race(IO.sleep(1 second), done.get)
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1))
    }
  }

  it should "allow us to track suspension and resume and make sure they are equal (equal)" in {
    (for {
      buffer <- Ref.of[IO, List[Any]](List.empty)
      done <- Deferred[IO, Any]
      mailbox <- Mailboxes.createMailbox[IO, SystemMessage[IO], AnyWithDeferred]("mailbox-test")
      f <- mailbox.processMailbox(onSystemReceive(buffer))(onUserReceive(buffer)).foreverM.start
      _ <- mailbox.enqueue(1 -> None)
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(2 -> None)
      _ <- mailbox.enqueue(3 -> None)
      _ <- mailbox.suspend
      _ <- mailbox.enqueue(4 -> None)
      _ <- mailbox.resume
      _ <- mailbox.enqueue(5 -> done.some)
      _ <- mailbox.resume
      _ <- done.get
      _ <- mailbox.close
      _ <- f.cancel
      result <- buffer.get

    } yield result).unsafeToFuture().map { result =>
      result should be(List(1, 2, 3, 4, 5))
    }
  }

}
