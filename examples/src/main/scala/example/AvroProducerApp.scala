package example

import cats.effect.{ Async, ContextShift, ExitCode, IO, IOApp, Resource }
import cats.syntax.applicative._
import com.sksamuel.avro4s.{ Decoder, Encoder, RecordFormat }
import com.tenable.library.kafkaclient.client.standard.KafkaProducerIO
import com.tenable.library.kafkaclient.config.KafkaProducerConfig
import com.typesafe.scalalogging.LazyLogging
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.{ Deserializer, Serde, Serdes, Serializer }

/*
   To run this example:

   Run the following from the root of the repository:

   ```bash
   # Start a local kafka environment using docker-compose
   docker-compose -f examples/docker-compose.yml up -d

   # Create a sample kafka topic
   docker exec -it local-kafka bash
   kafka-topics \
   --bootstrap-server kafka:9092 \
   --create \
   --topic pet-topic

   # Start consuming from the sample kafka topic
   docker exec -it local-schema-registry bash
   kafka-avro-console-consumer \
  --bootstrap-server kafka:29092 \
  --property schema.registry.url=http://local-schema-registry:8081 \
  --property key.separator=":" \
  --property print.key=true \
  --topic pet-topic

   # In a separate terminal window, start the app
   sbt "examples/runMain example.AvroProducerApp"
   ```
 */
object AvroProducerApp extends IOApp with LazyLogging {

  case class Config(
    producerClientId: String,
    connectionString: String,
    outputTopicName: String,
    schemaRegistryUrl: String
  )

  val config: Config = Config(
    producerClientId = "string-producer",
    connectionString = "localhost:9092",
    outputTopicName = "pet-topic",
    schemaRegistryUrl = "http://localhost:8081"
  )

  sealed trait AnimalType
  object AnimalType {
    case object Dog extends AnimalType
    case object Cat extends AnimalType
  }
  case class PetKey(id: String, animal: AnimalType)
  case class PetValue(name: String, age: Int)
  final type Pet = (PetKey, PetValue)

  object Pet {
    val petKeySerializer: Serializer[PetKey] = getSerde[PetKey](
      isKey = true,
      schemaRegistry = config.schemaRegistryUrl
    ).serializer()
    val petValueSerializer: Serializer[PetValue] = getSerde[PetValue](
      isKey = false,
      schemaRegistry = config.schemaRegistryUrl
    ).serializer()

    def getSerde[T >: Null: Encoder: Decoder](
      isKey: Boolean,
      serdeConfig: Map[String, AnyRef] = Map.empty,
      schemaRegistry: String
    ): Serde[T] = {
      import scala.jdk.CollectionConverters.MapHasAsJava
      val recordFormat         = RecordFormat[T]
      val schemaRegistryConfig = AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> schemaRegistry
      val serde                = new GenericAvroSerde
      serde.configure((serdeConfig + schemaRegistryConfig).asJava, isKey)
      val serializer: Serializer[T] =
        (topic: String, data: T) => serde.serializer.serialize(topic, recordFormat.to(data))
      val deserializer: Deserializer[T] =
        (topic: String, bytes: Array[Byte]) =>
          recordFormat.from(
            serde.deserializer().deserialize(topic, bytes)
          )
      Serdes.serdeFrom(serializer, deserializer)
    }
  }

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
  ): Resource[F, KafkaProducerIO[F, PetKey, PetValue]] =
    KafkaProducerIO
      .builder[F, PetKey, PetValue](kafkaConfig(config))
      .withKeyDeserializer(Pet.petKeySerializer)
      .withValueDeserializer(Pet.petValueSerializer)
      .resource

  val program: IO[Unit] =
    makeKafkaProducer[IO](config).use { producer =>
      for {
        _ <- logger.info("Will produce a sample message").pure[IO]
        _ <- producer.send(config.outputTopicName, PetKey("id-1234", AnimalType.Dog), PetValue("Spot", 1))
        _ <- logger.info("Produced a sample message").pure[IO]
      } yield ()
    }

  override def run(args: List[String]): IO[ExitCode] =
    program.map(_ => ExitCode.Success)
}
