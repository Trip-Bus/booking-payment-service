-- US-190: ventas e ingresos con la comisión de TripBus (cuota mensual por empresa + comisión por tiquete).

-- Una "venta" es un pago con aprobado_en (APP con cualquier método, TAQUILLA o la diferencia de una
-- reprogramación). La comisión se toma de catalog con la vigencia de la fecha de la venta (hora de Colombia):
-- comision_porcentaje (0 a 30) y comision_valor = monto × porcentaje / 100, redondeado al peso.
-- Si catalog no responde quedan en null y CommissionJob las completa (cada 15 min y al arrancar).
ALTER TABLE payments
    ADD COLUMN aprobado_en          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN comision_porcentaje  NUMERIC(5, 2),
    ADD COLUMN comision_valor       NUMERIC(14, 2);

-- Ventas anteriores a este cambio: se aprobaron al crearse (taquilla) o segundos después (webhook).
UPDATE payments SET aprobado_en = creado_en WHERE estado IN ('APROBADO', 'REEMBOLSADO');

-- Reversión: el reembolso registra la comisión de su venta en negativo (en los reportes esa comisión es 0).
ALTER TABLE refunds
    ADD COLUMN comision_valor NUMERIC(14, 2);

-- Reportes: ventas por fecha de aprobación (y estado), filtradas por empresa de la reserva.
CREATE INDEX idx_payments_aprobado_en ON payments (aprobado_en, estado) WHERE aprobado_en IS NOT NULL;
CREATE INDEX idx_payments_booking_id ON payments (booking_id);
CREATE INDEX idx_bookings_company_creado ON bookings (company_id, creado_en);
-- CommissionJob: ventas a las que aún les falta la comisión.
CREATE INDEX idx_payments_comision_pendiente ON payments (aprobado_en)
    WHERE aprobado_en IS NOT NULL AND comision_porcentaje IS NULL;
