-- US-033: el tiquete se envía por correo al comprador. El correo se toma del claim "email" del JWT
-- al crear la reserva (null en tokens viejos: entonces no se envía). En tickets se registra cuándo se
-- notificó y cuántos intentos fallidos lleva (el job de reintento se detiene en 5).

ALTER TABLE bookings
    ADD COLUMN email_contacto VARCHAR(320);

ALTER TABLE tickets
    ADD COLUMN notificado_en          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN intentos_notificacion  INTEGER NOT NULL DEFAULT 0;
