package com.suprnation.samples.logic

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor._
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

/** This test suite is geared towards creating a realistic scenario which creates increasingly more complex systems.
  */
class LogicCircuitSpec extends AsyncFlatSpec with Matchers with SimConfig {
  val pertuberation: Int = 2

  "Wire" should "return value" in {
    (for {
      actorSystem <- ActorSystem[IO]("Logic Circuits", (_: Any) => IO.unit).allocated.map(_._1)
      input <- actorSystem.actorOf(Wire(false), "in0")
      probe <- actorSystem.actorOf(Probe(input), "probe0")

      _ <- input ! true
      result1 <- probe.unsafeDowncastResponse[Boolean] ? GetValue
      _ <- input ! false
      result2 <- probe.unsafeDowncastResponse[Boolean] ? GetValue
    } yield (result1, result2)).unsafeToFuture().map { case (result1, result2) =>
      result1 should be(true)
      result2 should be(false)
    }
  }

  "Debug" should "allow us to debug the system" in {
    (for {
      debugMessage <- Ref.of[IO, Option[String]](None)
      system <- ActorSystem[IO](
        "Logic Circuits",
        (event: Any) => debugMessage.set(Some(event.toString))
      ).allocated.map(_._1)
      input <- system.actorOfWithDebug(Wire(false), "in0")
      _ <- input ! true
      result <- input.unsafeDowncastResponse[Boolean] ? GetValue
      _ <- system.waitForIdle()
      debugMessage <- debugMessage.get
    } yield (result, debugMessage)).unsafeToFuture().map { case (result, debugMessage) =>
      result should be(true)
      debugMessage should not be None
      debugMessage should be(
        Some(
          "Debug([Path: kukku://logic-circuits@localhost/user/debug-in0] [Name:debug-in0],class com.suprnation.actor.ReplyingActor,~~ redirect ~~>: [Name: in0] [Path: kukku://logic-circuits@localhost/user/in0]] GetValue)"
        )
      )
    }
  }

  "A Not gate" should "not the result from an up stream stimulus" in {
    (for {
      actorSystem <- ActorSystem[IO]("Inverter Circuit", (_: Any) => IO.unit).allocated.map(_._1)

      input0 <- actorSystem.actorOfWithDebug(Wire(false), "in0")
      output0 <- actorSystem.actorOfWithDebug(Wire(false), "out0")
      _ <- actorSystem.actorOfWithDebug(Inverter(input0, output0), "inverter")

      _ <- actorSystem.waitForIdle()

      //  Scenario 1 [Input: True] [Output: False]
      _ <- input0 ! true
      _ <- actorSystem.waitForIdle()
      res1 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input: False] [Output: True]"
      _ <- input0 ! false
      _ <- actorSystem.waitForIdle()
      res2 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

    } yield (res1, res2)).unsafeToFuture().map { case (res1, res2) =>
      res1 should be(false)
      res2 should be(true)
    }
  }

  "An And gate system (with probes)" should "behave as an And gate to the wire stimuli" in {
    (for {
      // AND Circuit
      system <- ActorSystem[IO]("AND Logic Circuit", (_: Any) => IO.unit).allocated.map(_._1)
      input0 <- system.actorOfWithDebug(Wire(false), "in0")
      probe0 <- system.actorOfWithDebug(Probe(input0), "probeIn0")

      input1 <- system.actorOfWithDebug(Wire(false), "in1")
      probe1 <- system.actorOfWithDebug(Probe(input1), "probeIn1")

      output0 <- system.actorOfWithDebug(Wire(false), "output0")
      probeOut0 <- system.actorOfWithDebug(Probe(output0), "probeOut0")

      _ <- system.actorOf(And(probe0, probe1, probeOut0), "andComponent")
      _ <- system.waitForIdle()

      // Scenario 1 [Input0: True] [Input1: False] [Output: False]
      _ <- input0 ! true
      _ <- system.waitForIdle()
      res1 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input0: True] [Input1: True] [Output: True]
      _ <- input1 ! true
      _ <- system.waitForIdle()
      res2 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input0: True] [Input1: False] [Output: False]
      _ <- input1 ! false
      _ <- system.waitForIdle()
      res3 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

    } yield (res1, res2, res3)).unsafeToFuture().map { case (res1, res2, res3) =>
      res1 should be(false)
      res2 should be(true)
      res3 should be(false)
    }
  }

  "An And gate with syntactic sugar" should "behave as an And gate to the wire stimuli" in {
    (for {
      // Creating AND Circuit
      system <- ActorSystem[IO]("AND Logic Circuit", (_: Any) => IO.unit).allocated.map(_._1)
      input0 <- system.actorOfWithDebug(Wire(false), "in0")
      input1 <- system.actorOfWithDebug(Wire(false), "in1")
      output0 <- system.actorOfWithDebug(Wire(false), "output0")
      _ <- system.actorOf(And(input0, input1, output0), "andComponent")

      _ <- system.waitForIdle()

      _ <- input0 ! true
      _ <- system.waitForIdle()
      res1 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input0: True] [Input1: True] [Output: True]
      _ <- input1 ! true
      _ <- system.waitForIdle()
      res2 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input0: True] [Input1: False] [Output: False]
      _ <- input1 ! false
      _ <- system.waitForIdle()
      res3 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

    } yield (res1, res2, res3)).unsafeToFuture().map { case (res1, res2, res3) =>
      res1 should be(false)
      res2 should be(true)
      res3 should be(false)
    }
  }

  "An Or gate" should "behave as an Or gate to the wire stimuli" in {
    (for {
      // Creating OR Circuit
      actorSystem <- ActorSystem[IO]("OR Logic Circuit", (_: Any) => IO.unit).allocated.map(_._1)
      input0 <- actorSystem.actorOfWithDebug(Wire(false), "in0")
      input1 <- actorSystem.actorOfWithDebug(Wire(false), "in1")
      output0 <- actorSystem.actorOfWithDebug(Wire(false), "output0")
      or <- actorSystem.actorOfWithDebug(Or(input0, input1, output0), "orComponent")

      _ <- actorSystem.waitForIdle()

      // Scenario 1 [Input0: True] [Input1: False] [Output: True]
      _ <- input0 ! true
      _ <- actorSystem.waitForIdle()
      res1 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input0: True] [Input1: True] [Output: True]
      _ <- input1 ! true
      _ <- actorSystem.waitForIdle()
      res2 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 3 [Input0: False] [Input1: True] [Output: True]
      _ <- input0 ! false
      _ <- actorSystem.waitForIdle()
      res3 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 4 [Input0: False] [Input1: False] [Output: False]
      _ <- input1 ! false
      _ <- actorSystem.waitForIdle()
      res4 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

    } yield (res1, res2, res3, res4)).unsafeToFuture().map { case (res1, res2, res3, res4) =>
      res1 should be(true)
      res2 should be(true)
      res3 should be(true)
      res4 should be(false)
    }
  }

  "An Alternate Or gate" should "behave as an Or gate (slightly slower) to the wire stimuli" in {
    (for {
      actorSystem <- ActorSystem[IO]("OR Logic Circuit", (_: Any) => IO.unit).allocated.map(_._1)
      input0 <- actorSystem.actorOfWithDebug(Wire(false), "in0")
      input1 <- actorSystem.actorOfWithDebug(Wire(false), "in1")
      output0 <- actorSystem.actorOfWithDebug(Wire(false), "output0")
      orAlt <- actorSystem.actorOfWithDebug(
        OrAlt(input0, input1, output0),
        "orAltComponent"
      )
      _ <- actorSystem.waitForIdle()

      // Scenario 1 [Input0: True] [Input1: False] [Output: True]
      _ <- input0 ! true
      _ <- actorSystem.waitForIdle()
      res1 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 2 [Input0: True] [Input1: True] [Output: True]
      _ <- input1 ! true
      _ <- actorSystem.waitForIdle()
      res2 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 3 [Input0: False] [Input1: True] [Output: True]
      _ <- input0 ! false
      _ <- actorSystem.waitForIdle()
      res3 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario 4 [Input0: False] [Input1: False] [Output: False]
      _ <- input1 ! false
      _ <- actorSystem.waitForIdle()
      res4 <- output0.unsafeDowncastResponse[Boolean] ? GetValue

    } yield (res1, res2, res3, res4)).unsafeToFuture().map { case (res1, res2, res3, res4) =>
      res1 should be(true)
      res2 should be(true)
      res3 should be(true)
      res4 should be(false)
    }
  }

  "A Half Adder" should "act like a Half Adder to the wire stimuli" in {
    (for {
      system <- ActorSystem[IO]("Half Adder", (_: Any) => IO.unit).allocated.map(_._1)
      a <- system.actorOfWithDebug(Wire(false), "a")
      b <- system.actorOfWithDebug(Wire(false), "b")
      s <- system.actorOfWithDebug(Wire(false), "s")
      c <- system.actorOfWithDebug(Wire(false), "c")
      _ <- system.actorOfWithDebug(HalfAdder(a, b, s, c), "halfAdder")

      _ <- system.waitForIdle()

      // Send b a 1 (s should be 1 and c should be 0)
      // Scenario A ~~~~~~~~~~~~~~~~~~~~~~
      _ <- a ! true
      _ <- system.waitForIdle()
      s0 <- s.unsafeDowncastResponse[Boolean] ? GetValue
      c0 <- c.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario B ~~~~~~~~~~~~~~~~~~~~~~
      // Send b a 1 (s should be 1  and c should be 1)
      _ <- b ! true
      _ <- system.waitForIdle()
      s1 <- s.unsafeDowncastResponse[Boolean] ? GetValue
      c1 <- c.unsafeDowncastResponse[Boolean] ? GetValue

      // Scenario C ~~~~~~~~~~~~~~~~~~~~~~
      // Send b a 1 (s should be 1  and c should be 1)
      _ <- b ! false
      _ <- system.waitForIdle()
      s2 <- s.unsafeDowncastResponse[Boolean] ? GetValue
      c2 <- c.unsafeDowncastResponse[Boolean] ? GetValue

    } yield ((s0, c0), (s1, c1), (s2, c2))).unsafeToFuture().map {
      case ((s0, c0), (s1, c1), (s2, c2)) =>
        s0 should be(true)
        c0 should be(false)

        s1 should be(false)
        c1 should be(true)

        s2 should be(true)
        c2 should be(false)
    }
  }

  "A Full Adder" should "behave as a Full Adder to the wire stimuli" in {
    (for {
      system <- ActorSystem[IO]("Half Adder", (_: Any) => IO.unit).allocated.map(_._1)
      a <- system.actorOf(Wire(false), "a")
      b <- system.actorOf(Wire(false), "b")
      cin <- system.actorOf(Wire(false), "cin")
      sum <- system.actorOf(Wire(false), "sum")
      cout <- system.actorOf(Wire(false), "cout")
      _ <- system.actorOf(FullAdder(a, b, cin, sum, cout), "full-adder")

      _ <- system.waitForIdle()

      checkTruthTable = (aV: Boolean, bV: Boolean, cV: Boolean) =>
        for {
          _ <- a ! aV
          _ <- b ! bV
          _ <- cin ! cV
          _ <- system.waitForIdle()
          _sumV <- sum.unsafeDowncastResponse[Boolean] ? GetValue
          _coutV <- cout.unsafeDowncastResponse[Boolean] ? GetValue
        } yield (_sumV, _coutV)

      s1 <- checkTruthTable(false, false, false)
      s2 <- checkTruthTable(false, false, true)
      s3 <- checkTruthTable(false, true, false)
      s4 <- checkTruthTable(false, true, true)
      s5 <- checkTruthTable(true, false, false)
      s6 <- checkTruthTable(true, false, true)
      s7 <- checkTruthTable(true, true, false)
      s8 <- checkTruthTable(true, true, true)

      _ <- system.waitForIdle()
    } yield (s1, s2, s3, s4, s5, s6, s7, s8)).unsafeToFuture().map {
      case ((s1, c1), (s2, c2), (s3, c3), (s4, c4), (s5, c5), (s6, c6), (s7, c7), (s8, c8)) =>
        // Scenario 1 - false, false, false, false, false
        s1 should be(false)
        c1 should be(false)

        // Scenario 2 - false, false, true, true, false
        s2 should be(true)
        c2 should be(false)

        // Scenario 3 - false, true, false, true, false
        s3 should be(true)
        c3 should be(false)

        // Scenario 4 - false, true, true, false, true
        s4 should be(false)
        c4 should be(true)

        // Scenario 5 - true, false, false, true, false
        s5 should be(true)
        c5 should be(false)

        // Scenario 6 - true, false, true, false, true
        s6 should be(false)
        c6 should be(true)

        // Scenario 7 - true, true, false, false, true
        s7 should be(false)
        c7 should be(true)

        // Scenario 8 - true, true, true, true, true
        s8 should be(true)
        c8 should be(true)
    }
  }

  "A Demux 2" should "behave as a de-multiplexer two to the wire stimuli" in {
    (for {
      system <- ActorSystem[IO]("Demux", (_: Any) => IO.unit).allocated.map(_._1)
      input <- system.actorOf(Wire(false), "input")
      c_0 <- system.actorOf(Wire(false), "c_0")

      out_0 <- system.actorOf(Wire(false), "out_0")
      out_1 <- system.actorOf(Wire(false), "out_1")

      _ <- system.actorOf[LogicCircuitRequest](Demux2(input, c_0, out_0, out_1), "demux2")
      _ <- system.waitForIdle()

      checkTruthTable = (_c_0: Boolean) =>
        for {
          _ <- input ! true
          _ <- c_0 ! _c_0
          _ <- system.waitForIdle()
          out0V <- out_0.unsafeDowncastResponse[Boolean] ? GetValue
          out1V <- out_1.unsafeDowncastResponse[Boolean] ? GetValue
        } yield (out0V, out1V)

      s1 <- checkTruthTable(false)
      s2 <- checkTruthTable(true)

    } yield (s1, s2)).unsafeToFuture().map { case ((s1Out1, s1Out2), (s2Out1, s2Out2)) =>
      // true, false
      s1Out1 should be(true)
      s1Out2 should be(false)

      // false, true
      s2Out1 should be(false)
      s2Out2 should be(true)
    }
  }

  "A Demux" should "behave as a de-multiplexer to the wire stimuli!" in {
    (for {
      system <- ActorSystem[IO]("Demux", (_: Any) => IO.unit).allocated.map(_._1)
      input <- system.actorOf(Wire(false), "input")
      c_0 <- system.actorOf(Wire(false), "c_0")
      c_1 <- system.actorOf(Wire(false), "c_1")

      out_0 <- system.actorOf(Wire(false), "out_0")
      out_1 <- system.actorOf(Wire(false), "out_1")
      out_2 <- system.actorOf(Wire(false), "out_2")
      out_3 <- system.actorOf(Wire(false), "out_3")

      _ <- system.waitForIdle()
      _ <- system.actorOf(
        Demux(input, List(c_0, c_1), List(out_0, out_1, out_2, out_3)),
        "demux"
      )

      checkTruthTable = (_c_0: Boolean, _c_1: Boolean) =>
        for {
          _ <- input ! true
          _ <- c_0 ! _c_0
          _ <- c_1 ! _c_1
          _ <- system.waitForIdle()
          out0V <- out_0.unsafeDowncastResponse[Boolean] ? GetValue
          out1V <- out_1.unsafeDowncastResponse[Boolean] ? GetValue
          out2V <- out_2.unsafeDowncastResponse[Boolean] ? GetValue
          out3V <- out_3.unsafeDowncastResponse[Boolean] ? GetValue
        } yield (out0V, out1V, out2V, out3V)

      s1 <- checkTruthTable(false, false)
      s2 <- checkTruthTable(false, true)
      s3 <- checkTruthTable(true, false)
      s4 <- checkTruthTable(true, true)
    } yield (s1, s2, s3, s4)).unsafeToFuture().map {
      case (
            (s0o1, s0o2, s0o3, s0o4),
            (s1o1, s1o2, s1o3, s1o4),
            (s2o1, s2o2, s2o3, s2o4),
            (s3o1, s3o2, s3o3, s3o4)
          ) =>
        // Scenario 1:  true, false, false, false
        s0o1 should be(true)
        s0o2 should be(false)
        s0o3 should be(false)
        s0o4 should be(false)

        // Scenario 2:  false, true, false, false
        s1o1 should be(false)
        s1o2 should be(true)
        s1o3 should be(false)
        s1o4 should be(false)

        // Scenario 3:  false, false, true, false
        s2o1 should be(false)
        s2o2 should be(false)
        s2o3 should be(true)
        s2o4 should be(false)

        // Scenario 4:  false, false, false, true
        s3o1 should be(false)
        s3o2 should be(false)
        s3o3 should be(false)
        s3o4 should be(true)
    }
  }
}
