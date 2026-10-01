package com.tripbus.booking.repository;

import com.tripbus.booking.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    @Modifying
    @Query("delete from IdempotencyKey k where k.expiraEn < :now")
    int deleteExpired(@Param("now") Instant now);
}
