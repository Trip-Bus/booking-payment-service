-- Sprint 4: venta en taquilla (US-144), mis viajes (US-145) y no-show (US-153).

-- Datos del viaje copiados de catalog al crear la reserva: "mis viajes" y los reportes se arman sin consultar a
-- catalog por cada reserva. Las reservas anteriores quedan en null y las rellena TripDataBackfill al arrancar
-- (si catalog no responde, en el siguiente arranque). ItineraryRescheduled actualiza fecha_salida.
-- canal: APP (el pasajero compra en la app) o TAQUILLA (un operador vende en efectivo).
-- vendido_por y comprador_*: solo en TAQUILLA. vendido_por es el operador; comprador_* los datos que dio el
-- comprador (la reserva queda a nombre del usuario registrado con ese correo, o del operador si no hay).
ALTER TABLE bookings
    ADD COLUMN origen              VARCHAR(120),
    ADD COLUMN destino             VARCHAR(120),
    ADD COLUMN fecha_salida        TIMESTAMP WITH TIME ZONE,
    ADD COLUMN empresa_nombre      VARCHAR(160),
    ADD COLUMN canal               VARCHAR(10) NOT NULL DEFAULT 'APP',
    ADD COLUMN vendido_por         UUID,
    ADD COLUMN comprador_nombre    VARCHAR(160),
    ADD COLUMN comprador_email     VARCHAR(320),
    ADD COLUMN comprador_telefono  VARCHAR(30);

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_canal CHECK (canal IN ('APP', 'TAQUILLA'));
ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_vendido_por CHECK ((canal = 'TAQUILLA') = (vendido_por IS NOT NULL));

-- Mis viajes (por usuario y salida), cierre de caja (por vendedor y día) y job de no-show (por salida).
CREATE INDEX idx_bookings_user_salida ON bookings (user_id, fecha_salida);
CREATE INDEX idx_bookings_vendido_por ON bookings (vendido_por, creado_en) WHERE canal = 'TAQUILLA';
CREATE INDEX idx_bookings_salida_confirmadas ON bookings (fecha_salida) WHERE estado = 'CONFIRMADA';
CREATE INDEX idx_bookings_sin_viaje ON bookings (itinerary_id) WHERE fecha_salida IS NULL;

-- US-153: NO_SHOW = el pasajero no abordó y el viaje salió hace más de tripbus.booking.no-show-after.
-- No se reembolsa; se puede reprogramar con cargo dentro de la ventana (no si es de oferta).
ALTER TABLE tickets DROP CONSTRAINT ck_tickets_estado;
ALTER TABLE tickets
    ADD CONSTRAINT ck_tickets_estado CHECK (estado IN ('EMITIDO', 'USADO', 'REPROGRAMADO', 'ANULADO', 'NO_SHOW'));

-- US-145: reenvíos del tiquete por correo en el día (hora de Colombia); máximo 3 por día.
ALTER TABLE tickets
    ADD COLUMN reenvios_fecha  DATE,
    ADD COLUMN reenvios_hoy    INTEGER NOT NULL DEFAULT 0;
