-- US-005: el OPERADOR solo ve las reservas de su empresa. company_id viene del itinerario que se consulta
-- a catalog al crear la reserva; las reservas existentes quedan en null (solo las ven su dueño y ADMIN).

ALTER TABLE bookings
    ADD COLUMN company_id UUID;

CREATE INDEX idx_bookings_company_id ON bookings (company_id);
