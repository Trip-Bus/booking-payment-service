package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-172: cada consulta del manifiesto de pasajeros (datos personales): quién, cuándo y qué viaje. */
@Entity
@Table(name = "manifest_access_log")
public class ManifestAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private String rol;

    @Column(nullable = false)
    private String formato;

    @Column(nullable = false)
    private boolean permitido;

    @Column(name = "consultado_en", nullable = false)
    private Instant consultadoEn;

    protected ManifestAccessLog() {
    }

    public ManifestAccessLog(UUID itineraryId, UUID userId, String rol, String formato, boolean permitido,
                             Instant consultadoEn) {
        this.itineraryId = itineraryId;
        this.userId = userId;
        this.rol = rol;
        this.formato = formato;
        this.permitido = permitido;
        this.consultadoEn = consultadoEn;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isPermitido() {
        return permitido;
    }
}
