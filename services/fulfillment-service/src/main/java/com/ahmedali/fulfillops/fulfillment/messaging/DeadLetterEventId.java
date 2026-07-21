package com.ahmedali.fulfillops.fulfillment.messaging;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * (eventId, consumerName) together identify one dead-lettered delivery: the same event can
 * independently exhaust retries for one listener's consumer group without affecting another's.
 */
@Embeddable
public class DeadLetterEventId implements Serializable {

  private UUID eventId;
  private String consumerName;

  protected DeadLetterEventId() {
    // JPA
  }

  public DeadLetterEventId(UUID eventId, String consumerName) {
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
    if (!(other instanceof DeadLetterEventId that)) {
      return false;
    }
    return Objects.equals(eventId, that.eventId) && Objects.equals(consumerName, that.consumerName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(eventId, consumerName);
  }
}
