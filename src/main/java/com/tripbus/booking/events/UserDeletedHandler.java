package com.tripbus.booking.events;

import com.tripbus.booking.common.events.DomainEventHandler;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * US-131 (Sprint 5): UserDeleted de identity. En las reservas del usuario se borran el correo de contacto y los datos
 * del comprador de taquilla (nombre, correo y teléfono); los pasajeros se conservan para el manifiesto y la
 * contabilidad, salvo en las reservas que nunca se pagaron. Sus reservas futuras siguen válidas, pero sin correo el
 * tiquete ya no se envía ni se reenvía. Idempotente por eventId (EventConsumer) y además por la propia consulta (solo
 * toca las reservas que aún no se limpiaron).
 */
@Component
public class UserDeletedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(UserDeletedHandler.class);

    public static final String USER_DELETED = "UserDeleted";

    private final BookingRepository bookingRepository;
    private final Clock clock;

    public UserDeletedHandler(BookingRepository bookingRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(USER_DELETED);
    }

    @Override
    public void handle(EventEnvelope event) {
        String userId = event.payload().path("userId").asText(null);
        if (userId == null) {
            log.warn("UserDeleted {} sin userId: se ignora", event.eventId());
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            log.warn("UserDeleted {} con un userId inválido: se ignora", event.eventId());
            return;
        }
        int reservas = bookingRepository.borrarDatosDeContacto(id, clock.instant());
        int pasajeros = bookingRepository.anonimizarPasajerosSinViaje(id);
        log.info("Usuario eliminado: datos de contacto borrados en {} reservas y {} pasajeros anonimizados",
                reservas, pasajeros);
    }
}
