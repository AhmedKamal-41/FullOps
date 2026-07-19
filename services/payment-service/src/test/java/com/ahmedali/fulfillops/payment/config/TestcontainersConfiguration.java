package com.ahmedali.fulfillops.payment.config;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Same pinned image versions as infra/compose/docker-compose.yml. Spring Boot starts and stops
 * these automatically for any test that imports this class — no running Compose stack required.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:17.10-alpine"));
  }

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
  }

  @Bean
  @ServiceConnection
  RedisContainer redisContainer() {
    return new RedisContainer(DockerImageName.parse("redis:8.8.0-alpine"));
  }
}
