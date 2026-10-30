# Booking + Payment Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_booking-payment-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_booking-payment-service)

Orquesta la compra: datos del viajero, total, pago con tarjeta, PSE o Nequi (sandbox) o en efectivo en taquilla,
tiquete con QR (y en PDF), idempotencia y saga de compra con compensaciones.

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-031, US-032, US-033, US-034, US-150, US-139, US-152, US-154, US-155, US-172, US-143, US-144, US-145,
US-153, US-166 (lado de booking) · **Puerto:** 18004 · **BD:** `tripbus_booking` (puerto QA 18104)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/bookings` `{holdId, pasajeros:[{nombre, tipoDocumento, numeroDocumento}]}` | Crea la reserva y calcula el total (Catalog o Flash Offers). |
| POST | `/bookings/{id}/pay` + header **`Idempotency-Key`** `{cardToken, cardLast4}` | Inicia el pago (202). Reintentos con la misma llave devuelven la misma respuesta. |
| GET | `/bookings` · `/bookings/{id}` · `/bookings/{id}/saga` | Mis reservas, detalle y bitácora de la saga. |
| GET | `/bookings?scope=proximos\|pasados&page=0&size=10` | **US-145**: mis viajes próximos o pasados, paginado (ver abajo). |
| POST | `/box-office/sales` + header **`Idempotency-Key`** `{itineraryId, seatNumbers, pasajeros, comprador:{nombre, email?, telefono?}}` | **US-144** (OPERADOR): venta en taquilla con pago en efectivo. 201 con la reserva `CONFIRMADA` y su tiquete. |
| GET | `/box-office/sales?fecha=YYYY-MM-DD&vendedor=` | **US-144**: cierre de caja del día (OPERADOR: el suyo o el de otro vendedor de su empresa; ADMIN: todas). |
| POST | `/tickets/{id}/resend` | **US-145**: reenvía el tiquete al correo de la reserva (202). Máximo 3 por día → `429 RESEND_LIMIT_REACHED`. |
| GET | `/tickets/{id}/pdf` | **US-145**: tiquete en PDF con el QR, el viaje, los pasajeros y los asientos (dueño, OPERADOR de la empresa, ADMIN). |
| POST | `/bookings/{id}/reschedule` `{holdId}` | **US-150** (dueño): reprograma a otro viaje con los asientos de la retención. 201 con la reserva nueva (`PENDIENTE_PAGO` si hay diferencia, `CONFIRMADA` si no); 200 si esa retención ya se usó para esto. |
| GET | `/tickets/{id}` · `/tickets/{id}/qr.png` | Tiquete con QR firmado (data URI / PNG). |
| POST | `/tickets/validate` `{qrCode}` | Validación al abordar (CONDUCTOR/OPERADOR/ADMIN). |
| POST | `/tickets/{id}/seat` `{fromSeat, toSeat}` | **US-139** (dueño): cambia de asiento en el mismo viaje; el tiquete queda con QR nuevo. |
| PATCH | `/tickets/{id}/passengers/{passengerId}` `{nombre, tipoDocumento, numeroDocumento}` | **US-152** (dueño): corrige el nombre o el documento de un pasajero, una sola vez. |
| GET | `/bookings/affected` · `/bookings/{id}/affected` | **US-154**: mis reservas afectadas pendientes y el detalle (causa, opciones, plazo, decisión). |
| POST | `/bookings/{id}/affected/reschedule` `{holdId}` · `/affected/refund` · `/affected/accept` | **US-154** (dueño): reprogramar gratis, reembolso o aceptar el nuevo horario. |
| GET | `/itineraries/{id}/manifest` `?format=csv` | **US-172**: manifiesto de pasajeros (ADMIN, OPERADOR/CONDUCTOR de la empresa). |
| POST | `/webhooks/payment` | Webhook de la pasarela (firma `X-Signature` HMAC-SHA256, **sin JWT**). |

`pasajeros[].passengerId` (en la reserva y en el tiquete) es el que usa el PATCH. `BookingResponse.origenBookingId`
indica de qué reserva viene una reprogramación.

### Saga

`PENDIENTE_PAGO → PAGO_EN_PROCESO → PAGO_APROBADO → CONFIRMADA` (con tiquete), o
`PAGO_RECHAZADO` (se libera la retención), `EXPIRADA` (la retención venció) o `REEMBOLSADA`
(pago aprobado pero el asiento ya no se pudo confirmar). Cada paso queda en `saga_log`; un job
retoma las sagas interrumpidas y otro concilia reservas/pagos/tiquetes cada 15 min.
`CONFIRMADA → REPROGRAMADA` (US-150): se confirmó la reserva nueva que la reemplaza.
En taquilla (US-144) la reserva recorre los mismos estados en un instante: el pago `EFECTIVO` se aprueba sin pasarela.

Estados del tiquete:

| Estado | Significado | Al abordar (`POST /tickets/validate`) |
|---|---|---|
| `EMITIDO` | Vigente, pendiente de abordar. | Válido una sola vez (pasa a `USADO`). |
| `USADO` | Abordó. | `valido: false, motivo: YA_USADO`. |
| `REPROGRAMADO` | Se cambió por el de una reserva nueva (US-150). | `409 TICKET_RESCHEDULED`. |
| `ANULADO` | Reembolsado por un cambio de la empresa (US-154). | `409 TICKET_VOIDED`. |
| `NO_SHOW` | El viaje salió hace más de 30 min y no abordó (US-153). Sin reembolso. | `valido: false, motivo: NO_SHOW`. |

Un QR que un cambio de asiento reemplazó responde `valido: false, motivo: QR_REEMPLAZADO`.

### Reprogramar el tiquete (US-150)

Política: **no hay reembolso por decisión del pasajero**. El pasajero retiene los asientos del viaje nuevo con
el `POST /seats/holds` normal y llama a `POST /bookings/{id}/reschedule {holdId}`. Se valida (409 con código):

| Código | Regla |
|---|---|
| `BOOKING_NOT_RESCHEDULABLE` / `TICKET_NOT_RESCHEDULABLE` | La reserva debe estar `CONFIRMADA` y su tiquete `EMITIDO` (no usado) o `NO_SHOW` (US-153, ver abajo). |
| `RESCHEDULE_WINDOW_CLOSED` | Deben faltar más de `tripbus.booking.reschedule-cutoff` (3 h) para la salida del viaje original. |
| `OFFER_FARE_NOT_RESCHEDULABLE` · `503 OFFERS_UNAVAILABLE` | **US-166**: si la reserva es de oferta, flash-offers (`GET /internal/offers/{id}`) debe permitir reprogramar; si no responde, 503. |
| `NO_SHOW_WINDOW_CLOSED` · `NO_SHOW_RESCHEDULE_DISABLED` | **US-153**: un no-show se reprograma dentro de la ventana y si la política está activa. |
| `RESCHEDULE_LIMIT_REACHED` | Máximo `tripbus.booking.max-reschedules` (2) en toda la cadena de reprogramaciones. |
| `RESCHEDULE_IN_PROGRESS` | Ya hay una reprogramación viva de esa reserva (también lo impide un índice único en la BD). |
| `HOLD_NOT_OWNED` · `HOLD_NOT_ACTIVE` · `SEATS_PASSENGERS_MISMATCH` · `SAME_ITINERARY` · `HOLD_ALREADY_USED` | La retención es del mismo usuario, está activa, tiene un asiento por pasajero y es de otro viaje. |
| `ITINERARY_NOT_AVAILABLE` · `ITINERARY_DEPARTED` · `ROUTE_MISMATCH` · `COMPANY_MISMATCH` · `CURRENCY_MISMATCH` | El viaje nuevo (catalog) no está cancelado, sale en el futuro, tiene el mismo origen y destino y es de la misma empresa. |

Solo el dueño puede reprogramar o cambiar su tiquete: el OPERADOR (su empresa) y el ADMIN siguen consultando
todo como siempre, pero las acciones les responden `403 NOT_BOOKING_OWNER` / `NOT_TICKET_OWNER`.

Se crea una reserva nueva con `origen_booking_id`, los mismos pasajeros (con los asientos nuevos) y
`total = max(0, pasaje nuevo − pasaje ya pagado) + tripbus.booking.reschedule-fee` (0 por defecto). El pasaje nuevo
se calcula como siempre (catalog o flash-offers) y el pasaje ya pagado es el acumulado de la cadena
(`bookings.valor_pagado`: si el viaje nuevo es más barato se conserva lo pagado). Con diferencia la reserva queda
`PENDIENTE_PAGO` y se paga con el `POST /bookings/{id}/pay` de siempre; sin diferencia nace en `PAGO_APROBADO` y se
confirma de inmediato sin pago. La reserva original no se toca hasta entonces: si la nueva expira o su pago se
rechaza, sigue intacta.

Al confirmarse la reserva nueva (`PaymentService.confirmAndIssue` + `RescheduleSaga`):

1. ConfirmSale del hold nuevo en seat-inventory (`ASIENTO_VENDIDO`).
2. En **una sola transacción**: el tiquete viejo pasa a `REPROGRAMADO` con un UPDATE condicional que compite con
   el abordaje, la reserva vieja a `REPROGRAMADA`, se emite el tiquete nuevo con QR nuevo y la reserva nueva queda
   `CONFIRMADA` (`REPROGRAMACION_APLICADA` / `REPROGRAMADA` en `saga_log`). El tiquete nuevo se envía por correo.
   Así nunca quedan dos tiquetes válidos. Si el tiquete viejo ya se usó para abordar, la reprogramación no aplica:
   se reembolsa (`REPROGRAMACION_NO_APLICABLE`) y se devuelven los asientos nuevos.
3. Se anulan los asientos viejos con `POST /internal/sales/void` (motivo `REPROGRAMACION`): paso
   `ANULAR_ASIENTOS_ANTERIORES` (`OK`, `REINTENTO` si seat-inventory no responde, `ERROR` si la rechaza). Las que
   quedan pendientes (`anulacion_resuelta_en` nulo) las reintenta el job de recuperación de sagas.

### Cambiar de asiento (US-139) y corregir datos del pasajero (US-152)

- **Asiento:** tiquete `EMITIDO` y más de 30 min antes de la salida (`SEAT_CHANGE_WINDOW_CLOSED`). Llama a
  seat-inventory `POST /internal/sales/swap` (sus 409, p. ej. `SEAT_UNAVAILABLE`, se devuelven tal cual) y, si
  responde bien, actualiza el asiento del pasajero y `bookings.seat_numbers` en la misma transacción. El QR incluye
  los asientos: se genera uno nuevo (nuevo `qr_token`, el viejo deja de validar) y se reenvía el tiquete por correo.
- **Pasajero:** tiquete `EMITIDO` y más de 1 h antes de la salida (`PASSENGER_CHANGE_WINDOW_CLOSED`), una sola vez
  por pasajero (`409 PASSENGER_ALREADY_CORRECTED`, lo garantiza `passenger_changes` con los valores anteriores y
  nuevos, `hecho_por` y `creado_en`). El QR no incluye esos datos, así que se conserva.

### Pasarela sandbox

No guarda números de tarjeta: recibe un **token** (y solo los últimos 4 dígitos).
`tok_visa_approved` aprueba y `tok_visa_declined` rechaza. El resultado llega por webhook firmado
(~1,5 s). Un webhook con firma inválida responde `401 INVALID_SIGNATURE` y queda en `webhook_log`.
Un APPROVED cuyo `amount`/`currency` no coincide con el pago no se aprueba: queda `MONTO_INCORRECTO`
en `webhook_log`, el pago se rechaza y se libera el asiento. Si la pasarela aprueba un pago que ya se
había dado por fallido (sin respuesta a los 5 min o pasarela caída), se reembolsa una sola vez
(`APROBADO_TARDIO_REEMBOLSADO` en `saga_log`).

Las transiciones de estado de la reserva están validadas (`Booking.cambiarEstado`); una no permitida
responde `409 INVALID_BOOKING_TRANSITION`. `GET /bookings/{id}` y `/saga`: el dueño ve las suyas, ADMIN
todas y OPERADOR solo las de su empresa (claim `companyId`); otra empresa responde 404.

La pasarela puede reenviar el mismo webhook para demostrar la idempotencia (US-034:
`tripbus.payment.duplicate-webhook`): **encendido en QA** (`application-qa.yml`, para seguir
probando que un webhook repetido no cobra ni emite dos veces) y **apagado en PROD**
(`application-prod.yml`). Desde el IDE lo controla `PAYMENT_DUPLICATE_WEBHOOK` (por defecto `true`).

**Publica:** `BookingCreated`, `PaymentApproved`, `PaymentFailed`, `TicketIssued`, `RefundProcessed`,
`TicketRescheduled` `{userId, bookingIdAnterior, bookingIdNuevo, ticketIdAnterior, ticketIdNuevo, itineraryIdAnterior,
itineraryIdNuevo}` (US-150), `TicketUpdated` `{userId, bookingId, ticketId, cambio: ASIENTO | PASAJERO}` (US-139 con
`fromSeat`/`toSeat`, US-152 con `passengerId`), `BookingAffected` `{userId, bookingId, ticketId, itineraryId, causa,
plazoDecision, opciones}` (US-154) y `BookingRelocated` `{userId, bookingIdAnterior, bookingIdNuevo, ticketIdAnterior,
ticketIdNuevo, itineraryIdAnterior, itineraryIdNuevo, seatNumbers}` (US-155).
**Consume:** `HoldExpired` (seat-inventory), `ItineraryCancelled` e `ItineraryRescheduled` (catalog). Idempotente por
`eventId`: `processed_events` se escribe en la misma transacción que el manejador, así un evento repetido no vuelve a
afectar reservas ni a avisar al pasajero.

### Política de reembolso

**Solo hay reembolso cuando el pasajero no tiene la culpa**: la empresa canceló, reprogramó o retrasó (≥ umbral) el
viaje. Por decisión propia no existe (reprogramar con US-150 nunca devuelve dinero). El reembolso solo se puede pedir
sobre una reserva afectada (`/bookings/{id}/affected/refund`); sobre cualquier otra responde `404 BOOKING_NOT_AFFECTED`.
Las otras devoluciones automáticas siguen siendo las de la saga de compra (asiento perdido tras el pago y aprobado
tardío), que tampoco son culpa del pasajero.

### Viajes afectados por la empresa (US-154) y decisión automática (US-155)

| Evento de catalog | Causa | Afecta |
|---|---|---|
| `ItineraryCancelled` | `CANCELACION` | siempre |
| `ItineraryRescheduled` tipo `REPROGRAMACION` | `REPROGRAMACION` | siempre |
| `ItineraryRescheduled` tipo `RETRASO` | `RETRASO` | si `minutosDiferencia >= tripbus.booking.affected-delay-min` (60); un retraso menor no afecta a nadie |

Quedan afectadas las reservas `CONFIRMADA` del viaje con tiquete `EMITIDO` (no usado): una fila en `affected_bookings`
`PENDIENTE` con `plazo_decision = ahora + tripbus.booking.affected-decision-window` (24 h), un correo al pasajero (qué
pasó, qué puede elegir y hasta cuándo; mismo SMTP que el tiquete) y el evento `BookingAffected`. Si la empresa vuelve
a cambiar el viaje y la reserva sigue confirmada, se reabre con la causa nueva (una cancelación pendiente no se
reemplaza por un cambio posterior).

| Causa | Opciones del dueño |
|---|---|
| `CANCELACION` | reprogramar gratis o reembolso |
| `REPROGRAMACION` / `RETRASO` | aceptar el nuevo horario, reprogramar gratis o reembolso |

- **Aceptar** (`/accept`): el tiquete sigue válido con el nuevo horario → `ACEPTO_CAMBIO`. Con el viaje cancelado,
  `409 OPTION_NOT_AVAILABLE`.
- **Reprogramar gratis** (`/reschedule {holdId}`): el flujo de US-150 con diferencia 0 (sin cargo), sin el límite de
  cambios ni el plazo de 3 h, con el mismo origen y destino y de cualquier empresa → `REPROGRAMADA` (`bookingNuevoId`).
- **Reembolso** (`/refund`): devuelve con `PaymentGateway.refund` todo lo pagado en la cadena de reprogramaciones, una
  sola vez (`refunds` es único por pago; repetirlo responde `200` con el mismo resultado). El tiquete pasa a `ANULADO`
  y la reserva a `REEMBOLSADA` en la misma transacción; si el viaje no fue cancelado se anulan los asientos con
  seat-inventory `/internal/sales/void` (motivo `REEMBOLSO`, se reintenta si no responde). Publica `RefundProcessed`.
- Una reserva ya decidida responde `409 ALREADY_DECIDED`; con el plazo vencido, `409 DECISION_WINDOW_CLOSED` (decide
  el sistema). Solo decide el dueño: OPERADOR (su empresa) y ADMIN consultan `/affected` pero reciben `403`.

**Al vencer el plazo** (`AffectedBookingJob`, cada 5 min en un solo nodo con `LeaderLock`), `decidido_por = SISTEMA`:
`REPROGRAMACION`/`RETRASO` → `ACEPTO_CAMBIO`. `CANCELACION` → busca en catalog (`/trips/search`, día por día) el
siguiente viaje de la misma ruta y empresa que salga después del original y dentro de los 3 días siguientes con cupo
para todos los pasajeros, retiene los primeros asientos disponibles (`GET /seats/itineraries/{id}` y
`POST /internal/holds` a nombre del pasajero) y confirma con el flujo gratis → `REUBICADA` + `BookingRelocated`; si no
hay cupo → reembolso automático (`REEMBOLSADA` + `RefundProcessed`). Si catalog o seat-inventory no responden no se
concluye "sin cupo": se reintenta en la siguiente pasada. Si el pasajero ya resolvió por su cuenta (reprogramó con
US-150 o abordó) se registra como su decisión. En todos los casos se le avisa por correo.

### Manifiesto de pasajeros (US-172)

`GET /itineraries/{id}/manifest` (`?format=csv` para CSV): ADMIN, y OPERADOR o CONDUCTOR de la empresa del viaje (claim
`companyId`; la validación contra la asignación de fleet llega después). Otra empresa → `404 ITINERARY_NOT_FOUND`;
otro rol → `403`. Por pasajero: nombre, tipo y número de documento, asiento, `bookingId`, estado del tiquete
(`EMITIDO` = pendiente de abordar, `USADO` = abordó) y hora de abordaje; totales `vendidos`, `abordados` y
`pendientes`. No incluye tiquetes `REPROGRAMADO` ni `ANULADO`. Es información personal: cada consulta (quién, rol,
cuándo, qué viaje, formato y si se permitió) queda en `manifest_access_log`. El CSV escapa comas y comillas y
antepone `'` a los valores que empiezan por `= + - @` (inyección de fórmulas).

### Datos del viaje en la reserva

Al crear la reserva (app, taquilla o reprogramación) se copian de catalog `origen`, `destino`, `fecha_salida` y
`empresa_nombre`, junto con el `canal` (`APP` | `TAQUILLA`). Así "mis viajes", el cierre de caja y el job de no-show no
consultan a catalog por cada reserva. Las reservas anteriores las rellena `TripDataBackfill` al arrancar (idempotente:
solo las que no tienen `fecha_salida`); si catalog no responde, quedan para el siguiente arranque. `ItineraryRescheduled`
actualiza `fecha_salida` de todas las reservas del viaje.

### Venta en taquilla (US-144)

`POST /box-office/sales` (solo OPERADOR, con `Idempotency-Key` igual que el pago: repetirla devuelve la misma venta y
con otro cuerpo responde `422 IDEMPOTENCY_KEY_REUSED`):

1. El viaje debe ser de la empresa del operador (si no, `404 ITINERARY_NOT_FOUND`), no estar cancelado
   (`409 ITINERARY_CANCELLED`) ni haber salido (`409 ITINERARY_DEPARTED`). Un pasajero por asiento.
2. Retiene los asientos en seat-inventory (`POST /internal/holds`) **a nombre del operador**.
3. En una transacción: reserva `canal = TAQUILLA` con `vendido_por` (el operador) y los datos del comprador, y un pago
   `EFECTIVO` aprobado al instante (sin pasarela). Luego confirma la venta en seat-inventory y emite el tiquete con el
   mismo paso de la saga de la app. Cada paso queda en `saga_log` (`RESERVA_CREADA`, `PAGO_EFECTIVO_APROBADO`,
   `ASIENTO_VENDIDO`, `TIQUETE_EMITIDO`). Si el asiento se perdió al confirmar, la reserva queda `REEMBOLSADA` (se
   devuelve el efectivo; la pasarela no interviene).
4. Si el correo del comprador es de un PASAJERO registrado (identity `GET /internal/users/by-email`), la reserva queda a
   su nombre y aparece en sus viajes; si no (o identity no responde), queda a nombre del operador. Si dio correo, se le
   envía el tiquete como en la app.

El tiquete de taquilla se valida al abordar igual que uno de la app. `GET /box-office/sales?fecha=` (hoy por defecto,
hora de Colombia) es el cierre de caja: ventas, tiquetes, asientos, `totalEfectivo` y `totalReembolsado`. El OPERADOR ve
las suyas o, con `?vendedor=`, las de otro vendedor de su empresa; el ADMIN ve todas o las de `?vendedor=`.

### Mis viajes, reenvío y PDF (US-145)

`GET /bookings?scope=proximos|pasados` (paginado, `size` hasta 50): **próximos** son los de salida hoy (hora de Colombia)
o después, de la más cercana a la más lejana; **pasados**, el resto, de la más reciente a la más vieja. Solo reservas
`CONFIRMADA`, `REPROGRAMADA` y `REEMBOLSADA`, con su estado visible. Cada viaje trae origen, destino, salida, empresa,
asientos, estado del tiquete, canal y si hoy se podría reprogramar (`puedeReprogramar`) o cambiar de asiento
(`puedeCambiarAsiento`). La regla de la tarifa de oferta se valida con flash-offers al reprogramar.

`POST /tickets/{id}/resend` reenvía el tiquete al correo de la reserva: máximo 3 por día (UPDATE atómico; el cuarto
responde `429 RESEND_LIMIT_REACHED`). `409 TICKET_NOT_RESENDABLE` si está `REPROGRAMADO` o `ANULADO`,
`409 NO_CONTACT_EMAIL` sin correo y `503 MAIL_UNAVAILABLE` sin SMTP. `GET /tickets/{id}/pdf` genera el PDF (OpenPDF,
LGPL/MPL) con el número de tiquete, el QR, el viaje, los pasajeros y los asientos. Ambos solo para el dueño, el OPERADOR
de la empresa del viaje y el ADMIN; para cualquier otro el tiquete no existe (404).

### No-show (US-153)

Cada 5 min, en un solo nodo, `NoShowJob` pasa a `NO_SHOW` los tiquetes `EMITIDO` de viajes que salieron hace más de
`tripbus.booking.no-show-after` (30 min) y publica `TicketNoShow {userId, bookingId, ticketId, itineraryId}`. **No hay
reembolso.** No aplica a reservas afectadas por la empresa pendientes de decisión ni a viajes cancelados. Un no-show se
puede reprogramar con el flujo de US-150 si `tripbus.booking.no-show-reschedule-enabled` (true), dentro de
`tripbus.booking.no-show-reschedule-window` (24 h) después de la salida, pagando la diferencia de precio más
`tripbus.booking.no-show-fee-percent` (20 %) del total pagado. Un tiquete de oferta en no-show no se reprograma.

### Tarifa de oferta (US-166, lado de booking)

Al reprogramar (US-150 y no-show), si la reserva tiene `offer_id` se consulta flash-offers `GET /internal/offers/{id}`
(con `X-Internal-Token`): `permiteReprogramar = false` → `409 OFFER_FARE_NOT_RESCHEDULABLE`; si flash-offers no responde
→ `503 OFFERS_UNAVAILABLE`. Las reservas **afectadas por la empresa** (US-154) siempre se pueden reprogramar gratis o
reembolsar, aunque sean de oferta: el pasajero no tiene la culpa.

## Flujo de ramas y entornos

```
rama de trabajo --PR--> develop (QA) --PR de release--> main (PROD)
```

- `main` sigue siendo la rama por defecto en GitHub, pero **los PR de trabajo van con base `develop`**.
- `develop` es QA y `main` es PROD: a `main` solo llega código por un PR de release desde `develop`.

| Evento | Pruebas + JaCoCo + Trivy | SonarQube Cloud | Imagen publicada en GHCR |
|---|---|---|---|
| PR con base `develop` | ✔ | ✔ (quality gate) | no |
| push a `develop` (merge de un PR) | ✔ | ✔ (quality gate) | `:qa`, `:develop`, `:sha-<corto>` |
| PR con base `main` (release) | ✔ | — | no |
| push a `main` (merge del release) | ✔ | — | `:prod`, `:latest`, `:sha-<corto>` |
| `workflow_dispatch` | ✔ | solo si se lanza sobre `develop` | no |

**Por qué Sonar solo corre en `develop`:** el plan Free de SonarQube Cloud analiza una única rama,
y la rama principal del proyecto en Sonar es `develop`. Lo que llega a `main` es exactamente el
código que ya pasó el quality gate en `develop`, así que en `main` el CI hace `mvn verify` sin Sonar
y lo indica con un aviso (`::notice::`). El control de cobertura de JaCoCo (`jacoco:check`) sí corre
en todas las ramas.

**Cómo hacer un release:**

1. Asegúrate de que el último CI de `develop` esté en verde (pruebas, cobertura, quality gate y
   Trivy) y de haber probado la imagen `:qa`.
2. Abre un PR con base `main` y rama de origen `develop` (título p. ej. `release: <resumen>`).
3. Cuando el CI del PR pase, haz merge (sin squash, para que `main` y `develop` compartan historial).
   El push a `main` publica la imagen `:prod` / `:latest`.

## Entornos (IDE / QA / PROD)

| Entorno | Cómo corre | Perfil Spring | Secretos |
|---|---|---|---|
| IDE | `mvn spring-boot:run` contra los contenedores de QA | (ninguno) | valores por defecto de QA en `application.yml` |
| QA | Docker Compose en tu computador | `qa` (+ `desplegado`) | `.env` (copia de `.env.example`) |
| PROD | Docker Compose en el servidor | `prod` (+ `desplegado`) | solo en el servidor |

Puertos de este repo (el contenedor escucha en el mismo puerto que publica):

| Servicio | QA (publicado) | Dentro de Docker | PROD |
|---|---|---|---|
| booking-payment-service | 18004 | 18004 | no se publica (entra por api-gateway 18000) |
| PostgreSQL `tripbus_booking` / usuario `booking` | 18104 | 5432 | nunca se publica |
| Redis (compartido, con contraseña) | 18100 | 6379 | nunca se publica |

Otros servicios que usa (por defecto en el IDE; en `qa`/`prod` no tienen valor por defecto y son
obligatorias): identity (`IDENTITY_JWKS_URI`, `http://localhost:18001/.well-known/jwks.json`),
catalog (`CATALOG_URL`, `http://localhost:18002`), seat-inventory (`SEAT_INVENTORY_URL`,
`http://localhost:18003`) y flash-offers (`FLASH_OFFERS_URL`, `http://localhost:18006`).
`PAYMENT_WEBHOOK_URL` es la URL a la que la pasarela sandbox llama al propio servicio.

### Levantar QA

```bash
cp .env.example .env && docker compose up -d --build
docker compose ps                                   # esperar a "healthy"
curl http://localhost:18004/actuator/health
curl -i http://localhost:18004/bookings             # 401 JSON (exige JWT)
```

Con este compose solo funcionan actuator, Swagger y `/webhooks/payment` (se autentica con la firma
HMAC). `/bookings` y `/tickets` exigen un JWT de identity y la compra completa necesita catalog,
seat-inventory y flash-offers: pruébalo con el compose general de **API-gateway**. El servicio llega
a esos servicios de tu PC por `host.docker.internal`, y la pasarela sandbox se llama a sí misma por
`http://booking-payment-service:18004/webhooks/payment`.

No lo corras a la vez que el compose general de **API-gateway** (usan los mismos puertos).
`docker compose down` detiene conservando los datos; `down -v` los borra.

### Imágenes Docker (GHCR)

El CI publica `ghcr.io/trip-bus/booking-payment-service` solo en push (nunca desde un PR):

| Etiqueta | Rama | Uso |
|---|---|---|
| `:qa` (y `:develop`) | `develop` | Última versión de QA. |
| `:prod` (y `:latest`) | `main` | Última versión de PROD; es la que se despliega en el servidor. |
| `:sha-<corto>` | ambas | Versión inmutable de un commit concreto (para fijar o hacer rollback). |

`docker compose up --build` en tu computador construye la imagen local `ghcr.io/trip-bus/booking-payment-service:qa-local`;
para usar la de QA ya publicada: `docker pull ghcr.io/trip-bus/booking-payment-service:qa`.

### Correr desde el IDE contra QA

Con el compose de QA arriba, `docker compose stop booking-payment-service` (libera el 18004) y luego
`mvn spring-boot:run` sin perfil: `application.yml` ya apunta a `localhost:18104` (BD) y
`localhost:18100` (Redis). Para Redis define `REDIS_PASSWORD=tripbus_redis_qa_pw` (el compose de QA
lo exige con contraseña).

### Variables

En `qa` y `prod` se activa el perfil `desplegado`: si falta alguna variable obligatoria el servicio
no arranca y dice cuáles faltan (`RequiredSecretsCheck`).

| Variable | Obligatoria en qa/prod | Por defecto (IDE) |
|---|---|---|
| `DB_PASSWORD` | ✔ | `booking_qa_pw` |
| `REDIS_PASSWORD` | ✔ | (vacía) |
| `INTERNAL_API_TOKEN` | ✔ | `tripbus_internal_qa_token` |
| `IDENTITY_JWKS_URI` | ✔ | `http://localhost:18001/.well-known/jwks.json` |
| `SEAT_INVENTORY_URL` | ✔ | `http://localhost:18003` |
| `CATALOG_URL` | ✔ | `http://localhost:18002` |
| `FLASH_OFFERS_URL` | ✔ | `http://localhost:18006` |
| `TICKET_SECRET` | ✔ | `tripbus_ticket_qa_secret` |
| `PAYMENT_WEBHOOK_SECRET` | ✔ | `tripbus_webhook_qa_secret` |
| `PAYMENT_WEBHOOK_URL` | ✔ | `http://localhost:18004/webhooks/payment` |

Opcionales con valor por defecto: `SERVER_PORT` (18004), `DB_HOST` (localhost), `DB_PORT` (18104),
`DB_NAME` (tripbus_booking), `DB_USERNAME` (booking), `DB_POOL_SIZE`, `REDIS_HOST` (localhost),
`REDIS_PORT` (18100), `PAYMENT_DUPLICATE_WEBHOOK` (solo IDE; en qa/prod lo fija el perfil), `IDENTITY_URL`
(`http://localhost:18001`; si identity no responde, la venta en taquilla queda a nombre del operador),
`NO_SHOW_RESCHEDULE_ENABLED` (true) y `TRIP_BACKFILL_ENABLED` (true).
`DB_HOST` reemplaza a la antigua `DB_URL`.

Tiquete por correo (US-033, opcionales en todos los entornos): `MAIL_HOST` (vacío = envío
deshabilitado, con un WARN al arrancar), `MAIL_PORT` (1025), `MAIL_USERNAME`, `MAIL_PASSWORD` y
`MAIL_FROM` (`TripBus <no-reply@tripbus.co>`). Al emitirse el tiquete se envía al correo del claim
`email` del JWT con el QR en PNG adjunto; si el SMTP falla, un job lo reintenta cada minuto hasta 5
veces. El correo nunca cambia el estado de la reserva ni del pago.

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18004/swagger-ui.html
- OpenAPI JSON: http://localhost:18004/v3/api-docs

Haz login en identity (`/auth/login`) y pega el `accessToken` en **Authorize**. `/webhooks/payment`
no usa JWT: se autentica con la firma HMAC del header `X-Signature`.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Booking lo **propaga** en sus llamadas salientes a
  seat-inventory, catalog y flash-offers, así una petición se sigue entre servicios. Logs legibles con
  `[requestId]` en IDE/QA y JSON (con `requestId` y `"service":"booking-payment-service"`) en PROD.
- El log de acceso solo registra método, ruta, estado y duración: nunca cuerpos de petición (no se
  registran datos de pago).

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias (hash de idempotencia, `RequiredSecretsCheck`, `RequestIdFilter`,
  propagación del requestId, clientes salientes y `DuplicateWebhookPorPerfilTest`, que comprueba
  que el reenvío del webhook queda encendido en `qa` y apagado en `prod`).
- `BookingSagaIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones Flyway
  reales y prueba la saga con los otros servicios simulados: 5 reintentos concurrentes = 1 cobro,
  webhook duplicado = 1 tiquete, pago rechazado libera la retención, asiento perdido ⇒ reembolso,
  firma inválida ⇒ 401 registrado en `webhook_log`.
- `RescheduleIT` (Sprint 3, mismo montaje): reprogramar con diferencia (se paga y reemplaza el tiquete), sin
  diferencia (se confirma sin pago ni reembolso) y con la retención equivocada; fuera de plazo y límite de cambios
  de toda la cadena; la anulación de asientos falla y el job la recupera; nunca quedan dos tiquetes válidos (también
  si se aborda con el viejo antes de pagar o con dos reprogramaciones simultáneas); pago rechazado deja intacta la
  original; cambio de asiento con QR nuevo y con conflicto en seat-inventory; corrección del pasajero repetida.
  Las reglas una a una están en `RescheduleServiceTest`, `RescheduleSagaTest` y `TicketChangeServiceTest`.
- `AffectedIT` (Sprint 3, con el consumidor del stream encendido): evento repetido (mismo `eventId`) que afecta y avisa
  una sola vez; retraso menor al umbral; aceptar, reprogramar gratis y reembolso; doble reembolso concurrente (un solo
  cobro devuelto); sin cambio de la empresa no hay reembolso; plazo vencido de un retraso, de una cancelación con cupo
  (reubicación) y sin cupo (reembolso, y sin catalog sigue pendiente); manifiesto por rol, CSV, otra empresa → 404 y
  registro de accesos. Las reglas una a una están en `AffectedBookingServiceTest`, `AffectedRefundServiceTest`,
  `AffectedBookingJobTest`, `ManifestServiceTest` y `AffectedMailServiceTest`.
- `TaquillaYMisViajesIT` (Sprint 4, con SMTP en memoria): venta en taquilla con y sin cuenta del comprador, de otra
  empresa, cancelada o que ya salió, `Idempotency-Key` repetida, efectivo devuelto sin pasarela y cierre de caja por
  vendedor; mis viajes próximos y pasados; reenvío por encima del límite; PDF con el número de tiquete; no-show por el
  job; reprogramar un no-show dentro y fuera de plazo; oferta no reprogramable (y sin flash-offers) y afectada de
  oferta que sí se puede; relleno de los datos del viaje. Las reglas una a una están en `BoxOfficeServiceTest`,
  `MyTripsServiceTest`, `TicketDocumentServiceTest`, `NoShowJobTest`, `RescheduleServiceTest` y `ClientesSprint4Test`.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_booking-payment-service` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla si la cobertura total baja de los mínimos del `pom.xml`, en **todas** las ramas
(también en `main`, donde Sonar no corre):

| Métrica | Medido (2026-10-26) | Mínimo |
|---|---|---|
| Líneas | 90,2 % | `jacoco.minimo.lineas` = 0.90 |
| Ramas | 82,5 % | `jacoco.minimo.ramas` = 0.80 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Las mejores candidatas para subir la cobertura son
`RemoteErrors`, `SagaRecoveryJob` y `EventConsumer`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `BookingApplication`, los records de `web/dto` (ninguno tiene métodos),
los records `repository/ManifestRow`, las entidades JPA de solo constructor/getters (`Passenger`, `PassengerChange`,
`ManifestAccessLog`, `Refund`, `SagaLogEntry`, `Ticket`,
`WebhookLog`, `ProcessedEvent`) y `config/OpenApiConfig`. `Booking`, `Payment`, `IdempotencyKey`,
`BookingProperties` y `PaymentProperties` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué no aplica a este servicio
(no usa XsltView ni SSE). Todas **vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no
se migró a Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la
versión (ver las propiedades `*.version` de parches de seguridad en `pom.xml`). Los paquetes de Alpine de la
imagen de ejecución se actualizan con `apk upgrade --no-cache` en el `Dockerfile`, así un parche publicado después
de la imagen base (p. ej. CVE-2026-111497 de `brotli-libs`) entra en el siguiente build.

El análisis de Sonar solo corre en `develop` (push y PR con base `develop`, ver
[Flujo de ramas y entornos](#flujo-de-ramas-y-entornos)) y si el repo tiene el secreto `SONAR_TOKEN`;
en otro caso el CI hace `mvn verify` y deja un aviso.

### Secretos del CI (`SONAR_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)

| Secreto | Valor |
|---|---|
| `SONAR_TOKEN` | Token de SonarQube Cloud con acceso al proyecto `Trip-Bus_booking-payment-service` |
| `DOCKERHUB_USERNAME` | Usuario de Docker Hub |
| `DOCKERHUB_TOKEN` | Personal access token con permiso **Public Repo Read-only** (solo lectura; nunca la contraseña) |

Los runners de GitHub comparten IP y agotan el límite de descargas anónimas de Docker Hub
(`toomanyrequests: You have reached your unauthenticated pull rate limit`), lo que rompe
Testcontainers (`postgres:16-alpine`, Redis) y el build de la imagen (`maven`, `eclipse-temurin`).
Con los secretos de Docker Hub los jobs `secretos`, `build-test` y `docker` inician sesión antes de
descargar nada y usan el límite de una cuenta autenticada. Son **opcionales pero recomendados**: si
faltan, el CI descarga sin autenticar, deja un aviso y puede fallar por el límite. Gitleaks se
descarga desde GHCR (`ghcr.io/gitleaks/gitleaks`), que no tiene ese límite. Todos se crean en
Settings → Secrets and variables → Actions.

Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact
docker build -t booking-payment-service:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml booking-payment-service:local
```
# Pagos sandbox (US-143)

`POST /bookings/{id}/pay` conserva el cuerpo histórico de tarjeta (`cardToken`, `cardLast4`, `cardBrand`) y ahora acepta `metodo` (`TARJETA`, `PSE` o `NEQUI`). Incluya siempre `Idempotency-Key`.

- Tarjeta: `tok_visa_approved` aprueba y `tok_visa_declined` rechaza.
- Nequi: `{"metodo":"NEQUI","nequi":{"telefono":"3991111111"}}` aprueba; `3992222222` rechaza y cualquier otro celular colombiano de 10 dígitos genera error. La respuesta queda pendiente mientras se acepta en la app.
- PSE: consulte `GET /payments/pse/banks`; use el banco `1` para aprobar o `2` para rechazar. Envíe `pse` con `bancoCodigo`, `tipoPersona` (`NATURAL`/`JURIDICA`), `tipoDocumento` y `numeroDocumento`. La respuesta devuelve `redirectUrl`; la página sandbox permite completar el pago y vence a los 10 minutos.

El sandbox no guarda números de tarjeta, teléfonos ni documentos completos: solo últimos cuatro dígitos y, para PSE, el nombre del banco. Para conectar Wompi, PayU u otra pasarela se implementa `PaymentGateway`; la saga, el webhook firmado y los reembolsos no requieren cambios ni llaves de proveedor en este proyecto.
