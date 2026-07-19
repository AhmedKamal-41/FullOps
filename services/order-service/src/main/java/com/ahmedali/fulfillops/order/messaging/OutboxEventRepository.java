package com.ahmedali.fulfillops.order.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Claims a bounded batch of due rows for this poll cycle. FOR UPDATE SKIP LOCKED lets more than
   * one relay instance (or a slow-running previous poll) run against the same table without
   * blocking on or double-claiming a row another instance already has. This method's own
   * transaction is short — it does not span the Kafka send that follows, which OutboxRelay does
   * outside any transaction.
   */
  @Transactional
  @Query(
      value =
          """
            SELECT * FROM outbox_event
            WHERE state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """,
      nativeQuery = true)
  List<OutboxEvent> claimBatch(@Param("batchSize") int batchSize);

  @Transactional
  @Modifying
  @Query(
      "UPDATE OutboxEvent o SET o.state = 'PUBLISHED', o.publishedAt = :publishedAt WHERE o.eventId = :eventId")
  void markPublished(@Param("eventId") UUID eventId, @Param("publishedAt") Instant publishedAt);

  @Transactional
  @Modifying
  @Query(
      """
            UPDATE OutboxEvent o
            SET o.attemptCount = o.attemptCount + 1,
                o.lastError = :error,
                o.nextAttemptAt = :nextAttemptAt,
                o.state = CASE WHEN o.attemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
            WHERE o.eventId = :eventId
            """)
  void markFailedAttempt(
      @Param("eventId") UUID eventId,
      @Param("error") String error,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("maxAttempts") int maxAttempts);
}
