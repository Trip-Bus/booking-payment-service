# Booking + Payment Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_booking-payment-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_booking-payment-service)

Orquesta la compra: datos del viajero, total, pago con tarjeta (sandbox), tiquete con QR,
idempotencia y saga de compra con compensaciones.

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-031, US-032, US-033, US-034 · **Puerto:** 18004 · **BD:** `tripbus_booking` (puerto QA 18104)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/bookings` `{holdId, pasajeros:[{nombre, tipoDocumento, numeroDocumento}]}` | Crea la reserva y calcula el total (Catalog o Flash Offers). |
| POST | `/bookings/{id}/pay` + header **`Idempotency-Key`** `{cardToken, cardLast4}` | Inicia el pago (202). Reintentos con la misma llave devuelven la misma respuesta. |
| GET | `/bookings` · `/bookings/{id}` · `/bookings/{id}/saga` | Mis reservas, detalle y bitácora de la saga. |
| GET | `/tickets/{id}` · `/tickets/{id}/qr.png` | Tiquete con QR firmado (data URI / PNG). |
| POST | `/tickets/validate` `{qrCode}` | Validación al abordar (CONDUCTOR/OPERADOR/ADMIN). |
| POST | `/webhooks/payment` | Webhook de la pasarela (firma `X-Signature` HMAC-SHA256, **sin JWT**). |

### Saga

`PENDIENTE_PAGO → PAGO_EN_PROCESO → PAGO_APROBADO → CONFIRMADA` (con tiquete), o
`PAGO_RECHAZADO` (se libera la retención), `EXPIRADA` (la retención venció) o `REEMBOLSADA`
(pago aprobado pero el asiento ya no se pudo confirmar). Cada paso queda en `saga_log`; un job
retoma las sagas interrumpidas y otro concilia reservas/pagos/tiquetes cada 15 min.

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

**Publica:** `BookingCreated`, `PaymentApproved`, `PaymentFailed`, `TicketIssued`, `RefundProcessed`. **Consume:** `HoldExpired`.

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
`REDIS_PORT` (18100), `PAYMENT_DUPLICATE_WEBHOOK` (solo IDE; en qa/prod lo fija el perfil).
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

| Métrica | Medido (2026-10-12) | Mínimo |
|---|---|---|
| Líneas | 74,0 % | `jacoco.minimo.lineas` = 0.70 |
| Ramas | 61,2 % | `jacoco.minimo.ramas` = 0.60 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Las mejores candidatas para subir la cobertura son
`RemoteErrors`, `SagaRecoveryJob` y `EventConsumer`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `BookingApplication`, los records de `web/dto` (ninguno tiene métodos),
las entidades JPA de solo constructor/getters (`Passenger`, `Refund`, `SagaLogEntry`, `Ticket`,
`WebhookLog`, `ProcessedEvent`) y `config/OpenApiConfig`. `Booking`, `Payment`, `IdempotencyKey`,
`BookingProperties` y `PaymentProperties` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué no aplica a este servicio
(no usa XsltView ni SSE). Todas **vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no
se migró a Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la
versión (ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

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
