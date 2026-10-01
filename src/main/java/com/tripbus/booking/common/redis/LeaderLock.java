package com.tripbus.booking.common.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * "Elección de líder" mínima para jobs programados: solo la réplica que obtiene la llave
 * (SET NX PX) ejecuta el job en esa ventana. La llave expira sola (no se libera), así que
 * en cada intervalo corre en un único nodo.
 */
@Component
public class LeaderLock {

    private static final Logger log = LoggerFactory.getLogger(LeaderLock.class);

    private final StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString();

    public LeaderLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean runIfLeader(String jobName, Duration window, Runnable job) {
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent("job-lock:" + jobName, instanceId, window);
        } catch (RuntimeException e) {
            log.warn("Redis no disponible para el lock del job {}: {}", jobName, e.getMessage());
            return false;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        try {
            job.run();
        } catch (RuntimeException e) {
            log.error("Error ejecutando el job {}", jobName, e);
        }
        return true;
    }
}
