package com.suprnation.samples.amqp

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
