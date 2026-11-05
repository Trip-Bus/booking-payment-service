-- Sprint 6: tarifas desde catalog (US-138/US-141), compra de un tramo (US-142), comprobantes de pago y de reembolso
-- (US-146) y cupones de descuento (US-147).

-- US-138/US-141: desglose por pasajero (tipo de pasajero y categoría del asiento, con el precio que dio catalog).
-- Las reservas anteriores quedan como ADULTO sin desglose.
ALTER TABLE passengers
    ADD COLUMN tipo_pasajero      VARCHAR(15) NOT NULL DEFAULT 'ADULTO',
    ADD COLUMN categoria_asiento  VARCHAR(30),
    ADD COLUMN precio_base        NUMERIC(12, 2),
    ADD COLUMN descuento_tipo     NUMERIC(12, 2),
    ADD COLUMN recargo_categoria  NUMERIC(12, 2),
    ADD COLUMN precio             NUMERIC(12, 2);
ALTER TABLE passengers
    ADD CONSTRAINT ck_passengers_tipo CHECK (tipo_pasajero IN ('ADULTO', 'NINO', 'ADULTO_MAYOR'));

-- subtotal: suma del desglose; total = subtotal - descuento_cupon (lo que se paga).
-- US-142: tramo comprado (orden y nombre de la parada de abordaje y de bajada); null = el viaje completo.
ALTER TABLE bookings
    ADD COLUMN subtotal         NUMERIC(12, 2),
    ADD COLUMN descuento_cupon  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN coupon_id        UUID,
    ADD COLUMN cupon_codigo     VARCHAR(30),
    ADD COLUMN route_id         UUID,
    ADD COLUMN from_orden       INTEGER,
    ADD COLUMN to_orden         INTEGER,
    ADD COLUMN parada_abordaje  VARCHAR(160),
    ADD COLUMN parada_bajada    VARCHAR(160);

ALTER TABLE tickets
    ADD COLUMN parada_abordaje  VARCHAR(160),
    ADD COLUMN parada_bajada    VARCHAR(160);

-- US-147: cupones. company_id null = cupón de TripBus (sirve en cualquier empresa). codigo es único sin distinguir
-- mayúsculas. usos se incrementa de forma atómica (UPDATE ... WHERE usos < usos_maximos) y se libera si la reserva
-- vence o el pago se rechaza. route_ids vacío = cualquier ruta.
CREATE TABLE coupons (
    id                UUID PRIMARY KEY,
    codigo            VARCHAR(30) NOT NULL,
    tipo              VARCHAR(10) NOT NULL,
    valor             NUMERIC(12, 2) NOT NULL,
    company_id        UUID,
    vigente_desde     TIMESTAMP WITH TIME ZONE NOT NULL,
    vigente_hasta     TIMESTAMP WITH TIME ZONE NOT NULL,
    usos_maximos      INTEGER,
    usos              INTEGER NOT NULL DEFAULT 0,
    usos_por_usuario  INTEGER,
    compra_minima     NUMERIC(12, 2),
    route_ids         TEXT,
    activo            BOOLEAN NOT NULL DEFAULT TRUE,
    creado_por        UUID NOT NULL,
    creado_en         TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_coupons_tipo CHECK (tipo IN ('PORCENTAJE', 'VALOR')),
    CONSTRAINT ck_coupons_valor CHECK (valor > 0 AND (tipo <> 'PORCENTAJE' OR valor <= 100)),
    CONSTRAINT ck_coupons_vigencia CHECK (vigente_hasta > vigente_desde),
    CONSTRAINT ck_coupons_usos CHECK (usos >= 0 AND (usos_maximos IS NULL OR usos <= usos_maximos))
);
CREATE UNIQUE INDEX uq_coupons_codigo ON coupons (lower(codigo));
CREATE INDEX idx_coupons_company ON coupons (company_id);

CREATE TABLE coupon_redemptions (
    id           UUID PRIMARY KEY,
    coupon_id    UUID NOT NULL REFERENCES coupons (id),
    booking_id   UUID NOT NULL REFERENCES bookings (id),
    user_id      UUID NOT NULL,
    descuento    NUMERIC(12, 2) NOT NULL,
    estado       VARCHAR(10) NOT NULL,
    creado_en    TIMESTAMP WITH TIME ZONE NOT NULL,
    liberado_en  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_coupon_redemptions_booking UNIQUE (booking_id),
    CONSTRAINT ck_coupon_redemptions_estado CHECK (estado IN ('ACTIVO', 'LIBERADO'))
);
CREATE INDEX idx_coupon_redemptions_usuario ON coupon_redemptions (coupon_id, user_id) WHERE estado = 'ACTIVO';

-- US-146: número de comprobante consecutivo, compartido por pagos y reembolsos. Se asigna la primera vez que se
-- genera el comprobante.
CREATE SEQUENCE comprobantes_seq START WITH 1;
ALTER TABLE payments ADD COLUMN comprobante_numero BIGINT;
ALTER TABLE refunds ADD COLUMN comprobante_numero BIGINT;
CREATE UNIQUE INDEX uq_payments_comprobante ON payments (comprobante_numero) WHERE comprobante_numero IS NOT NULL;
CREATE UNIQUE INDEX uq_refunds_comprobante ON refunds (comprobante_numero) WHERE comprobante_numero IS NOT NULL;
