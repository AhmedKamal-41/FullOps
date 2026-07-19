package com.ahmedali.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full application against real Postgres, Kafka, and Redis containers (started by
 * Testcontainers, not a developer's own Compose stack) and proves Flyway's baseline migration
 * actually ran: the outbox/inbox tables it creates exist afterward.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class InventoryServiceMigrationStartupIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void migratesOutboxAndInboxTablesOnStartup() {
    assertThat(countTables("outbox_event")).isEqualTo(1);
    assertThat(countTables("inbox_event")).isEqualTo(1);
  }

  private Integer countTables(String tableName) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
        Integer.class,
        tableName);
  }
}
