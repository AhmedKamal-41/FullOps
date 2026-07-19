package com.ahmedali.fulfillops.inventory.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The wire shape defined by contracts/events/EventEnvelope.v1.schema.json. */
public record EventEnvelope(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID aggregateId,
    String producer,
    JsonNode payload) {}
