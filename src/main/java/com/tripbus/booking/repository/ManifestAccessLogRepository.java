package com.tripbus.booking.repository;

import com.tripbus.booking.domain.ManifestAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManifestAccessLogRepository extends JpaRepository<ManifestAccessLog, Long> {
}
