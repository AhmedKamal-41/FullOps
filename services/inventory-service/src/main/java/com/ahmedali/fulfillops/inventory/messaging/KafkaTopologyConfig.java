package com.ahmedali.fulfillops.inventory.messaging;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Producer/consumer wiring itself is Spring Boot's own auto-configured
 * ProducerFactory/ConsumerFactory/KafkaTemplate — configured via spring.kafka.* in application.yml
 * (StringSerializer/StringDeserializer, since every event on the wire is a plain JSON string; see
 * EventEnvelope). Hand-rolling those beans instead of using Boot's would bypass Spring Boot's
 * KafkaConnectionDetails abstraction, which is what lets the "test" profile get its
 * bootstrap-servers from Testcontainers automatically.
 */
@Configuration
@EnableKafka
@EnableScheduling
public class KafkaTopologyConfig {}
