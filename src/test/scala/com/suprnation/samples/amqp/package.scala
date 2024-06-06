package com.suprnation.samples.amqp


case class AmqpConnectionFactoryConfig(
    host: String,
    virtualHost: String,
    username: String,
    password: String,
    port: Int = 5672,
    ssl: Boolean = false
)
