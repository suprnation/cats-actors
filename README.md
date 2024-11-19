# Cats-Actors

[![Release](https://jitpack.io/v/suprnation/cats-actors.svg)](https://jitpack.io/#suprnation/cats-actors)

Cats-Actors 🐱 is a functional programming-based actor system derived from Akka v2.6.21 🚀️

![Cats-Actors Logo](images/logo-small.png)

## Acknowledgments

This project is a derivative work of [Akka](https://www.akka.io/), developed by Lightbend, Inc. and other contributors.
Significant modifications have been made to rewrite the actor system and actor cell to use functional programming
principles and fibers.

The code follows the same design philosophy as the original Akka source code but has been modified to use Cats and
Cats-Effect libraries. It achieves high concurrency by using actors. This project can be considered an abstraction of
Cats-Effect and FS2, providing an actor-based experience and higher-level abstractions to achieve high concurrency.

## Introduction

Actors are the fundamental units of computation in the Actor Model, encapsulating state and behavior. They interact
exclusively through asynchronous message passing, ensuring their state is accessed sequentially.

### How Actors Communicate

- **Message Passing**: Actors communicate by sending messages to each other, which are queued in their mailboxes and
  processed one at a time.
- **Actor References**: Actors use unique references (ActorRef) to send messages.

### Actor Lifecycle

- **Creation**: Actors are created by other actors, forming a hierarchy. Root actors are created by the actor system.
- **Running**: In the "RUNNING" state, actors process messages defined by a receive method.
- **Stopping**: Actors can be stopped, entering the "SHUTDOWN" state, and can perform cleanup tasks.
- **Restarting**: Actors can be restarted by their parent, with preRestart and postRestart methods for cleanup and
  reinitialization.

### Supervision and Fault Tolerance

- **Supervision Strategies**: Define how to handle actor failures, ensuring system robustness.
- **Fault Tolerance**: Isolating state within actors and handling failures through supervision provides a robust
  framework for building fault-tolerant systems.

For more details, refer to the [Wikipedia page on the Actor Model](https://en.wikipedia.org/wiki/Actor_model).

Cats-Actors is a library designed to help developers build highly concurrent applications using functional programming
principles. It is a fork of Akka 2.6.x, created before the Akka project adopted the Business Source License. The library
has been modified to use modern functional programming tools like Cats and Cats-Effect, making it easier to write safe,
efficient, and scalable code. Cats-Actors allows you to manage multiple tasks simultaneously without the complexity of
traditional threading, making it ideal for applications that need to handle a lot of operations at once.

### Why Should I Use Cats-Actors?

- **Functional Programming Principles**

  - **Cats and Cats-Effect Integration**: Supports immutability, pure functions, and referential transparency.
  - **Fibers**: Offers lightweight concurrency for efficient, scalable applications.
- **Simplified Concurrency Model**

  - **High Concurrency**: Achieves high concurrency with minimal overhead and complexity.
- **Fault Tolerance and Supervision**

  - **Supervision Strategies**: Robust, functional programming-based supervision strategies.
  - **Functional Error Handling**: Makes error handling expressive and easier to reason about.
- **Modularity and Composability**

  - **Composable Effects**: Enables modular and reusable complex workflows.
- **Type Safety and Expressiveness**

  - **Type Classes and Higher-Kinded Types**: Provides expressive and type-safe code, reducing runtime errors.
  - **Fully Typed Actors**: Ensures that actors are fully typed, specifying the types of messages they can receive and reply with, enhancing type safety and reducing runtime errors.
- **Unique Features**

  - **Most Comprehensive Actor Implementation in the Functional Space**: Cats-Actors implements become/unbecome,
    supervision, and watch in a functional way, making it the most comprehensive actor implementation in the functional
    programming ecosystem.

Cats-Actors leverages functional programming and the Cats and Cats-Effect libraries for a more efficient, maintainable,
and expressive framework for building concurrent applications.


## Getting Started

### Installation

#### Using SBT

Add the following to your `build.sbt`:

```scala
resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies += "com.github.suprnation.cats-actors" %% "cats-actors" % "2.0.0"
```

#### Using Maven

Add the following to your `pom.xml`:

```xml

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

```xml

<dependency>
    <groupId>com.github.suprnation.cats-actors</groupId>
    <artifactId>cats-actors_2.13</artifactId>
    or
    <artifactId>cats-actors_3</artifactId>
    <version>2.0.0</version>
</dependency>
```

#### Using Bazel

Add the following to your `repositories.bzl` and `WORKSPACE` files:

`repositories.bzl`

```python
def load_dependencies():
    maven_install(
        artifacts = [
            "com.github.suprnation.cats-actors:cats-actors_2_13:2.0.0", // or
            "com.github.suprnation.cats-actors:cats-actors_3:2.0.0",
        ],
        repositories = [
            "https://jitpack.io",
        ],
    )
```

`WORKSPACE`

```
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "4.3"
RULES_JVM_EXTERNAL_SHA = "eac6aeece657c8be3b9bb0a8f25a6ab5daedc2a0736f64c19d5cf1e6a3a5ba4a"

http_archive(
    name = "rules_jvm_external",
    urls = ["https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG],
    sha256 = RULES_JVM_EXTERNAL_SHA,
)

load("@rules_jvm_external//:defs.bzl", "maven_install")

load("//:repositories.bzl", "load_dependencies")

load_dependencies()
```

and update the `BUILD` as follows

```
java_library(
    name = "my_library",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "@maven//:com_github_suprnation_cats_actors",
    ],
)
```

This should help integrate `cats-actors` into your projects using SBT, Maven, or Bazel.

## Basic Usage

### Hello World Example with Cats-Actors (Tell syntax)

Cats-Actors leverages Cats Effect for managing effects and supports a generic `F[_]` type, allowing interoperability with other effect types.

Here's a simple example of an actor system with a "Hello World" actor using Cats Effect:

First, define an actor that will handle the "Hello World" message.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

sealed trait Request
case object Hello extends Request

case class HelloWorldActor() extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = { case Hello =>
    IO.println("Hello, World!")
  }
}
```

Next, create an actor system to manage the actor and send a message to it.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

object HelloWorldApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("HelloWorldSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.actorOf(HelloWorldActor(), "helloWorldActor")
        _ <- actorRef ! Hello
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. Define the Actor:

- `HelloWorldActor extends Actor[IO, Request]`, meaning it processes `Request` messages within the `IO` effect. This ensures that all messages the actor can receive will be of type `Request`.
- The `receive` method is overridden to handle incoming messages. Here, it simply prints "Hello, World!" when it receives the `Hello` message.

2. Create the Actor System and Send a Message:

- `ActorSystem[IO]("HelloWorldSystem")` creates a new actor system named "HelloWorldSystem".
- `actorSystemResource.use { system => ... }` uses the `.use` method to manage the lifecycle of the actor system.
- `system.actorOf(HelloWorldActor(), "helloWorldActor")` creates an instance of `HelloWorldActor` within the actor system.
- `actorRef ! Hello` sends the `Hello` message to the actor.
- `system.waitForTermination` waits for the actor system to terminate. This ensures that all actors have completed their work before the application exits.
- The actor system is automatically cleaned up after the `use` block completes.

### Type Safety

Since the actor is typed with `Request`, only messages of type `Request` can be sent to it. For example, the following code would not be allowed:

```scala
_ <- actorRef ! "Hello"  // Error: Type mismatch
_ <- actorRef ! 123      // Error: Type mismatch
```

#### What does `waitForTermination` do?
The `waitForTermination` method is used to keep the actor system alive and waiting for messages unless the actor system
itself dies internally, for example, due to supervision escalation. This is useful in scenarios where you want to ensure
that the actor system remains active and responsive to incoming messages until it is explicitly terminated or an
internal failure occurs.

In the context of the Hello World example, `waitForTermination` ensures that the actor system remains active. But what if we do not want to wait forever? what if we want to just ensure that the actor has received the message and just processed that message? There are various ways how one can achieve this but one way is to use the `ask` syntax described
below.

### Hello World Example with Cats-Actors (Ask syntax)

Below is a comprehensive example of a Hello World application using Cats-Actors using the `?` syntax for querying an actor.

First, let's define an actor that will handle the `Hello` message and reply with `World` .

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.ReplyingActor

sealed trait Request
case object Hello extends Request

sealed trait Response
case object World extends Response

class HelloWorldActor extends ReplyingActor[IO, Request, Response] {
  override def receive: ReplyingReceive[IO, Request, Response] = { case Hello =>
    IO.println("Hello, World!").as(World)
  }
}
```

Note that in this case since we want to ensure that the request is of type `Request` and the reply from an actor is of type `Response` we extend from `ReplyingActor[IO, Request, Response]` which forces the actor to compute an `F[Response]` for every message which will be redirected to the sender.

Next, create an actor system to manage the actor and query it using the `?` syntax.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

object HelloWorldApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("HelloWorldSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.replyingActorOf(new HelloWorldActor, "helloWorldActor")
        response <- actorRef ? Hello
        _ <- IO.println(s"Received response: $response")
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. Define the Actor:

- `HelloWorldActor extends ReplyingActor[IO, Request, Response]`, meaning it processes `Request` messages and replies with `Response` within the `IO` effect.
- The `receive` method is overridden to handle the `Hello` messages (of type `Request`). Here, it prints "Hello, World!" and returns `World` (of type `Response`).

2. Create the Actor System and Query the Actor:

- `ActorSystem[IO]("HelloWorldSystem")` creates a new actor system named "HelloWorldSystem".
- `actorSystemResource.use { system => ... }` uses the `.use` method to manage the lifecycle of the actor system.
- `system.replyingActorOf(new HelloWorldActor, "helloWorldActor")` creates an instance of `HelloWorldActor` within the actor system.
- `actorRef ? Hello` sends the `Hello` message to the actor and waits for a response. Note that the `ask` syntax will return a `Response`, the declared return type of the `ReplyingActor[IO, Request, Response]`.
- `IO.println(s"Received response: $response")` prints the response received from the actor.
- The actor system is automatically cleaned up after the `use` block completes.

> **Note:** that if you do not care about the reply but only care that the actor has processed the message you can use the `?!` syntax. We can rewrite the above example as:

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

object HelloWorldApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("HelloWorldSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.replyingActorOf(new HelloWorldActor, "helloWorldActor")
        _ <- actorRef ?! "hello"
        _ <- IO.println(s"Actor all done!")
      } yield ExitCode.Success
    }
  }
}
```

#### Why `waitForTermination` is **not** Required

In this example, `waitForTermination` is not required because the actor system will automatically wait for a reply from
the actor when using the `?` syntax. The `?` syntax sends a message to the actor and waits for a response of type `Response`, ensuring that the
actor system remains active until the response is received. This makes `waitForTermination` unnecessary in scenarios where
you are querying an actor and expecting a reply.

By using the `?` syntax, the actor system handles the lifecycle and ensures that the application waits for the actor's
response before proceeding. This simplifies the code and avoids the need for explicit termination handling.

## Sequential vs Parallel send

### Using cats-effects to send sequential messages to an actor

Below is a comprehensive example of an actor that receives multiple messages forever. We will use Cats-Effect to leverage fibers for concurrent message sending in the background.

First, define an actor that will handle multiple messages.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

case class EchoActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case message =>
    IO.println(s"Received message: $message")
  }
}
```

Next, create an actor system to manage the actor and send messages to it in a background fiber.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ActorSystem

import scala.concurrent.duration._

object EchoApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("EchoSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.actorOf(EchoActor(), "echoActor")
        _ <- sendMessagesForever(actorRef).start // Start sending messages in a background fiber
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }

  def sendMessagesForever(actorRef: ActorRef[IO, String]): IO[Unit] = {
    val messages = List("Hello", "World", "This", "Is", "Cats-Actors")
    val sendMessage = messages.traverse_(msg => (actorRef ! msg) >> IO.sleep(1.second))
    sendMessage.foreverM
  }
}
```

### Explanation

1. Define the Actor:

- `EchoActor extends Actor[IO, String]`, meaning it processes `String` messages within the `IO` effect.
- The `receive` method is overridden to handle incoming messages. Here, it prints each received message.

2. Create the Actor System and Send Messages in a Fiber:

- `ActorSystem[IO]("EchoSystem")` creates a new actor system named "EchoSystem".
- `actorSystemResource.use { system => ... }` uses the `.use` method to manage the lifecycle of the actor system.
- `system.actorOf(EchoActor(), "echoActor")` creates an instance of `EchoActor` within the actor system.
- `sendMessagesForever(actorRef).start` starts sending messages to the actor in a background fiber.
- The actor system waits until an internal error happens, in such an eventuality the actor system is cleaned up after the `use` block completes.

3. Send Messages Forever:

- `sendMessagesForever` is a function that sends a list of messages to the actor repeatedly.
- `messages.traverse_(msg => actorRef ! msg >> IO.sleep(1.second))` sends each message to the actor with a 1-second delay between messages.
- `sendMessage.foreverM` repeats the message sending process indefinitely.

#### Leveraging Cats-Effect
By using Cats-Effect, we can leverage its powerful concurrency primitives to create complex use cases like this one. The `start` method allows us to run the message-sending process in a background fiber, ensuring that the actor continues to receive messages indefinitely without blocking the main application flow. The `foreverM` method is used to repeat the message-sending process forever, demonstrating how Cats-Effect can be used to build highly concurrent and resilient applications.

### Using cats-effects to send parallel messages to an actor

Below is a comprehensive example of an actor that receives multiple messages in parallel. We will use Cats-Effect to leverage fibers for concurrent message sending.

First, define an actor that will handle multiple messages.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

case class EchoActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case message =>
    IO.println(s"Received message: $message")
  }
}
```

Next, create an actor system to manage the actor and send messages to it in parallel.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ActorSystem

object EchoApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("EchoSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.actorOf(EchoActor(), "echoActor")
        _ <- sendMessagesInParallel(actorRef) // Start sending messages in parallel
        _ <- system.waitForTermination // Keep the actor system alive
      } yield ExitCode.Success
    }
  }

  def sendMessagesInParallel(actorRef: ActorRef[IO, String]): IO[Unit] = {
    val messages = List("Hello", "World", "This", "Is", "Cats-Actors")
    val sendMessage = messages.parTraverse_(msg => actorRef ! msg)
    sendMessage
  }
}
```

### Explanation

1. Define the Actor:

- `EchoActor extends Actor[IO, String]`, meaning it processes `String` messages within the `IO` effect.
- The `receive` method is overridden to handle incoming messages. Here, it prints each received message.

2. Create the Actor System and Send Messages in Parallel:

- `ActorSystem[IO]("EchoSystem")` creates a new actor system named "EchoSystem".
- `actorSystemResource.use { system => ... }` uses the `.use` method to manage the lifecycle of the actor system.
- `system.actorOf(EchoActor(), "echoActor")` creates an instance of `EchoActor` within the actor system.
- `sendMessagesInParallel(actorRef)` sends messages to the actor in parallel.
- `_ <- system.waitForTermination` keeps the actor system alive and waiting for messages unless the actor system itself dies internally.
- The actor system is automatically cleaned up after the use block completes.

3. Send Messages in Parallel:

- `sendMessagesInParallel` is a function that sends a list of messages to the actor in parallel.
- `messages.parTraverse_(msg => actorRef ! msg)` sends each message to the actor concurrently using `parTraverse_` from Cats, which runs the effects in parallel.


#### Leveraging Cats-Effect for Concurrency

By using Cats-Effect, we can leverage its powerful concurrency primitives to create complex use cases like this one. The `parTraverse_` method allows us to run the message-sending process in parallel, ensuring that the actor receives messages concurrently.

## Creating Actors
Below are three examples demonstrating how to create multiple actors in different ways using the `cats-actors` library.

### Example 1: Creating Several Actors from the System Actor

In this example, we create multiple actors directly from the actor system.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorSystem

case class EchoActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case message =>
    IO.println(s"Received message: $message")
  }
}

object SystemActorApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("SystemActorApp")

    actorSystemResource.use { system =>
      for {
        actorRef1 <- system.actorOf(EchoActor(), "echoActor1")
        actorRef2 <- system.actorOf(EchoActor(), "echoActor2")
        actorRef3 <- system.actorOf(EchoActor(), "echoActor3")
        _ <- List(actorRef1, actorRef2, actorRef3).parTraverse_(_ ! "Hello from SystemActorApp")
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Example 2: Creating Actors from Within an Actor
In this example, we create multiple actors from within another actor.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorSystem

case class EchoActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case message =>
    IO.println(s"Received message: $message")
  }
}

case class ParentActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case "createChildren" =>
    for {
      child1 <- context.system.actorOf(EchoActor(), "childActor1")
      child2 <- context.system.actorOf(EchoActor(), "childActor2")
      _ <- List(child1, child2).parTraverse_(_ ! "Hello from ParentActor")
    } yield ()
  }
}

object ParentActorApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("ParentActorApp")

    actorSystemResource.use { system =>
      for {
        parentActor <- system.actorOf(ParentActor(), "parentActor")
        _ <- parentActor ! "createChildren"
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Example 3: Creating an Actor from Within an Actor Created by Another Actor

In this example, we create an actor from within an actor that was itself created by another actor.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorSystem

case class EchoActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case message =>
    IO.println(s"Received message: $message")
  }
}

case class GrandParentActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case "createParent" =>
    for {
      parent <- context.system.actorOf(ParentActor(), "parentActor")
      _ <- parent ! "createChild"
    } yield ()
  }
}

case class ParentActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case "createChild" =>
    for {
      child <- context.actorOf(EchoActor(), "childActor")
      _ <- child ! "Hello from ParentActor"
    } yield ()
  }
}

object GrandParentActorApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("GrandParentActorApp")

    actorSystemResource.use { system =>
      for {
        grandParentActor <- system.actorOf(GrandParentActor(), "grandParentActor")
        _ <- grandParentActor ! "createParent"
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **Example 1:**

- Creates three `EchoActor` instances directly from the actor system.
- Sends a message to each actor in parallel.
- _Note: The actor system is the parent of all created actors and is responsible for their supervision._

2. **Example 2:**

- Defines a `ParentActor` that creates two `EchoActor` instances when it receives the "createChildren" message.
- Sends a message to each child actor in parallel.
- _Note: The `ParentActor` is the parent of the created child actors and is responsible for their supervision._

3. **Example 3:**

- Defines a `GrandParentActor` that creates a `ParentActor` when it receives the "createParent" message.
- The `ParentActor` creates an `EchoActor` when it receives the "createChild" message.
- Sends a message to the child actor.
- _Note: The `GrandParentActor` is the parent of the `ParentActor`, and the `ParentActor` is the parent of the `EchoActor`. Each parent actor is responsible for the supervision of its child actors._

## Killing Actors
In actor systems, there are various ways to manage actor termination:

- **PoisonPill**

  - **Purpose**: Gracefully stops an actor.
  - **Behavior**: When an actor receives a PoisonPill, it processes all messages in its mailbox before stopping.
  - **Use Case**: Preferred for a clean shutdown where the actor can finish its pending work.
- **Kill**

  - **Purpose**: Forcibly stops an actor.
  - **Behavior**: When an actor receives a Kill message, it throws an exception (typically an ActorKilledException), causing the actor to immediately terminate and restart if supervised.
  - **Use Case**: Useful when you need to abruptly stop an actor, but it can disrupt ongoing processes.
- **context.self.stop**

  - **Purpose**: Directly stops the actor from within its own context.
  - **Behavior**: Immediately stops the actor. Any messages in the mailbox are discarded, and the actor transitions to the stopped state.
  - **Use Case**: Suitable for scenarios where the actor decides to terminate itself after completing its work or encountering a condition that requires it to stop.

Let's illustrate these with examples

### Example 1: Killing an Actor Using context.self.stop

In this example, we define an actor that stops itself when it receives a specific message - `Stop`.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorSystem

import scala.concurrent.duration._
import scala.language.postfixOps

trait Request
case object Stop extends Request
case class Message(msg: String) extends Request

case class SelfStoppingActor() extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = {
    case Stop =>
      IO.println("Stopping actor...") >> context.self.stop
    case Message(msg) =>
      IO.println(s"Received message: $msg")
  }
}

object SelfStoppingActorApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("SelfStoppingActorSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.actorOf(SelfStoppingActor(), "selfStoppingActor")
        _ <- actorRef ! Message("Hello, Actor!")
        _ <- actorRef ! Stop
        _ <- IO.sleep(1.second) // Give some time for the actor to stop
      } yield ExitCode.Success
    }
  }
}
```

### Example 2: Killing an Actor Using PoisonPill

In this example, we define an actor that stops when it receives a PoisonPill message.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.{ActorSystem, PoisonPill}

import scala.concurrent.duration._
import scala.language.postfixOps

trait Request
case class Message(message: String) extends Request

case class PoisonPillActor() extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = { case Message(msg) =>
    IO.println(s"Received message: $msg")
  }
}

object PoisonPillActorApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("PoisonPillActorSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.actorOf(PoisonPillActor(), "poisonPillActor")
        _ <- actorRef ! Message("Hello, Actor!")
        _ <- actorRef !* PoisonPill // Note the use of !*
        _ <- IO.sleep(1.second) // Give some time for the actor to stop
      } yield ExitCode.Success
    }
  }
}
```

### Example 3: Killing an Actor Using Kill

In this example, we define an actor that stops when it receives a Kill message.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.{ActorSystem, Kill}

import scala.concurrent.duration._
import scala.language.postfixOps

trait Request
case class Message(message: String) extends Request

case class KillableActor() extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = { case Message(msg) =>
    IO.println(s"Received message: $msg")
  }
}

object KillableActorApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("KillableActorSystem")

    actorSystemResource.use { system =>
      for {
        actorRef <- system.actorOf(KillableActor(), "killableActor")
        _ <- actorRef ! Message("Hello, Actor!")
        _ <- actorRef !* Kill // Note the use of !*
        _ <- IO.sleep(1.second) // Give some time for the actor to stop
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **Example 1: Killing an Actor Using `context.self.stop`**:

- `SelfStoppingActor`:
  - Stops itself when it receives the `Stop` message (automatically handled by the Actor).
  - Prints a message when it receives any other message.
- `SelfStoppingActorApp`:
  - Creates an actor system named "SelfStoppingActorSystem".
  - Creates an actor of type `SelfStoppingActor`.
  - Sends a message to the actor and then sends the `Stop` message to stop the actor.

2. **Example 2: Killing an Actor Using `PoisonPill`**:

- `PoisonPillActor`:
  - Stops itself when it receives a `PoisonPill` message (automatically handled by the Actor).
  - Prints a message when it receives any other message.
- `PoisonPillActorApp`:
  - Creates an actor system named "PoisonPillActorSystem".
  - Creates an actor of type `PoisonPillActor`.
  - Sends a message to the actor and then sends the `PoisonPill` message to stop the actor.

3. **Example 3: Killing an Actor Using `Kill`**:

- `KillableActor` stops itself when it receives a `Kill` message.
- `KillableActorApp` creates an actor system, sends a message to the actor, and then sends the `Kill` message to stop the actor.

### `!*` Method

The `!*` method is used to send system commands to actors. The type definition of `!*` is:

```scala
  def !*(message: => SystemCommand)(implicit
      sender: Option[ActorRef[F, Nothing]] = None
  ): F[Unit]
```

There are two system commands, `Kill` and `PoisonPill`, which can be sent using this method.

These examples demonstrate how to kill actors using `context.self.stop`, `PoisonPill`, and `Kill` in the `cats-actors` library. Let's understand the difference here.

## Passing Actor Addresses (ActorRef) around
Actors communicate by sending messages. To communicate with each other, they need to know each other's addresses. Actors can pass addresses in two ways:
- During actor creation
- Passed as a message

Let's explore these approaches.

### Passing Actor Address during creation

In this section, we will demonstrate how to pass an actor's address during its creation. This approach allows an actor to communicate with another actor by having its reference from the moment it is created. This can be useful when setting up structured communication patterns between actors right from the start.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef

case class Question(text: String)

case class RespondingActor(replyTo: ActorRef[IO, Question]) extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case question =>
    replyTo ! Question(question)
  }
}
```

When the `RespondingActor` is constructed, we pass in an `ActorRef[IO, Question]`, which is an address of an actor that accepts `Question` messages. When the actor receives a message (which must be of type `String`), it converts the message to a `Question` type and sends it to the `replyTo` actor.

What if we wanted to receive a reply from this actor with the answer to our question? Can we guarantee that the queries will be answered? Yes! Let's see how to achieve this.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ReplyingActorRef

case class Question(text: String)
case class Answer(text: String)

case class RespondingActor(replyTo: ReplyingActorRef[IO, Question, Answer]) extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case question =>
    for {
      answer <- replyTo ? Question(question)
    } yield answer.text
  }
}
```

Here, we take in a message of type `String` and convert it to a `Question`. However, this time we use `ReplyingActorRef[IO, Question, Answer]` to guarantee that any replies we receive from the `replyTo` address are of type `Answer`.

### Passing Actor Address within a message
Another common technique in message-passing systems is to include the reply address within the message itself. This approach allows the sender to specify where the response should be sent, providing flexibility for dynamic interactions between actors. By embedding the recipient's address in the message, actors can engage in more complex communication patterns without needing to know each other's addresses in advance.


```scala
import cats.effect.IO
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef

case class Question(text: String, replyTo: ActorRef[IO, Answer])
case class Answer(text: String)

case class RespondingActor() extends Actor[IO, Question] {
  override def receive: Receive[IO, Question] = { case Question(question, replyTo) =>
    for {
      answer <- Answer("complex computation to get the answer...").pure[IO]
      _ <- replyTo ! answer
    } yield answer.text
  }
}

In the receive method, we get a `Question` which contains the question itself and the reply address. The reply address must understand `Answer` messages.

> Note that cats-actors still support `context.sender` and `context.parent`; however, these should be avoided since they are not typed and will require type coercion to send messages to these addresses.

## Context sender and parent
In actor systems, understanding the `context` in which actors operate is crucial for managing communication and
supervision. Two important elements of this `context` are `sender` and `parent`.

### context.sender

Represents the actor that sent the current message. Allows the receiving actor to respond directly to the sender.

````scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

class RespondingActor extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case msg =>
    context.sender.get ! s"Received your message: $msg"
  }
}
````

However note that `context.sender` does not allow us to send messages, we have to force the type to `String` using `unsafeUpcastRequest[String]`` the type that we know the actor supports (we know this implicitly not explicitly).

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

class RespondingActor extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case msg =>
    context.sender.get.unsafeUpcastRequest[String] ! s"Received your message: $msg"
  }
}
```

> In cats-actors, `context.sender` is considered unsafe. Instead, the `ActorRef` should be passed directly in the message or as a closure during the construction of the actor. See the sections above for more information on these techniques.

### context.parent

Represents the parent actor of the current actor. Useful for supervision and hierarchical message passing.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

class ChildActor extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case msg =>
    context.parent ! s"Child received: $msg"
  }
}
```

Note that `context.parent`does not allow us to send messages to the parent since the system is not sure of the type.  We need to coerce the type once again using

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

class ChildActor extends Actor[IO, String] {
  override def receive: Receive[IO,String] = {
    case msg =>
      (context.parent.unsafeUpcastRequest[String] ! s"Child received: $msg")
  }
}
```

### Example: Using context.sender and context.parent
First, let's define the actors that will be used in the example.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef

trait Request
case class Message(msg: String) extends Request
case object GetParent extends Request
case object GetSender extends Request

case class ChildActor() extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = {
    case Message(msg) =>
      IO.println(s"ChildActor received message: $msg from ${context.sender}")
    case GetParent =>
      IO.println(s"ChildActor's parent: ${context.parent}")
    case GetSender =>
      IO.println(s"ChildActor's sender: ${context.sender}")
  }
}

case class ParentActor(childRef: ActorRef[IO, Request]) extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = {
    case Message(msg) =>
      IO.println(s"ParentActor received message: $msg from ${context.sender}") >>
        (childRef ! Message(msg))
    case GetParent =>
      IO.println(s"ParentActor's parent: ${context.parent}")
    case GetSender =>
      IO.println(s"ParentActor's sender: ${context.sender}")
  }
}
```

Next, let's create the main application to demonstrate the usage.

```scala
import cats.effect.{ExitCode, IO, IOApp}
import com.suprnation.actor.ActorSystem
import com.suprnation.typelevel.actors.syntax._

object ActorContextExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource = ActorSystem[IO]("ActorContextExampleSystem")

    actorSystemResource.use { system =>
      for {
        childActor <- system.actorOf(ChildActor(), "child-actor")
        parentActor <- system.actorOf(ParentActor(childActor), "parent-actor")

        // Send a message to the parent actor
        _ <- parentActor ! Message("Hello from main")
        _ <- system.waitForIdle()

        // Get the parent of the child actor
        _ <- childActor ! GetParent
        _ <- system.waitForIdle()

        // Get the sender of the message in the child actor
        _ <- childActor ! GetSender
        _ <- system.waitForIdle()
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **Actor Definitions:**

- `ChildActor`: Receives messages and prints the message along with the sender (`context.sender`). It also handles `GetParent` and `GetSender` messages to print the parent actor and the sender, respectively.
- `ParentActor`: Receives messages and prints the message along with the sender (`context.sender`). It forwards messages to the `ChildActor` and handles `GetParent` and `GetSender` messages to print the parent actor and the sender, respectively.

2. **Main Application:**

- The `ActorContextExampleApp` creates an actor system and defines a `ParentActor` and a `ChildActor`.
- It sends a `Message` to the `ParentActor`, which forwards it to the `ChildActor`.
- It sends `GetParent` and `GetSender` messages to the `ChildActor` to demonstrate the usage of `context.parent` and `context.sender`.

> **Note:** When the main app sends a message, the sender is `None`. This is normal as there is no `ActorRef` from the main app.

## Forwarding of Messages in Actor Systems

Forwarding messages in an actor system means sending a message from one actor to another on behalf of the original sender. This is useful when an actor needs to delegate tasks to other actors without altering the sender information, maintaining the original sender's identity for responses or acknowledgments.

### Example: Forwarding Messages in an Actor System

First, let's define the actors that will be used in the example.

```scala
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef

case class ForwardMessage(msg: String)

case class ForwardActor(
    forwardTo: ActorRef[IO, ForwardMessage],
    ref: Ref[IO, Option[ActorRef[IO, Nothing]]]
) extends Actor[IO, ForwardMessage] {
  override def receive: Receive[IO, ForwardMessage] = { case ForwardMessage(msg) =>
    ref.set(sender) >> forwardTo.forward(ForwardMessage(msg))
  }
}

case class BaseActor(ref: Ref[IO, Option[ActorRef[IO, Nothing]]])
    extends Actor[IO, ForwardMessage] {
  override def receive: Receive[IO, ForwardMessage] = { case ForwardMessage(msg) =>
    ref.set(sender) >> IO.println(s"BaseActor received message: $msg ${context.sender}")
  }
}
```

#### Using .forward Method

Next, let's create an example using the `.forward` method to forward messages.

```scala
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.ActorSystem
import com.suprnation.typelevel.actors.syntax._

object ForwardExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource = ActorSystem[IO]("ForwardExampleSystem")

    actorSystemResource.use { system =>
      for {
        ref <- Ref.of[IO, Option[NoSendActorRef[IO]]](None)
        baseActor <- system.actorOf(BaseActor(ref), "base-actor")
        forwardActor <- system.actorOf(ForwardActor(baseActor, ref), "forward-actor")

        // Send a message to the forward actor, which will forward it to the base actor
        _ <- forwardActor ! ForwardMessage("Hello using .forward")
        _ <- system.waitForIdle()
      } yield ExitCode.Success
    }
  }
}
```

#### Using >>! Syntax

Now, let's create an example using the `>>!` syntax to forward messages.

```scala
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}

case class ForwardMessage(msg: String)

case class ForwardActor(
    forwardTo: ActorRef[IO, ForwardMessage],
    ref: Ref[IO, Option[NoSendActorRef[IO]]]
) extends Actor[IO, ForwardMessage] {
  override def receive: Receive[IO, ForwardMessage] = { case ForwardMessage(msg) =>
    ref.set(sender) >> (forwardTo >>! ForwardMessage(msg))
  }
}

case class BaseActor(ref: Ref[IO, Option[ActorRef[IO, Nothing]]])
    extends Actor[IO, ForwardMessage] {
  override def receive: Receive[IO, ForwardMessage] = { case ForwardMessage(msg) =>
    ref.set(sender) >> IO.println(s"BaseActor received message: $msg ${context.sender}")
  }
}
```

#### Explanation

1. **Actor Definitions:**

- **ForwardActor**: Forwards messages to another actor (`forwardTo`) and sets the sender reference.
- **BaseActor**: Receives forwarded messages and prints them to the console.

2. **Using `.forward` Method:**

- The `ForwardExampleApp` sends a message to `forwardActor`, which forwards it to `BaseActor` using the `.forward` method.

3. **Using `>>!` Syntax:**

- The `ForwardSyntaxExampleApp` sends a message to `forwardActor`, which forwards it to `BaseActor` using the `>>!` syntax.

> **Note:** When an actor forwards a message, `context.sender` remains the original sender, allowing the receiving actor to respond directly to the original sender if needed.

#### Explanation of NoSendActorRef

`NoSendActorRef[IO]` is an alias for `Option[ReplyingActorRef[IO, Nothing, Any]]`, representing an actor address to which we cannot send any messages (`Nothing` indicates no accepted message type), but can be used to reply with any type (`Any`), implying flexibility in responses. This design ensures that the reference can hold an actor's address while restricting its usage to non-sending operations, maintaining type safety and functional constraints within the actor system.

## Supervision
Supervision in an actor system refers to the mechanism by which parent actors manage the lifecycle and failures of their child actors. It ensures fault tolerance and robust error handling in the system.

### Key Concepts

- **Parent-Child Hierarchy**: Actors are organized in a hierarchical structure where parent actors supervise their child actors.
- **Supervision Strategy**: Defines how to handle failures in child actors. Common strategies include:
  - `Restart`: Restarts the failed actor.
  - `Stop`: Stops the actor permanently.
  - `Resume`: Continues the actor's processing without any changes.
  - `Escalate`: Passes the failure up to the parent actor.
- **Supervision Strategies**
  - `Restart Strategy`: Commonly used to handle transient errors. The actor's state is re-initialized, and it can resume processing new messages.
  - `Stop Strategy`: Used when an actor encounters an unrecoverable error or when it no longer needs to process messages.
  - `Resume Strategy`: Allows the actor to continue processing messages without restarting, used for recoverable errors that don't require state re-initialization.
  - `Escalate Strategy`: Passes the failure to the parent actor to decide the next course of action.

Below is an example demonstrating supervision in an actor system using the `cats-actors` library. This example will cover the key concepts and supervision strategies you mentioned.

### Example: Supervision in an Actor System

First, define the parent and child actors with different supervision strategies.

```scala
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.SupervisorStrategy._
import com.suprnation.actor.{OneForOneStrategy, SupervisionStrategy}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

trait Request
case object Fail extends Request
case object Illegal extends Request
case class Message(msg: String) extends Request

case class ParentActor(ref: Ref[IO, List[ActorRef[IO, Request]]]) extends Actor[IO, Request] {
  override def supervisorStrategy: SupervisionStrategy[IO] =
    OneForOneStrategy[IO](maxNrOfRetries = 3, withinTimeRange = 1 minute) {
      case _: IllegalArgumentException => Stop
      case _: RuntimeException         => Restart
      case _: Exception                => Escalate
    }

  override def preStart: IO[Unit] =
    ref.update(xs => xs :+ context.actorOf(ChildActor(), "child-actor").void)

  override def receive: Receive[IO, Request] = { case msg =>
    for {
      children <- ref.get
      _ <- children.traverse_(child => child ! msg)
    } yield ()
  }
}

case class ChildActor() extends Actor[IO, Request] {
  override def receive: Receive[IO, Request] = {
    case Fail    => IO.raiseError(new RuntimeException("Child actor failed (restarting)!"))
    case Illegal => IO.raiseError(new IllegalArgumentException("Illegal argument (stopping)!"))
    case msg     => IO.println(s"Child actor received: $msg")
  }
}
```

Next, create an actor system to manage the actors and send messages to demonstrate the supervision strategies.

```scala
import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ActorSystem

import scala.concurrent.duration._
import scala.language.postfixOps

object SupervisionExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("SupervisionExampleSystem")

    actorSystemResource.use { system =>
      for {
        ref <- Ref[IO].of(List.empty[ActorRef[IO, Request]])
        parentActor <- system.actorOf(ParentActor(ref), "parent-actor")
        _ <- parentActor ! Message("Hello, Actor!")
        _ <- parentActor ! Fail // This will cause the child actor to restart
        _ <- parentActor ! Message("Hello again!")
        _ <- parentActor ! Illegal // This will cause the child actor to stop
        _ <- parentActor ! Message("Are you there?")
        _ <- IO.sleep(1.second) // Give some time for the messages to be processed
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

**1. Define the Actors:**
- `ParentActor`:
  - Defines a `supervisorStrategy` using `OneForOneStrategy` with different handling for various exceptions.
  - Creates a child actor in the `preStart` method and takes a reference (`ref`) which will hold the list of children actor addresses created.
  - Forwards received messages to the child actor.
- `ChildActor`:
  - Raises different exceptions based on the received message to demonstrate the supervision strategies.
  - Prints received messages.

**2. Create the Actor System and Send Messages:**

- `SupervisionExampleApp`:
  - Creates an actor system named "SupervisionExampleSystem".
  - Creates a `ParentActor` which in turn creates a `ChildActor`.
  - Sends messages to the `ParentActor` to demonstrate the supervision strategies:
    - Sends a normal message.
    - Sends a "fail" message to cause the child actor to restart.
    - Sends another normal message.
    - Sends an "illegal" message to cause the child actor to stop.
    - Sends another normal message to check if the child actor is still there. The actor will not be there, and the message is re-routed to the `dead-letter` queue.

### Supervision Strategies Demonstrated

- **Restart**: When the `ChildActor` receives the "fail" message, it raises a `RuntimeException`, causing the `ParentActor` to restart it.
- **Stop**: When the `ChildActor` receives the "illegal" message, it raises an `IllegalArgumentException`, causing the `ParentActor` to stop it.
- **Escalate**: Any other exceptions would be escalated to the parent actor, but this is not demonstrated in this example.

This example demonstrates how to implement and use supervision strategies in an actor system using the `cats-actors` library.

## Actor Lifecycle Hooks

Actor lifecycle hooks provide methods to perform actions during different phases of an actor's lifecycle. These hooks
allow for initialization, cleanup, and handling restarts.

**Available Hooks**

- `preStart`

  - Called before the actor starts processing messages.
  - Useful for initialization tasks.

  ```scala
     override def preStart(): IO[Unit] = IO(println("Actor is starting"))
  ```
- `postStop`

  - Called after the actor has been stopped.
  - Useful for cleanup tasks.

  ```scala
  override def postStop(): IO[Unit] = IO(println("Actor has stopped"))
  ```
- `preRestart`

  - Called before the actor is restarted after a failure.
  - Allows for cleanup before restarting.

  ```scala
  override def preRestart(reason: Throwable, message: Option[Any]): IO[Unit] =
    IO(println(s"Actor is restarting due to: ${reason.getMessage}"))
  ```
- `postRestart`

  - Called after the actor has been restarted.
  - Used to reinitialize the actor.

  ```scala
  override def postRestart(reason: Throwable): IO[Unit] =
    IO(println("Actor has been restarted"))
  ```
- `preSuspend`

  - This method is called before an actor is suspended, typically due to an error or some other condition that
    requires the actor to be temporarily halted.
  - This method takes two optional parameters:
    - `reason`: An optional Throwable that indicates the reason for the suspension.
    - `message`: An optional message that might have caused the suspension.

  ```scala
  override def preSuspend(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
      IO.println(s"Actor is being suspended due to: ${reason.map(_.getMessage).getOrElse("unknown reason")}")
      .flatMap(_ => reason.fold(IO.unit)(t => IO(t.printStackTrace())))
  ```

  > **Note:** Only the actor which caused the error will receive the message. This is to ensure that no actor receives messages which are not intended for them
  > and thus result in information leak.


### Example: Actor hooks

Let's update the example to make use of the lifecycle hooks such as `preStart`, `postStop`, `preRestart`, `postRestart`, and `preSuspend`. These hooks allow you to define custom behavior at different stages of an actor's lifecycle.

```scala
import cats.effect._
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.SupervisorStrategy._
import com.suprnation.actor.{ActorSystem, OneForOneStrategy, SupervisionStrategy}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

trait Request
case object Fail extends Request
case object Illegal extends Request
case class Message(msg: String) extends Request

case class ParentActor(ref: Ref[IO, List[ActorRef[IO, Request]]]) extends Actor[IO, Request] {
  override def supervisorStrategy: SupervisionStrategy[IO] =
    OneForOneStrategy[IO](maxNrOfRetries = 3, withinTimeRange = 1 minute) {
      case _: IllegalArgumentException => Stop
      case _: RuntimeException         => Restart
      case _: Exception                => Escalate
    }

  override def preStart: IO[Unit] =
    for {
      _ <- IO.println("ParentActor is starting.")
      _ <- context.actorOf(ChildActor(), "child-actor").void
    } yield ()

  override def postStop: IO[Unit] =
    IO.println("ParentActor has stopped.")

  override def preRestart(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
    for {
      _ <- IO.println(
        s"ParentActor is restarting due to: ${reason.fold("No Reason")(_.getMessage)}"
      )
      _ <- super.preRestart(reason, message)
    } yield ()

  override def postRestart(reason: Option[Throwable]): IO[Unit] =
    IO.println(s"ParentActor has restarted due to: ${reason.fold("No Reason")(_.getMessage)}")

  override def preSuspend(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
    for {
      _ <- IO.println(
        s"ParentActor is being suspended due to: ${reason.map(_.getMessage).getOrElse("unknown reason")}"
      )
      _ <- reason.fold(IO.unit)(t => IO(t.printStackTrace()))
      _ <- super.preSuspend(reason, message)
    } yield ()

  override def receive: Receive[IO, Request] = { case msg =>
    for {
      children <- ref.get
      _ <- children.traverse_(child => child ! msg)
    } yield ()
  }
}

case class ChildActor() extends Actor[IO, Request] {
  override def preStart: IO[Unit] =
    IO.println("ChildActor is starting.")

  override def postStop: IO[Unit] =
    IO.println("ChildActor has stopped.")

  override def preRestart(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
    for {
      _ <- IO.println(
        s"ChildActor is restarting due to: ${reason.map(_.getMessage).getOrElse("unknown reason")}"
      )
      _ <- super.preRestart(reason, message)
    } yield ()

  override def postRestart(reason: Option[Throwable]): IO[Unit] =
    for {
      _ <- IO.println(
        s"ChildActor has restarted due to: ${reason.map(_.getMessage).getOrElse("unknown reason")}"
      )
      _ <- super.postRestart(reason)
    } yield ()

  override def preSuspend(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
    for {
      _ <- IO.println(
        s"ChildActor is being suspended due to: ${reason.map(_.getMessage).getOrElse("unknown reason")}"
      )
      _ <- reason.fold(IO.unit)(t => IO(t.printStackTrace()))
      _ <- super.preSuspend(reason, message)
    } yield ()

  override def receive: Receive[IO, Request] = {
    case Fail    => IO.raiseError(new RuntimeException("Child actor failed (restarting)!"))
    case Illegal => IO.raiseError(new IllegalArgumentException("Illegal argument (stopping)!"))
    case Message(msg) => IO.println(s"Child actor received: $msg")
  }
}

object SupervisionExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("SupervisionExampleSystem")

    actorSystemResource.use { system =>
      for {
        children <- Ref[IO].of(List.empty[ActorRef[IO, Request]])
        parentActor <- system.actorOf(ParentActor(children), "parent-actor")
        _ <- parentActor ! Message("Hello, Actor!")
        _ <- parentActor ! Fail // This will cause the child actor to restart
        _ <- parentActor ! Message("hello again!")
        _ <- parentActor ! Illegal // This will cause the child actor to stop
        _ <- parentActor ! Message("Are you there?")
        _ <- IO.sleep(1.second) // Give some time for the messages to be processed
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

#### Lifecycle Hooks

1. `preStart`:

- Called before the actor starts processing messages.
- Used to initialize resources or perform setup tasks.
- In `ChildActor`, it prints a message indicating that the actor is starting.

2. `postStop`:

- Called after the actor has stopped processing messages.
- Used to clean up resources or perform teardown tasks.
- In `ChildActor`, it prints a message indicating that the actor has stopped.

3. `preRestart`:

- Called before the actor is restarted due to a failure.
- Used to perform tasks before the actor restarts, such as cleaning up resources.
- In `ChildActor`, it prints a message indicating the reason for the restart and calls the superclass's `preRestart` method to ensure any default behavior is executed.

4. `postRestart`:

- Called after the actor has restarted due to a failure.
- Used to perform tasks after the actor restarts, such as reinitializing resources.
- In `ChildActor`, it prints a message indicating the reason for the restart and calls the superclass's `postRestart` method to ensure any default behavior is executed.

5. `preSuspend`:

- Called before the actor is suspended.
- Used to perform tasks before the actor is suspended, such as logging the reason for suspension.
- In `ChildActor`, it prints a message indicating the reason for suspension, prints the stack trace if a `Throwable` is provided, and calls the superclass's `preSuspend` method to ensure any default behavior is executed.

#### `receive` Method

The `receive` method defines how the actor handles incoming messages. In `ChildActor`, it handles three types of messages:

1. Fail:

- Raises a `RuntimeException` with a message indicating that the child actor failed and will be restarted.

2. Illegal:

- Raises an `IllegalArgumentException` with a message indicating that an illegal argument was provided and the actor will be stopped.

3. Message:

- Prints the received message to the console.

#### Output

The expected output from this run is as follows:

On startup of the system you should see:

```bash
ParentActor is starting.
ChildActor is starting.
```

When the first message is received:

```bash
Child actor received: Hello, Actor! // First message received
```

When the "fail" message is received, the parent should restart the child actor:

```bash
ChildActor is being suspended due to: Child actor failed (restarting)! // Received fail (preSuspend hook)
ChildActor is restarting due to: Child actor failed (restarting)! // Received fail (preRestart hook)
ChildActor has stopped. // Received fail (postStop hook)
ChildActor has restarted due to: Child actor failed (restarting)! // Received fail (postRestart hook)
ChildActor is starting. // Received fail (preStart hook)
```

When "Hello again!" is received:

```bash
Child actor received: Hello again! // 3rd message "Hello again!"
```

When "illegal" is received, the parent should terminate the child actor:

```bash
ChildActor is being suspended due to: Illegal argument (stopping)! // Received illegal (preSuspend hook)
ChildActor has stopped. // Received illegal (postStop hook)
```

Any subsequent message ("Are you there?") will flow to the dead-letter queue:

```bash
[EventBus] => Debug([Path: localhost/dead-letter] DeadLetter(Envelope(Are you there?,Some([System: SupervisionExampleSystem] ... [name: child-actor]}))) // Message "Are you there?" flows to the dead-letter
```

===

## Actor Watch

Actor watch is a mechanism in actor systems that allows one actor to monitor the lifecycle of another actor. When an
actor watches another actor, it will be notified if the watched actor stops, either normally or due to failure.

*Key Concepts*

- **Watching**: An actor can start watching another actor using the watch method.
- **Termination Notifications**: If the watched actor stops, the watcher actor receives a the user defined termination message.
- **Unwatching**: An actor can stop watching another actor using the unwatch method.

### Example: Watching Actors

Let's define two actors: `ParentActor` and `ChildActor`. The `ParentActor` will watch the `ChildActor` and handle its termination.

`ParentActor` and `ChildActor` Definitions

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.NoSendActorRef

trait Request
case object StopChild extends Request
case class Message(message: String) extends Request
case class ActorTerminated(actorRef: NoSendActorRef[IO]) extends Request

case class ParentActor() extends Actor[IO, Request] {
  override def preStart: IO[Unit] =
    for {
      _ <- IO.println("ParentActor is starting.")
      child <- context.actorOf(ChildActor(), "child-actor")
      _ <- context.watch(child, ActorTerminated(child)) // Watch the child actor
    } yield ()

  override def receive: Receive[IO, Request] = {
    case StopChild =>
      for {
        children <- context.children
        _ <- children.headOption.fold(IO.unit)(child => context.stop(child))
      } yield ()
    case ActorTerminated(ref) =>
      IO.println(s"ParentActor received termination notification for: ${ref.path.name}")
  }
}

case class ChildActor() extends Actor[IO, Request] {
  override def preStart: IO[Unit] =
    IO.println("ChildActor is starting.")

  override def postStop: IO[Unit] =
    IO.println("ChildActor has stopped.")

  override def receive: Receive[IO, Request] = { case msg =>
    IO.println(s"Child actor received: $msg")
  }
}
```

Next, let's create an `IOApp` to run the example. The `ParentActor` will watch the `ChildActor`, and we will send a message to stop the `ChildActor` to see how the `ParentActor` handles the termination notification.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

import scala.concurrent.duration._
import scala.language.postfixOps

object ActorWatchExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("ActorWatchExampleSystem")

    actorSystemResource.use { system =>
      for {
        parentActor <- system.actorOf(ParentActor(), "parent-actor")
        _ <- parentActor ! StopChild // Send a message to stop the child actor
        _ <- IO.sleep(1.second) // Give some time for the messages to be processed
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. `ParentActor`:

- In the `preStart` hook, it creates a `ChildActor` and starts watching it using the `watch` method.
- In the `receive` method, it handles two types of messages:
  - `StopChild`: Stops the child actor.
  - `ActorTerminated(ref)`: Handles the termination notification for the watched actor and prints a message.

2. `ChildActor`:
- Prints a message when it starts and stops.
- In the `receive` method, it prints any received message.

3. `ActorWatchExampleApp`:
- Creates an `ActorSystem` and a `ParentActor`.
- Sends a message to the `ParentActor` to stop the `ChildActor` - `StopChild`.
- Waits for a short period to allow the messages to be processed.

> **Note:** The `context.watch(child, ActorTerminated(child))` instruction tells the system to send the `ActorTerminated(actorRef)` message when the actor is terminated.

## Scheduling messages

Let's create an example that demonstrates how to use the `Scheduler` class and its `scheduleOnce` method to schedule a task to run after a specified delay.

### Example: Using Scheduler.scheduleOnce

In this example, we'll create an actor that schedules a task to print a message to the console after a delay of 5 seconds.

**Actor Definition**

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

import scala.concurrent.duration._

case object ScheduleTask

case class SchedulerActor() extends Actor[IO, ScheduleTask.type] {
  override def preStart: IO[Unit] =
    // Schedule a task to run after a delay of 5 seconds
    context.system.scheduler
      .scheduleOnce(5.seconds) {
        IO.println("Task executed after delay!")
      }
      .void

  override def receive: Receive[IO, ScheduleTask.type] = { case ScheduleTask =>
    IO.println("Received ScheduleTask message")
  }
}
```

Next, let's create an `IOApp` to run the example. We'll create an `ActorSystem` and a `SchedulerActor`, and then send a message to observe the scheduling behavior.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

import scala.concurrent.duration._
import scala.language.postfixOps

object SchedulerActorExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("SchedulerActorSystem")

    actorSystemResource.use { system =>
      for {
        schedulerActor <- system.actorOf(SchedulerActor(), "scheduler-actor")
        _ <- schedulerActor ! ScheduleTask
        // Wait for 6 seconds to ensure the scheduled task gets executed
        _ <- IO.sleep(6.seconds)
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **SchedulerActor**:

- In the `preStart` method, `context.system.scheduler.scheduleOnce` is used to schedule a task to run after a delay of 5 seconds. The task prints a message to the console.
- The `receive` method handles messages sent to the actor. In this example, it prints a message when it receives a `ScheduleTask` message.

2. **SchedulerActorExampleApp**:

- Creates an `ActorSystem` using a resource.
- Creates a `SchedulerActor` and sends a `ScheduleTask` message to it.
- Waits for 6 seconds to ensure that the scheduled task gets executed.

## State Management - Context `become` / `unbecome`

In an actor system, `become` and `unbecome` are used to change an actor's behavior dynamically at runtime. This allows actors to handle different types of messages or states over time.

**Become**

- The `become` method changes the current behavior of the actor to a new behavior. This is useful for managing state transitions within an actor. The behavior is changed on the next message.

**Unbecome**

- The `unbecome` method reverts the actor's behavior to the previous one. This is typically used to handle state transitions that need to revert back to an original or default behavior. The behavior is changed on the next message.

### Example: Context `become` and `unbecome`

In this example, we'll create an actor that can switch between two behaviors: happy and angry. The actor will start in the happy state and can switch to the angry state when it receives a specific message. It can also switch back to the happy state.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

sealed trait MoodActorRequests
case object MakeAngry extends MoodActorRequests
case object MakeHappy extends MoodActorRequests
case object Greet extends MoodActorRequests

case class MoodActor() extends Actor[IO, MoodActorRequests] {
  def happy: Receive[IO, MoodActorRequests] = {
    case MakeAngry =>
      IO.println("Switching to angry state...") >> context.become(angry)
    case Greet =>
      IO.println("Hello! I'm happy!")
  }

  def angry: Receive[IO, MoodActorRequests] = {
    case MakeHappy =>
      IO.println("Switching to happy state...") >> context.unbecome
    case Greet =>
      IO.println("Go away! I'm angry!")
  }

  override def receive: Receive[IO, MoodActorRequests] = happy
}
```

Next, let's create an `IOApp` to run the example. We'll create an `ActorSystem` and a `MoodActor`, and then send messages to switch between states and observe the behavior.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

object MoodActorExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("MoodActorSystem")

    actorSystemResource.use { system =>
      for {
        moodActor <- system.actorOf(MoodActor(), "mood-actor")
        _ <- moodActor ! Greet // Should print: "Hello! I'm happy!"
        _ <- moodActor ! MakeAngry // Should switch to angry state
        _ <- moodActor ! Greet // Should print: "Go away! I'm angry!"
        _ <- moodActor ! MakeHappy // Should switch back to happy state
        _ <- moodActor ! Greet // Should print: "Hello! I'm happy!"
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **MoodActor**:

- Defines two behaviors: `happy` and `angry`.
- The `happy` behavior handles two messages:
  - `MakeAngry`: Switches to the `angry` state using `context.become`.
  - `Greet`: Prints a happy greeting.
- The `angry` behavior handles two messages:
  - `MakeHappy`: Switches back to the `happy` state using `context.unbecome`.
  - `Greet`: Prints an angry greeting.
- The initial behavior is set to `happy` in the `receive` method.

2. **MoodActorExampleApp**:

- Creates an `ActorSystem` and a `MoodActor`.
- Sends a series of messages to the `MoodActor` to switch between states and observe the behavior:
  - `Greet`: Prints the current greeting based on the state.
  - `MakeAngry`: Switches to the angry state.
  - `MakeHappy`: Switches back to the happy state.

This example demonstrates how to use `context.become` and `context.unbecome` to change an actor's behavior dynamically. The `MoodActor` switches between `happy` and `angry` states based on the received messages, showcasing the flexibility of the actor model in handling different behaviors.

## State Management - Ref's and Call-by-Name Actor

`become` and `unbecome` can be useful for state management in actors, but they might become cumbersome when the state needs to be updated from multiple places or grows too large. In such cases, using a `Ref` can be a better alternative.

### Example: Using Refs

We'll create an actor that can switch between two behaviors: `Happy` and `Angry`. The actor will start in the `Happy` state and can switch to the `Angry` state when it receives `MakeAngry`. It can also switch back to the `Happy` state upon receiving a `MakeHappy` message. We'll use `Refs` to manage the state.

```scala
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.{Actor, Receive}

sealed trait MoodActorRequests
case object MakeAngry extends MoodActorRequests
case object MakeHappy extends MoodActorRequests
case object Greet extends MoodActorRequests

sealed trait Mood
case object Happy extends Mood
case object Angry extends Mood

case class MoodActor(state: Ref[IO, Mood]) extends Actor[IO, MoodActorRequests] {

  override def receive: Receive[IO, MoodActorRequests] = {
    case MakeAngry =>
      for {
        _ <- IO.println("Switching to angry state...")
        _ <- state.set(Angry)
      } yield ()

    case MakeHappy =>
      for {
        _ <- IO.println("Switching to happy state...")
        _ <- state.set(Happy)
      } yield ()
    case Greet =>
      state.get.flatMap {
        case Angry =>
          IO.println("Go away! I'm angry!")
        case Happy =>
          IO.println("Hello! I'm happy!")
      }
  }
}

object MoodActor {
  def make: IO[MoodActor] = Ref.of[IO, Mood](Happy).map(MoodActor(_))
}
```

Next, let's create an `IOApp` to run the example. We'll create an `ActorSystem` and a `MoodActor`, and then send messages to switch between states and observe the behavior.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

object MoodActorExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("MoodActorSystem")

    actorSystemResource.use { system =>
      for {
        moodActor <- system.actorOf(MoodActor.make, "mood-actor")
        _ <- moodActor ! Greet // Should print: "Hello! I'm happy!"
        _ <- moodActor ! MakeAngry // Should switch to angry state
        _ <- moodActor ! Greet // Should print: "Go away! I'm angry!"
        _ <- moodActor ! MakeHappy // Should switch back to happy state
        _ <- moodActor ! Greet // Should print: "Hello! I'm happy!"
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **MoodActor**:

- Uses a `Ref` to manage the state (`Happy` or `Angry`).
- Handles state transitions directly by updating the `Ref`.
- Handles messages:
  - `MakeAngry`: Switches to the `Angry` state and updates the `Ref`.
  - `MakeHappy`: Switches to the `Happy` state and updates the `Ref`.
  - `Greet`: Prints a message based on the current state.

2. **MoodActor.make**:

- Creates a `Ref` to hold the initial state (`Happy`).
- Returns an `IO` instance that creates a `MoodActor` with the `Ref`.

3. **MoodActorExampleApp**:

- Creates an `ActorSystem` and a `MoodActor`.
- Sends a series of messages to the `MoodActor` to switch between states and observe the behavior:
  - `Greet`: Prints the current greeting based on the state.
  - `MakeAngry`: Switches to the angry state.
  - `MakeHappy`: Switches back to the happy state.

4. **Mood**:
- `Mood` is a sealed trait representing the state of the actor.
- It has two possible states: `Happy` and `Angry`.
- These states are used to determine the actor's behavior when it receives messages.


### Example: Inline Alternative
The helper method `MoodActor.make` is not necessary because the `actorOf` method can directly accept a call-by-name actor instance or an `Applicative[F]`. This allows the `MoodActor` to be defined inline, streamlining the actor creation process. Below is an example demonstrating this approach:

```scala
import cats.effect._
import com.suprnation.actor.ActorSystem

object MoodActorExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("MoodActorSystem")

    actorSystemResource.use { system =>
      for {
        moodActor <- system.actorOf(
          for {
            state <- Ref.of[IO, Mood](Happy)
          } yield MoodActor(state),
          "mood-actor"
        )
        _ <- moodActor ! Greet // Should print: "Hello! I'm happy!"
        _ <- moodActor ! MakeAngry // Should switch to angry state
        _ <- moodActor ! Greet // Should print: "Go away! I'm angry!"
        _ <- moodActor ! MakeHappy // Should switch back to happy state
        _ <- moodActor ! Greet // Should print: "Hello! I'm happy!"
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

## Timeouts in Actors

Timeouts in actor systems are used to schedule actions or messages to be sent after a specified duration. This can be
useful for implementing retries, periodic tasks, or handling inactivity.

### `context.setReceiveTimeout`

The `context.setReceiveTimeout` method allows you to schedule a message to be sent to an actor if no other messages are received within a certain period.

### Example: Using context.setReceiveTimeout

In this example, we'll create an actor that sets a receive timeout. If the actor does not receive any messages within the specified duration, it will perform a timeout action.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

import scala.concurrent.duration._

sealed trait Requests
case object Timeout extends Requests
case class Message(msg: String) extends Requests

case class TimeoutActor() extends Actor[IO, Requests] {
  override def preStart: IO[Unit] =
    // Set the receive timeout to 5 seconds
    context.setReceiveTimeout(5.seconds, Timeout)

  override def receive: Receive[IO, Requests] = {
    case Timeout =>
      IO.println("Timeout occurred! No messages received within the timeout period.")
    case Message(msg) =>
      IO.println(s"Received message: $msg")
  }
}
```

Next, let's create an `IOApp` to run the example. We'll create an `ActorSystem` and a `TimeoutActor`, and then send messages to observe the timeout behavior.

```scala
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.suprnation.actor.ActorSystem

import scala.concurrent.duration._
import scala.language.postfixOps

object TimeoutActorExampleApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val actorSystemResource: Resource[IO, ActorSystem[IO]] =
      ActorSystem[IO]("TimeoutActorSystem")

    actorSystemResource.use { system =>
      for {
        timeoutActor <- system.actorOf(TimeoutActor(), "timeout-actor")
        _ <- IO.sleep(3.seconds)
        _ <- timeoutActor ! Message("Hello") // Should print: "Received message: Hello"
        _ <- IO.sleep(6.seconds) // Should trigger the timeout action
        _ <- timeoutActor ! Message(
          "Another message"
        ) // Should print: "Received message: Another message"
        _ <- IO.sleep(6.seconds) // Should trigger the timeout action again
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **TimeoutActor**:

- Sets a receive timeout of 5 seconds in the `preStart` method using `context.setReceiveTimeout`.
- Defines a `receive` method to handle messages:
  - If a `Timeout` message is received, it prints a timeout message.
  - For any other message, it prints the received message.

2. **TimeoutActorExampleApp**:

- Creates an `ActorSystem` and a `TimeoutActor`.
- Sends a `Timeout` message to the `TimeoutActor` after 3 seconds, which should print the received message and reset the timeout.
- Waits for 6 seconds to trigger the timeout action, which should print the timeout message.
- Sends another message to the `TimeoutActor`, which should print the received message and reset the timeout.
- Waits for another 6 seconds to trigger the timeout action again.

> **Note:** The `context.setReceiveTimeout(5.seconds, Timeout)` method schedules the user-defined `Timeout` message to be sent to the actor if no other messages are received within 5 seconds. This allows the actor to perform a specific action (such as logging or cleaning up resources) if it becomes inactive.

## Finite State Machine (FSM) in Actor Systems
Cats-Actors comes with a powerful Finite State Machine (FSM) out of the box, allowing you to manage complex actor behaviors and state transitions seamlessly. By leveraging the FSM, you can define multiple states and transitions based on received messages, ensuring your actors handle various scenarios effectively.

**Key Benefits**

- **State Management:** Easily define and manage multiple states within your actors.
- **Transitions:** Define transitions based on specific events, making your actor's behavior more predictable and maintainable.
- **Functional Programming:** Utilize the power of Cats Effect and functional programming principles for robust and clean state management.
- **Debugging:** Built-in support for debugging and logging state transitions, aiding in development and troubleshooting.

### Example FSM with Debugging Support

First, let's define the messages that can be received by our state machine:

```scala
sealed trait Request
case object Start extends Request
case object Stop extends Request
```

Next, let's define the states and data that the FSM will manage.

```scala
sealed trait State

case object Idle extends State

case object Active extends State

case class Data(counter: Int)
```

Next, define the FSM using the `FSMBuilder` and include debugging support with `withConsoleInformation`.

```scala
import cats.effect._
import cats.implicits._
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.actor.fsm._

object ExampleFSMApp extends IOApp {

  sealed trait Request
  case object Start extends Request
  case object Stop extends Request

  sealed trait State

  case object Idle extends State

  case object Active extends State

  case class Data(counter: Int)

  override def run(args: List[String]): IO[ExitCode] = {
    val fsm: IO[ReplyingActor[IO, Request, List[Any]]] = FSM[IO, State, Data, Request, List[Any]]
      .withConfig(FSMConfig.withConsoleInformation[IO, State, Data, Request, List[Any]])
      .when(Idle) (stateManager => { case FSM.Event(Start, data) =>
        for {
          newState <- stateManager.goto(Active)
        } yield newState.using(data.copy(counter = data.counter + 1))
      })
      .when(Active) (stateManager => { case FSM.Event(Stop, data) =>
        for {
          newState <- stateManager.goto(Idle)
        } yield newState.using(data.copy(counter = data.counter + 1))
      })
      .onTransition {
        case (Idle, Active) => IO.println("Transitioning from Idle to Active")
        case (Active, Idle) => IO.println("Transitioning from Active to Idle")
      }
      .startWith(Idle, Data(0))
      .initialize

    ActorSystem[IO]("example-fsm-system").use { system =>
      for {
        fsmActor <- system.replyingActorOf[Request, List[Any]](fsm)
        _ <- fsmActor ! Start
        _ <- fsmActor ! Stop
        _ <- fsmActor ! Start
        _ <- fsmActor ! Stop
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

### Explanation

1. **Request, States, and Data**:

- `Start` and `Stop` are the messages that can be received by our state machine.
- `Idle` and `Active` are the states.
- `Data` holds a counter to track state transitions.

2. **FSM Definition**:

- `FSMBuilder` is used to define state transitions and include debugging support with `FSMConfig.withConsoleInformation`.
  - `when(Idle)` and `when(Active)` define the state functions for handling events.
  - `onTransition` defines actions to take during state transitions.

3. **FSM Initialization**:

- `startWith(Idle, Data(0))` initializes the FSM with the `Idle` state and a counter set to 0.

4. **Actor System**:

- An actor system is created, and the FSM is instantiated as an actor.
- Messages (`Start` and `Stop`) are sent to the FSM actor to trigger state transitions.
- The actor system waits for termination.

### Highlighted Details

1. **Typed FSM**:

The FSM is fully typed and will receive messages of type `Request`, which is defined as:
  ```scala
  sealed trait Request
  case object Start extends Request
  case object Stop extends Request
  ```

The response type `List[Any]` is explained further below.


2. **Timeout Mechanism**:

- `context.setReceiveTimeout(5.seconds, Timeout)` is used to schedule a timeout.
- When the timeout occurs, the user-defined `Timeout` message is sent to the user actor.


3. **Replying FSM**:

- The FSM may reply to a message based on its corresponding state transition. 
- The reply can be in the form of a returned response that fulfils the ask (`?`) pattern, similarly to any other actor in cats-actors (`ReturnResponse` reply type). 
- For compatibility with the original Akka implementation, the FSM can also reply by sending a message back to the original sender (`SendMessage` reply type).
- The FSM can also do both, using the `BothMessageAndResponse` reply type.
- Depending on its implementation, an FSM may not respond at all, or respond with a single, or multiple replies. This is modelled by the requirement for the response type to be monoidal. If no reply is defined, the identity element is returned or sent, otherwise any replies are combined together in one. This is the reason for the response type `List[Any]` used in the example above.
- Replies can be defined per state, using the `replying` method with the appropriate reply type, with `SendMessage` as the default. A `returning` method is also provided as a default for the `ReturnResponse` type. Subsequent uses of these methods add further replies to the state, which are combined in the monoidal reponse type.


## Common Use-cases - Integrate with a messaging queue

Integrating cats-actors with a queuing system like RabbitMQ is a common scenario. In this example, we'll demonstrate how to push messages from RabbitMQ onto an fs2.Stream and pipe these messages into the actor system for continuous processing.

First, let's create an AMQP stream companion object that defines an AMQP (Advanced Message Queuing Protocol) producer and consumer using Cats Effect and RabbitMQ. This object will include methods to create connections and channels, and to produce and consume messages from a RabbitMQ queue.

```scala
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.rabbitmq.client._

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

object AmqpStream {

  import fs2.Stream

  val queueName = "messages"

  private def createConnection(factory: ConnectionFactory): Resource[IO, Connection] =
    Resource.make(IO.blocking(factory.newConnection()))(conn => IO.blocking(conn.close()))

  private def createChannel(connection: Connection): Resource[IO, Channel] =
    Resource.make(IO.blocking(connection.createChannel()))(channel => IO.blocking(channel.close()))

  private def createConnectionFactory(
                                       connectionFactoryConfig: AmqpConnectionFactoryConfig
                                     ): ConnectionFactory = {
    val connectionFactory: ConnectionFactory = new ConnectionFactory()
    connectionFactory.setHost(connectionFactoryConfig.host)
    connectionFactory.setVirtualHost(connectionFactoryConfig.virtualHost)
    connectionFactory.setUsername(connectionFactoryConfig.username)
    connectionFactory.setPassword(connectionFactoryConfig.password)
    connectionFactory.setPort(connectionFactoryConfig.port)
    if (connectionFactoryConfig.ssl) connectionFactory.useSslProtocol()
    connectionFactory
  }

  def amqpProducer(
                    connectionFactoryConfig: AmqpConnectionFactoryConfig
                  ): Resource[IO, String => IO[Unit]] = {
    val connectionFactory: ConnectionFactory = createConnectionFactory(connectionFactoryConfig)
    val amqpProperties: AMQP.BasicProperties = new AMQP.BasicProperties.Builder().build()

    for {
      connection <- createConnection(connectionFactory)
      channel <- createChannel(connection)
      _ <- Resource.eval(
        IO(channel.queueDeclare(queueName, false, false, false, Map.empty[String, AnyRef].asJava))
      )
    } yield (message: String) =>
      IO(channel.basicPublish("", queueName, amqpProperties, message.getBytes()))
  }

  def amqpConsumerStream(
                          connectionFactoryConfig: AmqpConnectionFactoryConfig
                        ): Stream[IO, String] = {
    val connectionFactory: ConnectionFactory = createConnectionFactory(connectionFactoryConfig)

    for {
      connection <- Stream.resource(createConnection(connectionFactory))
      channel <- Stream.resource(createChannel(connection))
      queue <- Stream.eval(Queue.unbounded[IO, String])
      stream <- {
        val deliverCallback: DeliverCallback = (_, delivery) =>
          queue.offer(new String(delivery.getBody, StandardCharsets.UTF_8)).unsafeRunSync()
        Stream
          .eval(IO {
            channel.basicQos(2048)
            channel.basicConsume(queueName, true, deliverCallback, (_: String) => {})
          })
          .drain ++ Stream.fromQueueUnterminated(queue)
      }
    } yield stream
  }

}
```

### Explanation

#### amqpProducer Method

The `amqpProducer` method is responsible for creating a producer that can send messages to a RabbitMQ queue. Here's a step-by-step overview:

1. **Connection Factory:** It creates a ConnectionFactory using the provided configuration.
2. **AMQP Properties:** It defines basic properties for the AMQP messages.
3. **Resource Management:**

- Connection: It creates a connection to the RabbitMQ server.
- Channel: It creates a channel on the connection.
- Queue Declaration: It declares the queue if it doesn't already exist.

4. **Message Publishing:** It returns a function that takes a message as input and publishes it to the queue using the channel.

#### amqpConsumerStream Method

The `amqpConsumerStream` method is responsible for creating a stream that consumes messages from a RabbitMQ queue. Here's a step-by-step overview:

1. **Connection Factory:** It creates a ConnectionFactory using the provided configuration.
2. **Stream Management:**

- Connection: It creates a connection to the RabbitMQ server as a stream resource.
- Channel: It creates a channel on the connection as a stream resource.
- Queue: It creates an unbounded queue to hold the messages.

3. **Message Consumption:**

- Deliver Callback: It defines a callback to handle message delivery, which offers messages to the queue.
- Stream Construction:
  - Quality of Service: It sets the Quality of Service for the channel.
  - Message Consumption: It starts consuming messages from the queue using the deliver callback.
  - Stream Composition: It combines the consuming stream with the queue stream to produce a continuous stream of messages.

Now that we've covered the boilerplate, let's create a complete application that pipes messages from an `fs2.Stream` to an actor.

### Example: Processing via a single actor

```scala
import cats.effect._
import cats.implicits._
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.{Actor, ActorSystem}

import scala.concurrent.duration._

object SingleActorRabbitApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val connectionConfig = AmqpConnectionFactoryConfig("localhost", "/", "guest", "guest")

    val resources = for {
      consumer <- Resource.pure(AmqpStream.amqpConsumerStream(connectionConfig))
      producer <- AmqpStream.amqpProducer(connectionConfig)
      actorSystem <- ActorSystem[IO]("cats-actors-rabbit-sample")
    } yield (consumer, producer, actorSystem)

    resources.use { case (consumer, producer, actorSystem) =>
      for {
        actor <- actorSystem.actorOf(new Actor[IO, String] {
          override def receive: Receive[IO, String] = {
            case msg =>
              IO.println(msg)
          }
        })
        _ <- consumer.evalTapChunk(actor ! _).compile.drain.start
        _ <- List("1", "2", "3", "4")
          .traverse(message => producer(message).delayBy(10.millis))
          .foreverM
          .start
        _ <- actorSystem.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

#### Explanation

1. Actor Creation:

   ```scala
   actor
   <- actorSystem.actorOf(new Actor[IO, String] {
     override def receive: Receive[IO, String] = {
       case msg =>
         IO.println(msg)
     }
   })
   ```

   An actor is created with a receive method that prints any received message.
2. Piping Data from FS2 Stream to Actor:

   ```scala
   _ <- consumer.evalTapChunk(actor ! _).compile.drain.start
   ```

   - `consumer`: This is the FS2 stream that consumes messages from RabbitMQ.
   - `evalTapChunk(actor ! _)`: This method evaluates an effectful function for each chunk of the stream. Here, it sends each message to the actor using the `!` operator.
   - `compile.drain.start`: This compiles the stream to an `IO` and starts it concurrently.

This setup ensures that messages consumed from RabbitMQ are processed by the actor in real-time.

### Example: Processing by sending computations in a round-robin fashion

Processing all messages within a single actor can be inefficient. A better approach is to distribute the load across
multiple actors. One simple strategy for this is to process messages using a set of actors, where each message is
distributed to actors in a round-robin fashion.

In this example, we will create a set of actors and distribute incoming messages among them in a round-robin manner,
which helps in balancing the load and improving the system's responsiveness.

```scala
import cats.effect._
import cats.implicits._
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.{Actor, ActorRef, ActorSystem}

import java.util.UUID
import scala.concurrent.duration._

object ShardedActorRabbitApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    case class ShardActor() extends Actor[IO, String] {
      override def receive: Receive[IO, String] = {
        case msg =>
          IO.println(s"[Actor: ${context.self.path.name}] [Message: $msg]")
      }
    }

    val connectionConfig = AmqpConnectionFactoryConfig("localhost", "/", "guest", "guest")

    val resources = for {
      consumer <- Resource.pure(AmqpStream.amqpConsumerStream(connectionConfig))
      producer <- AmqpStream.amqpProducer(connectionConfig)
      actorSystem <- ActorSystem[IO]("cats-actors-rabbit-sample")
    } yield (consumer, producer, actorSystem)

    resources.use { case (consumer, producer, actorSystem) =>
      for {
        actor <- actorSystem.actorOf[String, Unit](for {
          children <- Ref[IO].of[List[ActorRef[IO, String]]](List.empty)
          index <- Ref[IO].of[Int](1)
          maxChildren = 10
        } yield new Actor[IO, String] {
          override def preStart: IO[Unit] =
            (1 to maxChildren).toList.traverse(index =>
              context.actorOf(ShardActor(), s"child-$index")
            ) >>= children.set

          override def receive: Receive[IO, String] = {
            case m =>
              (children.get, index.get).flatMapN { case (children, currentIndex) =>
                (children(currentIndex % maxChildren) ! m) >> index.update(_ + 1)
              }
          }
        })
        _ <- consumer.evalTapChunk(actor ! _).compile.drain.start
        _ <- IO
          .defer(producer(s"hello-${UUID.randomUUID().toString}").delayBy(1.millis))
          .foreverM
          .start
        _ <- actorSystem.waitForTermination
      } yield ExitCode.Success
    }
  }
}
```

#### Explanation

Let's break down the code with a focus on how the shard of actors is created and how messages are piped to them.

##### ShardActor

This is a simple actor that prints any received message along with its own name.

```scala
import cats.effect.IO
import com.suprnation.actor.Actor.{Actor, Receive}

case class ShardActor() extends Actor[IO, String] {
  override def receive: Receive[IO, String] = { case msg =>
    IO.println(s"[Actor: ${context.self.path.name}] [Message: $msg]")
  }
}
```

##### Shard Creation

1. Actor Initialization:

```scala
actor
<- actorSystem.actorOf[String](for {
  children <- Ref[IO].of[List[ActorRef[IO, String]]](List.empty)
  index <- Ref[IO].of[Int](1)
  maxChildren = 10
} yield new Actor[IO] {
```

The for comprehension creates an `IO[Actor[IO, String]]`. This applicative is processed by actorOf to create the actor.

2. Pre-Start Hook:

```scala
override def preStart: IO[Unit] =
  (1 to maxChildren).toList.traverse(index =>
    context.actorOf(ShardActor(), s"child-$index")
  ) >>= children.set
```

Initializes `maxChildren` number of `ShardActor` instances and stores their references in children.

3. Message Handling:

```scala
override def receive: Receive[IO, String] = {
  case m =>
    (children.get, index.get).flatMapN { case (children, currentIndex) =>
      (children(currentIndex % maxChildren) ! m) >> index.update(_ + 1)
    }
}
```

`receive`: Distributes incoming messages to child actors in a round-robin fashion using the index.

## Common Scenarios - Sharding of Actors with Timeout

In this example, we aim to dynamically create actors to handle user-specific tasks and terminate idle actors to reclaim memory. The `ActorSupervisor` creates `ShardActor` instances for each user to process messages. When a `ShardActor` remains idle, it notifies the `ActorSupervisor`, which then terminates the idle actor to optimize memory usage.

```scala
package com.suprnation.samples

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.suprnation.EscalatingActor
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.{ActorSystem, PoisonPill}
import com.suprnation.samples.ShardingApp.ActorSupervisor.{ActorIdle, Request, Shard, Timeout}

import scala.concurrent.duration._
import scala.language.postfixOps
object ShardingApp extends IOApp {
  object ActorSupervisor {
    trait Request
    case class Shard(userId: Long, amount: Int) extends Request
    case class ActorIdle(userId: Long) extends Request
    case object Timeout extends Request
  }

  case class ActorSupervisor() extends EscalatingActor[IO, Request] {
    override val receive: Receive[IO, Request] = receiveWithMap(Map.empty)

    def receiveWithMap(
        userMap: Map[Long, ActorRef[IO, Request]]
    ): Receive[IO, Request] = {
      case g @ Shard(userId, _) =>
        if (userMap.contains(userId)) {
          IO.println(s"User $userId is already found!") >> (userMap(userId) ! g)
        } else {
          for {
            _ <- IO.println(s"Oh noes! Actor $userId not found!!")
            actorAddress <- context.actorOf(ShardActor(self, userId))
            _ <- context.become(receiveWithMap(userMap + (userId -> actorAddress)))
            _ <- actorAddress >>! g
          } yield ()
        }

      case ActorIdle(userId) =>
        IO.println(s"Actor is idle $userId (parent)") >>
          context.become(receiveWithMap(userMap - userId)) >>
          (userMap(userId) !* PoisonPill)
    }
  }

  case class ShardActor(parent: ActorRef[IO, Request], userId: Long)
      extends EscalatingActor[IO, Request] {
    override def preStart: IO[Unit] =
      context.setReceiveTimeout(2 seconds, Timeout)

    override def receive: Receive[IO, Request] = {
      case Shard(userId, amount) =>
        IO.println(s"Received Message from child [UserId: $userId] [Amount: $amount]")
      case Timeout =>
        IO.println(s"Hey no one sent me anything for [UserId: $userId] 2 second!") >>
          (parent ! ActorIdle(userId))
    }
  }

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]()
      .use(system =>
        for {
          supervisor <- system.actorOf[Request](new ActorSupervisor())
          _ <- supervisor ! Shard(1, 10)
          _ <- supervisor ! Shard(2, 20)
          _ <- supervisor ! Shard(1, 5)
          _ <- supervisor ! Shard(2, 1)
          _ <- supervisor ! Shard(1, 10)
          _ <- supervisor ! Shard(2, 20)
          _ <- system.waitForTermination
        } yield ()
      )
      .as(ExitCode.Success)

}
```

### Explanation

1. Actor Supervisor:

- Manages `ShardActor` instances for each user.
- Messages:
  - `Shard`: Find or create a shard for the specified user.
  - `ActorIdle`: Indicates that a shard is idle and should be terminated.

2. Shard Actor:

- Represents user-specific actors that process messages.
- Messages:
  - `Shard`: Processes messages for the user.
  - `Timeout`: Sent if the actor is idle for a specified time, leading to an idle notification to the supervisor.

3. Application Setup:

- Initializes the actor system and sends a series of Shard messages to demonstrate actor creation and message processing.

## Looking for Another Example?

If you need another example or have a specific scenario in mind, please open an issue on our GitHub repository. We'll
make sure to add it as soon as possible. Be sure to check the existing tests and scenarios in our test suite, as they
might already cover your needs.

### How to Contribute

1. **Open an Issue**: Describe the example or scenario you need.
2. **Check Tests**: Review the tests and scenarios in the test suite for similar examples.

We appreciate your feedback and contributions!

## Contributing

We welcome contributions to Cats-Actors! Please refer to our [CONTRIBUTING.md](CONTRIBUTING.md) for detailed
instructions on how to contribute.

## Code of Conduct

We adhere to the Contributor Covenant Code of Conduct. Please read our [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for more
details.

## License

Cats-Actors is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

## NOTICE

See the [NOTICE](NOTICE) file for details about attribution and contributions.
