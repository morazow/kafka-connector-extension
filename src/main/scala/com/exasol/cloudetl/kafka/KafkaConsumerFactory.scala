package com.exasol.cloudetl.kafka

import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import com.exasol.ExaMetadata
import com.exasol.cloudetl.kafka.KafkaConsumerProperties._

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer

/**
 * A factory class that creates Kafka consumer clients.
 */
object KafkaConsumerFactory {

  /**
   * Creates a [[org.apache.kafka.clients.consumer.KafkaConsumer]] from
   * [[KafkaConsumerProperties]] properties.
   *
   * At the moment Avro based specific {@code KafkaConsumer[String,
   * GenericRecord]} consumer is returned. Therefore, in order to define
   * the schema of [[org.apache.avro.generic.GenericRecord]] the {@code
   * SCHEMA_REGISTRY_URL} value should be provided.
   */
  def apply(properties: KafkaConsumerProperties): KafkaConsumer[String, GenericRecord] = {
    val topic = properties.getTopic()
    try {
      new KafkaConsumer[String, GenericRecord](
        properties.getProperties(),
        new StringDeserializer,
        getAvroDeserializer(properties.getSchemaRegistryUrl())
          .asInstanceOf[Deserializer[GenericRecord]]
      )
    } catch {
      case exception: Throwable =>
        throw new KafkaConnectorException(
          s"Error creating a Kafka consumer for topic '$topic'. Cause: " + exception.getMessage(),
          exception
        )
    }
  }

  /**
   * Creates a [[org.apache.kafka.clients.consumer.KafkaConsumer]] from
   * [[KafkaConsumerProperties]] properties and [[ExaMetadata]]
   * metadata.
   *
   * The Exasol metadata is used to obtain additional secure key value
   * properties from connection object.
   */
  def apply(
    properties: KafkaConsumerProperties,
    exasolMetadata: ExaMetadata
  ): KafkaConsumer[String, GenericRecord] = {
    validateNoSSLCredentials(properties)
    if (properties.hasNamedConnection()) {
      val newProperties = properties.mergeWithConnectionObject(exasolMetadata)
      validateSSLLocationFilesExist(newProperties)
      apply(newProperties)
    } else {
      apply(properties)
    }
  }

  private[this] def validateNoSSLCredentials(properties: KafkaConsumerProperties): Unit =
    if (properties.isSSLEnabled()) {
      val secureConnectionProperties = List(
        SSL_KEYSTORE_LOCATION,
        SSL_KEYSTORE_PASSWORD,
        SSL_KEY_PASSWORD,
        SSL_TRUSTSTORE_LOCATION,
        SSL_TRUSTSTORE_PASSWORD
      ).map(_.userPropertyName)
      if (secureConnectionProperties.exists(p => properties.containsKey(p))) {
        throw new KafkaConnectorException(
          "Please use a named connection object to provide secure SSL properties."
        )
      }
    }

  private[this] def validateSSLLocationFilesExist(properties: KafkaConsumerProperties): Unit =
    if (properties.isSSLEnabled()) {
      if (!Files.isRegularFile(Paths.get(properties.getSSLKeystoreLocation()))) {
        throw new KafkaConnectorException(
          s"Unable to find the SSL keystore file '${properties.getSSLKeystoreLocation()}'. " +
            s"Please make sure it is successfully uploaded to BucketFS bucket."
        )
      }
      if (!Files.isRegularFile(Paths.get(properties.getSSLTruststoreLocation()))) {
        throw new KafkaConnectorException(
          s"Unable to find the SSL truststore file '${properties.getSSLTruststoreLocation()}'. " +
            s"Please make sure it is successfully uploaded to BucketFS bucket."
        )
      }
    }

  private[this] def getAvroDeserializer(schemaRegistryUrl: String): KafkaAvroDeserializer = {
    // The schema registry URL should be provided here since the one
    // configured in consumer properties is not for the deserializer.
    val deserializerConfig = Map(
      AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> schemaRegistryUrl
    )
    val kafkaAvroDeserializer = new KafkaAvroDeserializer
    kafkaAvroDeserializer.configure(deserializerConfig.asJava, false)
    kafkaAvroDeserializer
  }

}
