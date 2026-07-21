package com.ahmedali.fulfillops.fulfillment.web;

import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEvent;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEventRepository;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEventStatus;
import com.ahmedali.fulfillops.fulfillment.service.DeadLetterReplayService;
import com.ahmedali.fulfillops.fulfillment.web.dto.DeadLetterEventResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only: every event that exhausted its retry budget and reached a dead-letter topic in this
 * service is persisted (see DeadLetterEventRecorder) so it can be found here and safely replayed —
 * the replay endpoint takes only an event id, never a payload, so an admin can never inject an
 * arbitrary message onto a topic through this API. See SecurityConfig for the ADMIN-only rule.
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
public class DeadLetterReplayController {

  private final DeadLetterEventRepository repository;
  private final DeadLetterReplayService replayService;

  public DeadLetterReplayController(
      DeadLetterEventRepository repository, DeadLetterReplayService replayService) {
    this.repository = repository;
    this.replayService = replayService;
  }

  @GetMapping
  public List<DeadLetterEventResponse> listPendingReview() {
    return repository
        .findByStatusOrderByCreatedAtDesc(DeadLetterEventStatus.PENDING_REVIEW)
        .stream()
        .map(DeadLetterReplayController::toResponse)
        .toList();
  }

  @PostMapping("/{eventId}/replay")
  public DeadLetterEventResponse replay(
      @PathVariable UUID eventId, @AuthenticationPrincipal Jwt jwt) {
    return toResponse(replayService.replay(eventId, jwt.getSubject()));
  }

  private static DeadLetterEventResponse toResponse(DeadLetterEvent event) {
    return new DeadLetterEventResponse(
        event.getId().getEventId(),
        event.getId().getConsumerName(),
        event.getOriginalTopic(),
        event.getEventType(),
        event.getAggregateId(),
        event.getEnvelopeJson(),
        event.getStatus().name(),
        event.getCreatedAt(),
        event.getReplayedAt(),
        event.getReplayedBy());
  }
}
