package example

import cats.syntax.applicative._
import cats.effect.{ Async, ContextShift, ExitCode, IO, IOApp, Resource }
import com.tenable.library.kafkaclient.client.standard.KafkaProducerIO
import com.tenable.library.kafkaclient.config.KafkaProducerConfig
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer

/*
   To run this example:

   Run the following from the root of the repository:

   ```bash
   # Start a local kafka environment using docker-compose
   docker-compose -f examples/docker-compose.yml up -d

   # Docker exec into the kafka container
   docker exec -it local-kafka bash

   # Create a sample kafka topic
   kafka-topics \
   --bootstrap-server kafka:9092 \
   --create \
   --topic hello-world-string-topic

   # Start consuming from the sample kafka topic
   kafka-console-consumer \
   --bootstrap-server kafka:9092 \
   --topic hello-world-string-topic \
   --property key.separator=":" \
   --property print.key=true

   # In a separate terminal window, start the app
   sbt "examples/runMain example.StringProducerApp"
   ```
 */
object StringProducerApp extends IOApp with LazyLogging {

  case class Config(
    producerClientId: String,
    connectionString: String,
    outputTopicName: String
  )

  def kafkaConfig(config: Config): KafkaProducerConfig =
    KafkaProducerConfig
      .default(config.connectionString)
      .copy(additionalProperties =
        Map(
          ProducerConfig.CLIENT_ID_CONFIG -> config.producerClientId
        )
      )

  def makeKafkaProducer[F[_]: Async: ContextShift](
    config: Config
  ): Resource[F, KafkaProducerIO[F, String, String]] =
    KafkaProducerIO
      .builder[F, String, String](kafkaConfig(config))
      .withKeyDeserializer(new StringSerializer)
      .withValueDeserializer(new StringSerializer)
      .resource

  val config: Config = Config(
    producerClientId = "string-producer",
    connectionString = "localhost:9092",
    outputTopicName = "hello-world-string-topic"
  )

  val program: IO[Unit] =
    makeKafkaProducer[IO](config).use { producer =>
      for {
        _ <- logger.info("Will produce a sample message").pure[IO]
        _ <- producer.send(config.outputTopicName, "message-1", "Hello, World!")
        _ <- logger.info("Produced a sample message").pure[IO]
      } yield ()
    }

  override def run(args: List[String]): IO[ExitCode] =
    program.map(_ => ExitCode.Success)
}
