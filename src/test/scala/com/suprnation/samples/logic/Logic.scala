package com.suprnation.samples.logic

import cats.effect.IO
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor._
import com.suprnation.typelevel.actors.syntax._

object LogicCircuitRequest {
  implicit def convertToState(boolean: Boolean): State = State(boolean)
}
trait LogicCircuitRequest
case class AddComponent(wireName: String, actor: ActorRef[IO, LogicCircuitRequest])
    extends LogicCircuitRequest
case class StateChange(name: String, state: Boolean) extends LogicCircuitRequest
case class State(state: Boolean) extends LogicCircuitRequest
case object GetValue extends LogicCircuitRequest

/** This is not the best use of actors but it shows how one can migrate from Akka to Actors
  *
  * @param currentState
  *   the current state.
  */
case class Wire(var currentState: Boolean)
    extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest] {

  var associations: Map[ActorRef[IO, LogicCircuitRequest], String] =
    Map[ActorRef[IO, LogicCircuitRequest], String]()

  override def receive: Receive[IO, LogicCircuitRequest] = {
    case AddComponent(name: String, b: ActorRef[IO, LogicCircuitRequest]) =>
      log(s"[Wire] Processing Message Adding Component $name ${b.path}") >>
        IO.delay {
          associations = associations + (b -> name)
        } >> log(s"[Wire] Added Association on $name for ${b.path}") >>
        (b ! StateChange(name, currentState))

    case GetValue => currentState.pure[IO]

    case State(s) =>
      if (currentState != s) {
        IO.delay {
          currentState = s
        } >>
          associations
            .map { case (ref: ActorRef[IO, LogicCircuitRequest], name: String) =>
              log(
                s"[Wire] Setting [State: $currentState] to [Name: $name] [Address: $ref}]"
              ) >> (ref ! StateChange(name, currentState))
            }
            .toList
            .sequence
            .as(s)
      } else IO(s)
  }

}

case class Inverter(
    input: ActorRef[IO, LogicCircuitRequest],
    output: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with SimConfig {
  override def preStart: IO[Unit] = (input ! AddComponent("in", self)).void

  override def receive: Receive[IO, LogicCircuitRequest] = { case StateChange(_, s: Boolean) =>
    context.system.scheduler.scheduleOnce(inverterDelay) {
      output ! (!s)
    }
  }
}

case class Probe(input: ActorRef[IO, LogicCircuitRequest])
    extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest] {
  override def receive: Receive[IO, LogicCircuitRequest] = {
    case add @ AddComponent(name: String, b: ActorRef[IO, LogicCircuitRequest]) =>
      log(
        s"[Probe] Processing Message Adding Component ${name} ${b.path}"
      ) >> (input ?! add) >> log(s"[Probe] Sent message to ${b.path} ")
    case sc @ StateChange(_, currentState: Boolean) =>
      log(
        s"[Probe ${self.path.name}~>${input.path.name}] Wire is now $currentState"
      ) >> (input ?! sc)
    case State(b) =>
      // Here we will need to forward the message..
      log(
        s"[Probe ${self.path.name}~>${input.path.name}] Wire received [State: $b]"
      ) >> (input ?! b)
    case gv @ GetValue =>
      log(
        s"[Probe ${self.path.name}~>${input.path.name}] Current state is"
      ) >> (input ? gv)
  }
}

case class And(
    input0: ActorRef[IO, LogicCircuitRequest],
    input1: ActorRef[IO, LogicCircuitRequest],
    output: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest]
    with SimConfig {

  var in0: Option[Boolean] = None
  var in1: Option[Boolean] = None

  override def preStart: IO[Unit] =
    ((input0 ! AddComponent("in0", self)) >>
      (input1 ! AddComponent("in1", self))).void

  override def receive: Receive[IO, LogicCircuitRequest] = {
    case StateChange("in0", b: Boolean) =>
      IO {
        in0 = b.some
      } >> log(s"Setting IN 0 $b") >> sendMessage

    case StateChange("in1", b: Boolean) =>
      IO {
        in1 = b.some
      } >> log(s"Setting IN 1 $b") >> sendMessage
  }

  private def sendMessage: IO[Option[Boolean]] =
    (in0, in1)
      .mapN((first, second) =>
        context.system.scheduler.scheduleOnce(andDelay) {
          val result: Boolean = first && second
          (output ! result) >> IO(result)
        }
      )
      .sequence
      .flatTap(r => log(s"Sending Result $r"))
}

case class Or(
    input0: ActorRef[IO, LogicCircuitRequest],
    input1: ActorRef[IO, LogicCircuitRequest],
    output: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest]
    with SimConfig {

  var in0: Option[Boolean] = None
  var in1: Option[Boolean] = None

  override def preStart: IO[Unit] =
    ((input0 ! AddComponent("in0", self)) >>
      (input1 ! AddComponent("in1", self))).void

  override def receive: Receive[IO, LogicCircuitRequest] = {
    case StateChange("in0", b: Boolean) =>
      IO {
        in0 = b.some
      } >> sendMessage

    case StateChange("in1", b: Boolean) =>
      IO {
        in1 = b.some
      } >> sendMessage
  }

  private def sendMessage: IO[Option[Boolean]] =
    (in0, in1)
      .mapN((first, second) =>
        context.system.scheduler.scheduleOnce(andDelay) {
          val result: Boolean = first || second
          (output ! result) >> log(s"Sending $result") >> IO(result)
        }
      )
      .sequence
}

case class ForwardActor(input: ActorRef[IO, LogicCircuitRequest])
    extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest] {
  override def receive: Receive[IO, LogicCircuitRequest] = { case msg: Any =>
    log("[Forwarder] Sending back!") >> (input >>! msg)
  }
}

case class OrAlt(
    input0: ActorRef[IO, LogicCircuitRequest],
    input1: ActorRef[IO, LogicCircuitRequest],
    output: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with SimConfig {

  override def preStart: IO[Unit] = for {
    notInput0 <- context.actorOf[LogicCircuitRequest](Wire(false), "notInput0")
    notInput1 <- context.actorOf[LogicCircuitRequest](Wire(false), "notInput1")
    notOutput0 <- context.actorOf[LogicCircuitRequest](Wire(false), "notOutput0")

    _ <- context.actorOf[LogicCircuitRequest](Inverter(input0, notInput0), "A")
    _ <- context.actorOf[LogicCircuitRequest](Inverter(input1, notInput1), "B")
    _ <- context.actorOf[LogicCircuitRequest](And(notInput0, notInput1, notOutput0), "notAB")
    _ <- context.actorOf[LogicCircuitRequest](Inverter(notOutput0, output), "notnotAB")
  } yield ()
}

case class HalfAdder(
    a: ActorRef[IO, LogicCircuitRequest],
    b: ActorRef[IO, LogicCircuitRequest],
    s: ActorRef[IO, LogicCircuitRequest],
    c: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with SimConfig
    with ActorLogging[IO, LogicCircuitRequest] {

  override def preStart: IO[Unit] = for {
    d <- context.actorOfWithDebug(Wire(false), "d")
    e <- context.actorOfWithDebug(Wire(false), "e")
    _ <- context.actorOfWithDebug(Or(a, b, d), "Or")
    _ <- context.actorOfWithDebug(And(a, b, c), "And")
    _ <- context.actorOfWithDebug(Inverter(c, e), "Inverter")
    _ <- context.actorOfWithDebug(And(d, e, s), "And2")
  } yield ()
}

case class FullAdder(
    a: ActorRef[IO, LogicCircuitRequest],
    b: ActorRef[IO, LogicCircuitRequest],
    cin: ActorRef[IO, LogicCircuitRequest],
    sum: ActorRef[IO, LogicCircuitRequest],
    cout: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest] {

  override def preStart: IO[Unit] = for {
    s <- context.actorOf[LogicCircuitRequest](Wire(false))
    c1 <- context.actorOf[LogicCircuitRequest](Wire(false))
    c2 <- context.actorOf[LogicCircuitRequest](Wire(false))
    _ <- context.actorOf[LogicCircuitRequest](HalfAdder(a, cin, s, c1), "HalfAdder1")
    _ <- context.actorOf[LogicCircuitRequest](HalfAdder(b, s, sum, c2), "HalfAdder2")
    _ <- context.actorOf[LogicCircuitRequest](Or(c1, c2, cout), "OrGate")
  } yield ()
}

case class Demux2(
    in: ActorRef[IO, LogicCircuitRequest],
    c: ActorRef[IO, LogicCircuitRequest],
    out1: ActorRef[IO, LogicCircuitRequest],
    out0: ActorRef[IO, LogicCircuitRequest]
) extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest] {
  override def preStart: IO[Unit] = for {
    notC <- context.actorOf[LogicCircuitRequest](Wire(false), "not")
    _ <- context.actorOf[LogicCircuitRequest](Inverter(c, notC), "InverterA")
    _ <- context.actorOf[LogicCircuitRequest](And(in, notC, out1), "AndGateA")
    _ <- context.actorOf[LogicCircuitRequest](And(in, c, out0), "AndGateB")
  } yield ()

}

case class Demux(
    in: ActorRef[IO, LogicCircuitRequest],
    c: List[ActorRef[IO, LogicCircuitRequest]],
    out: List[ActorRef[IO, LogicCircuitRequest]]
) extends Actor[IO, LogicCircuitRequest]
    with ActorLogging[IO, LogicCircuitRequest] {

  /** This is pretty insane, we are going to compose the actor system using recursion... pwetttyyy kewwwlll
    *
    * @return
    */
  override def preStart: IO[Unit] = c match {
    case c_n :: Nil =>
      context.actorOf[LogicCircuitRequest](Demux2(in, c_n, out.head, out(1))).void

    case c_n :: c_rest =>
      for {
        out1 <- context.actorOf[LogicCircuitRequest](Wire(false))
        out0 <- context.actorOf(Wire(false))
        _ <- context.actorOf[LogicCircuitRequest](Demux2(in, c_n, out1, out0))
        _ <- context.actorOf[LogicCircuitRequest](Demux(out1, c_rest, out.take(out.size / 2)))
        _ <- context.actorOf[LogicCircuitRequest](Demux(out0, c_rest, out.drop(out.size / 2)))
      } yield ()

    case Nil => IO.unit
  }
}
