-- US-033: el QR solo se puede usar una vez al abordar. Se registra cuándo y quién lo validó.

ALTER TABLE tickets
    ADD COLUMN usado_en  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN usado_por UUID;

ALTER TABLE tickets
    ADD CONSTRAINT ck_tickets_estado CHECK (estado IN ('EMITIDO', 'USADO'));
