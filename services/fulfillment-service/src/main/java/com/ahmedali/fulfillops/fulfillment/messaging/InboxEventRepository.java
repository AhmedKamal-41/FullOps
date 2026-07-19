package com.ahmedali.fulfillops.fulfillment.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, InboxEventId> {}
