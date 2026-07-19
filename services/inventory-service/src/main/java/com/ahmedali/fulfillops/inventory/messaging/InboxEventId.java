package com.ahmedali.fulfillops.inventory.messaging;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * (eventId, consumerName) together are the idempotency key: this consumer has either already
 * handled this event, or it hasn't. Composite so more than one logical consumer in the same service
 * can track completion independently.
 */
@Embeddable
public class InboxEventId implements Serializable {

  private UUID eventId;
  private String consumerName;

  protected InboxEventId() {
    // JPA
  }

  public InboxEventId(UUID eventId, String consumerName) {
    this.eventId = eventId;
    this.consumerName = consumerName;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getConsumerName() {
    return consumerName;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InboxEventId that)) {
      return false;
    }
    return Objects.equals(eventId, that.eventId) && Objects.equals(consumerName, that.consumerName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(eventId, consumerName);
  }
}
