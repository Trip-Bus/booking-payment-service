-- Sprint 3: reservas afectadas por cambios de la empresa (US-154, US-155) y manifiesto de pasajeros (US-172).

-- Una reserva CONFIRMADA con tiquete EMITIDO cuyo viaje la empresa canceló, reprogramó o retrasó (>= umbral).
-- causa: CANCELACION | REPROGRAMACION | RETRASO. estado: PENDIENTE hasta que el pasajero decide o vence
-- plazo_decision (entonces decide el job: SISTEMA). booking_nuevo_id: la reserva a la que se reprogramó o reubicó.
-- anulacion_resuelta_en: tras un reembolso de un viaje NO cancelado, cuándo seat-inventory anuló los asientos
-- (null mientras falte; el job lo reintenta).
CREATE TABLE affected_bookings (
    booking_id             UUID PRIMARY KEY REFERENCES bookings (id),
    itinerary_id           UUID NOT NULL,
    causa                  VARCHAR(20) NOT NULL,
    plazo_decision         TIMESTAMP WITH TIME ZONE NOT NULL,
    estado                 VARCHAR(20) NOT NULL,
    decidido_en            TIMESTAMP WITH TIME ZONE,
    decidido_por           VARCHAR(10),
    booking_nuevo_id       UUID REFERENCES bookings (id),
    anulacion_resuelta_en  TIMESTAMP WITH TIME ZONE,
    creado_en              TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en         TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_affected_causa CHECK (causa IN ('CANCELACION', 'REPROGRAMACION', 'RETRASO')),
    CONSTRAINT ck_affected_estado
        CHECK (estado IN ('PENDIENTE', 'REPROGRAMADA', 'REEMBOLSADA', 'REUBICADA', 'ACEPTO_CAMBIO')),
    CONSTRAINT ck_affected_decidido_por CHECK (decidido_por IN ('SISTEMA', 'PASAJERO')),
    -- Decidida <=> tiene fecha y autor.
    CONSTRAINT ck_affected_decision CHECK ((estado = 'PENDIENTE') = (decidido_en IS NULL AND decidido_por IS NULL))
);

CREATE INDEX idx_affected_pendientes ON affected_bookings (plazo_decision) WHERE estado = 'PENDIENTE';
CREATE INDEX idx_affected_itinerary ON affected_bookings (itinerary_id);

CREATE INDEX idx_bookings_itinerary ON bookings (itinerary_id, estado);

-- US-172: el manifiesto tiene datos personales; cada consulta queda registrada (quién, cuándo, qué viaje).
CREATE TABLE manifest_access_log (
    id            BIGSERIAL PRIMARY KEY,
    itinerary_id  UUID NOT NULL,
    user_id       UUID NOT NULL,
    rol           VARCHAR(20),
    formato       VARCHAR(10) NOT NULL,
    permitido     BOOLEAN NOT NULL,
    consultado_en TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_manifest_access_itinerary ON manifest_access_log (itinerary_id, consultado_en DESC);
