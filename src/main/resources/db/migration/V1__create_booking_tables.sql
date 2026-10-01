-- Booking + Payment (esquema propio del servicio).

CREATE TABLE bookings (
    id               UUID PRIMARY KEY,
    hold_id          UUID NOT NULL,
    user_id          UUID NOT NULL,
    itinerary_id     UUID NOT NULL,
    offer_id         UUID,
    seat_numbers     VARCHAR(200) NOT NULL,
    estado           VARCHAR(30) NOT NULL,
    precio_unitario  NUMERIC(12, 2) NOT NULL,
    total            NUMERIC(12, 2) NOT NULL,
    moneda           VARCHAR(3) NOT NULL DEFAULT 'COP',
    hold_expira_en   TIMESTAMP WITH TIME ZONE,
    creado_en        TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en   TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT NOT NULL DEFAULT 0,
    -- Una retención solo puede convertirse en UNA reserva.
    CONSTRAINT uq_bookings_hold UNIQUE (hold_id)
);

CREATE INDEX idx_bookings_user ON bookings (user_id, creado_en DESC);
CREATE INDEX idx_bookings_estado ON bookings (estado, actualizado_en);

CREATE TABLE passengers (
    id                UUID PRIMARY KEY,
    booking_id        UUID NOT NULL REFERENCES bookings (id),
    nombre            VARCHAR(160) NOT NULL,
    tipo_documento    VARCHAR(10) NOT NULL,
    numero_documento  VARCHAR(30) NOT NULL,
    asiento           VARCHAR(10)
);

CREATE INDEX idx_passengers_booking ON passengers (booking_id);

CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    booking_id          UUID NOT NULL REFERENCES bookings (id),
    monto               NUMERIC(12, 2) NOT NULL,
    metodo              VARCHAR(20) NOT NULL,
    estado              VARCHAR(20) NOT NULL,
    payment_gateway_id  VARCHAR(80),
    idempotency_key     VARCHAR(160) NOT NULL,
    tarjeta_ultimos4    VARCHAR(4),
    tarjeta_marca       VARCHAR(20),
    motivo_rechazo      VARCHAR(200),
    creado_en           TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en      TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_payments_gateway_id UNIQUE (payment_gateway_id)
);

-- US-034: a nivel de BD, una reserva nunca puede tener dos pagos vivos (pendiente o aprobado).
CREATE UNIQUE INDEX uq_payments_booking_activo ON payments (booking_id) WHERE estado IN ('PENDIENTE', 'APROBADO');

CREATE TABLE tickets (
    id          UUID PRIMARY KEY,
    booking_id  UUID NOT NULL REFERENCES bookings (id),
    qr_code     TEXT NOT NULL,
    qr_token    VARCHAR(64) NOT NULL,
    emitido_en  TIMESTAMP WITH TIME ZONE NOT NULL,
    estado      VARCHAR(20) NOT NULL,
    -- US-034: nunca dos tiquetes para la misma reserva.
    CONSTRAINT uq_tickets_booking UNIQUE (booking_id),
    CONSTRAINT uq_tickets_qr_token UNIQUE (qr_token)
);

CREATE TABLE idempotency_keys (
    key              VARCHAR(160) PRIMARY KEY,
    user_id          UUID NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    estado           VARCHAR(20) NOT NULL,
    response_status  INTEGER,
    response_body    TEXT,
    creado_en        TIMESTAMP WITH TIME ZONE NOT NULL,
    expira_en        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_expira ON idempotency_keys (expira_en);

CREATE TABLE saga_log (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  UUID NOT NULL,
    paso        VARCHAR(40) NOT NULL,
    estado      VARCHAR(20) NOT NULL,
    payload     TEXT,
    creado_en   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_saga_log_booking ON saga_log (booking_id, id);

CREATE TABLE webhook_log (
    id                BIGSERIAL PRIMARY KEY,
    gateway_event_id  VARCHAR(80),
    payload           TEXT NOT NULL,
    firma             VARCHAR(200),
    valido            BOOLEAN NOT NULL,
    resultado         VARCHAR(40),
    procesado_en      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE refunds (
    id          UUID PRIMARY KEY,
    payment_id  UUID NOT NULL REFERENCES payments (id),
    monto       NUMERIC(12, 2) NOT NULL,
    motivo      VARCHAR(200) NOT NULL,
    estado      VARCHAR(20) NOT NULL,
    creado_en   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_refunds_payment UNIQUE (payment_id)
);

CREATE TABLE processed_events (
    event_id      VARCHAR(64) PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
