-- Sprint 3: reprogramar el tiquete (US-150), cambiar de asiento (US-139) y corregir datos del pasajero (US-152).

-- REPROGRAMADO: el tiquete se cambió por otro de una reserva nueva. ANULADO: el tiquete ya no sirve.
-- Ninguno de los dos se puede usar para abordar.
ALTER TABLE tickets DROP CONSTRAINT ck_tickets_estado;
ALTER TABLE tickets
    ADD CONSTRAINT ck_tickets_estado CHECK (estado IN ('EMITIDO', 'USADO', 'REPROGRAMADO', 'ANULADO'));

-- origen_booking_id: reserva de la que viene una reprogramación (la vieja pasa a REPROGRAMADA al confirmarse).
-- valor_pagado: valor del pasaje que ya quedó pagado en la cadena de reprogramaciones (null en las reservas
--   normales, donde es el total). La política es sin reembolso: si el viaje nuevo es más barato se conserva.
-- anulacion_resuelta_en: en la reserva REPROGRAMADA, cuándo seat-inventory anuló sus asientos viejos
--   (null mientras falte: el job de recuperación de sagas la reintenta).
ALTER TABLE bookings
    ADD COLUMN origen_booking_id      UUID REFERENCES bookings (id),
    ADD COLUMN valor_pagado           NUMERIC(12, 2),
    ADD COLUMN anulacion_resuelta_en  TIMESTAMP WITH TIME ZONE;

-- Una reserva solo puede tener UNA reprogramación viva: dos solicitudes simultáneas nunca terminan en dos
-- tiquetes válidos. Las que expiraron, se rechazaron o se reembolsaron no cuentan (se puede intentar de nuevo).
CREATE UNIQUE INDEX uq_bookings_reprogramacion_viva ON bookings (origen_booking_id)
    WHERE estado IN ('PENDIENTE_PAGO', 'PAGO_EN_PROCESO', 'PAGO_APROBADO', 'CONFIRMADA', 'REPROGRAMADA');

CREATE INDEX idx_bookings_anulacion_pendiente ON bookings (actualizado_en)
    WHERE estado = 'REPROGRAMADA' AND anulacion_resuelta_en IS NULL;

-- US-152: el nombre o el documento de cada pasajero se puede corregir una sola vez (lo garantiza la BD).
CREATE TABLE passenger_changes (
    id                         UUID PRIMARY KEY,
    passenger_id               UUID NOT NULL REFERENCES passengers (id),
    nombre_anterior            VARCHAR(160) NOT NULL,
    tipo_documento_anterior    VARCHAR(10) NOT NULL,
    numero_documento_anterior  VARCHAR(30) NOT NULL,
    nombre_nuevo               VARCHAR(160) NOT NULL,
    tipo_documento_nuevo       VARCHAR(10) NOT NULL,
    numero_documento_nuevo     VARCHAR(30) NOT NULL,
    hecho_por                  UUID NOT NULL,
    creado_en                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_passenger_changes_passenger UNIQUE (passenger_id)
);
