package com.ahmedali.fulfillops.payment.web.dto;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterEventResponse(
    UUID eventId,
    String consumerName,
    String originalTopic,
    String eventType,
    UUID aggregateId,
    String envelopeJson,
    String status,
    Instant createdAt,
    Instant replayedAt,
    String replayedBy) {}
