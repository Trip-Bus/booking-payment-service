-- US-131 (Sprint 5): identity publica UserDeleted {userId} al eliminar una cuenta. En sus reservas se borran los
-- datos de contacto (email_contacto) y los del comprador de taquilla (nombre, correo y teléfono). Se conservan el
-- nombre y el documento de los pasajeros que hacen falta para el manifiesto y la contabilidad; solo se anonimizan
-- los pasajeros de reservas que nunca se pagaron (PAGO_RECHAZADO o EXPIRADA). Sus reservas futuras siguen válidas,
-- pero sin correo el tiquete ya no se envía ni se reenvía.
ALTER TABLE bookings
    ADD COLUMN usuario_eliminado_en TIMESTAMP WITH TIME ZONE;
