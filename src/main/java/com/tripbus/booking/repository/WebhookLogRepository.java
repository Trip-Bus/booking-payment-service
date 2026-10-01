package com.tripbus.booking.repository;

import com.tripbus.booking.domain.WebhookLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookLogRepository extends JpaRepository<WebhookLog, Long> {
}
