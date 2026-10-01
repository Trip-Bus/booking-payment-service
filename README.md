# Booking + Payment Service

Orquesta la compra: datos del viajero, total, pago con tarjeta (sandbox), tiquete con QR,
idempotencia y saga de compra con compensaciones.

**HU:** US-031, US-032, US-033, US-034 · **Puerto:** 16004 · **BD:** `tripbus_booking`

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/bookings` `{holdId, pasajeros:[{nombre, tipoDocumento, numeroDocumento}]}` | Crea la reserva y calcula el total (Catalog o Flash Offers). |
| POST | `/bookings/{id}/pay` + header **`Idempotency-Key`** `{cardToken, cardLast4}` | Inicia el pago (202). Reintentos con la misma llave devuelven la misma respuesta. |
| GET | `/bookings` · `/bookings/{id}` · `/bookings/{id}/saga` | Mis reservas, detalle y bitácora de la saga. |
| GET | `/tickets/{id}` · `/tickets/{id}/qr.png` | Tiquete con QR firmado (data URI / PNG). |
| POST | `/tickets/validate` `{qrCode}` | Validación al abordar (CONDUCTOR/OPERADOR/ADMIN). |
| POST | `/webhooks/payment` | Webhook de la pasarela (firma `X-Signature` HMAC-SHA256). |

### Saga

`PENDIENTE_PAGO → PAGO_EN_PROCESO → PAGO_APROBADO → CONFIRMADA` (con tiquete), o
`PAGO_RECHAZADO` (se libera la retención), `EXPIRADA` (la retención venció) o `REEMBOLSADA`
(pago aprobado pero el asiento ya no se pudo confirmar). Cada paso queda en `saga_log`; un job
retoma las sagas interrumpidas y otro concilia reservas/pagos/tiquetes cada 15 min.

### Pasarela sandbox

No guarda números de tarjeta: recibe un **token**. `tok_visa_approved` aprueba y
`tok_visa_declined` rechaza. El resultado llega por webhook firmado (~1,5 s) y se reenvía
duplicado para demostrar la idempotencia (`PAYMENT_DUPLICATE_WEBHOOK`).

**Publica:** `BookingCreated`, `PaymentApproved`, `PaymentFailed`, `TicketIssued`, `RefundProcessed`. **Consume:** `HoldExpired`.

## Variables

`DB_*`, `REDIS_HOST`, `IDENTITY_JWKS_URI`, `INTERNAL_API_TOKEN`, `SEAT_INVENTORY_URL`, `CATALOG_URL`, `FLASH_OFFERS_URL`, `PAYMENT_WEBHOOK_URL`, `PAYMENT_WEBHOOK_SECRET`, `TICKET_SECRET`.

## Ejecutar

Toda la plataforma: `cd ../API-gateway && docker compose up -d --build` (ver README de API-gateway).

Solo este servicio (con PostgreSQL y Redis del compose levantados):

```bash
mvn spring-boot:run          # puerto 16004
```

## Pruebas

```bash
mvn test                     # unitarias
TRIPBUS_IT=true mvn test     # + integración contra PostgreSQL/Redis reales (base tripbus_booking_test)
```

El pipeline `.github/workflows/ci-cd.yml` corre todas las pruebas y publica la imagen en GHCR.
