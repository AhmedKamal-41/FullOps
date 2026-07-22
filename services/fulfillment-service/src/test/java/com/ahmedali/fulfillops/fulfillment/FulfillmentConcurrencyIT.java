package com.ahmedali.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistoryRepository;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCommandService;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentVersionConflictException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves two operators cannot both claim the same fulfillment silently: every thread reads the same
 * starting version (0) and races to submit its claim at the same instant a CountDownLatch allows;
 * exactly one must win the row's optimistic-lock version check, and every loser must see a
 * FulfillmentVersionConflictException rather than a silently overwritten assignee. Submits work
 * with pool.submit(...) and collects Futures individually rather than
 * ExecutorService.invokeAll(...) — see order-service's OrderConcurrencyIT for the deadlock that
 * pattern caused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class FulfillmentConcurrencyIT {

  private static final int CONTENDERS = 10;

  @Autowired private FulfillmentRepository fulfillmentRepository;
  @Autowired private FulfillmentStatusHistoryRepository statusHistoryRepository;
  @Autowired private FulfillmentCommandService fulfillmentCommandService;

  @Test
  void exactlyOneOfManyConcurrentClaimAttemptsSucceeds() throws Exception {
    Fulfillment fulfillment = saveNewFulfillment();
    UUID fulfillmentId = fulfillment.getFulfillmentId();

    CountDownLatch ready = new CountDownLatch(CONTENDERS);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
    List<Future<Boolean>> futures;
    try {
      futures =
          IntStream.range(0, CONTENDERS)
              .<Future<Boolean>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            return claimSucceeds(fulfillmentId);
                          }))
              .toList();

      ready.await();
      go.countDown();

      long successes = 0;
      for (Future<Boolean> future : futures) {
        if (future.get()) {
          successes++;
        }
      }
      assertThat(successes).isEqualTo(1);
    } finally {
      pool.shutdown();
    }

    Fulfillment reloaded = fulfillmentRepository.findById(fulfillmentId).orElseThrow();
    assertThat(reloaded.getAssigneeId()).isNotNull();
    assertThat(reloaded.getVersion()).isEqualTo(1);
  }

  private boolean claimSucceeds(UUID fulfillmentId) {
    try {
      fulfillmentCommandService.claim(fulfillmentId, 0, "operator-" + UUID.randomUUID());
      return true;
    } catch (FulfillmentVersionConflictException conflict) {
      return false;
    }
  }

  private Fulfillment saveNewFulfillment() {
    Fulfillment fulfillment =
        Fulfillment.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "WH-ATL-1",
            Instant.now().plusSeconds(3600),
            UUID.randomUUID());
    fulfillmentRepository.save(fulfillment);
    statusHistoryRepository.save(
        new FulfillmentStatusHistory(
            fulfillment.getFulfillmentId(), FulfillmentStatus.ASSIGNED, "system", null));
    return fulfillment;
  }
}
