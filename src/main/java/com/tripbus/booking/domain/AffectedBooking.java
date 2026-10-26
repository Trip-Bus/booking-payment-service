package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-154/US-155: reserva afectada por un cambio de la empresa (viaje cancelado, reprogramado o retrasado). Queda
 * PENDIENTE hasta que el pasajero elige o vence el plazo; entonces decide el sistema. Es la única vía de reembolso:
 * solo se reembolsa cuando el pasajero no tiene la culpa.
 */
@Entity
@Table(name = "affected_bookings")
public class AffectedBooking {

    public enum Causa { CANCELACION, REPROGRAMACION, RETRASO }

    public enum Estado { PENDIENTE, REPROGRAMADA, REEMBOLSADA, REUBICADA, ACEPTO_CAMBIO }

    public enum DecididoPor { SISTEMA, PASAJERO }

    /** Lo que puede elegir el pasajero. */
    public enum Opcion { ACEPTAR_CAMBIO, REPROGRAMAR, REEMBOLSO }

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Causa causa;

    @Column(name = "plazo_decision", nullable = false)
    private Instant plazoDecision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Estado estado;

    @Column(name = "decidido_en")
    private Instant decididoEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "decidido_por")
    private DecididoPor decididoPor;

    @Column(name = "booking_nuevo_id")
    private UUID bookingNuevoId;

    @Column(name = "anulacion_resuelta_en")
    private Instant anulacionResueltaEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    private long version;

    protected AffectedBooking() {
    }

    public AffectedBooking(UUID bookingId, UUID itineraryId, Causa causa, Instant plazoDecision, Instant now) {
        this.bookingId = bookingId;
        this.itineraryId = itineraryId;
        this.creadoEn = now;
        pendienteCon(causa, plazoDecision, now);
    }

    /**
     * La empresa vuelve a cambiar un viaje cuya reserva sigue confirmada (p. ej. lo cancela después de que el
     * pasajero aceptó un retraso): vuelve a quedar PENDIENTE con la causa y el plazo nuevos.
     */
    public void reabrir(Causa nuevaCausa, Instant nuevoPlazo, Instant now) {
        pendienteCon(nuevaCausa, nuevoPlazo, now);
    }

    /** Privado (no sobrescribible) para poder usarlo desde el constructor. */
    private void pendienteCon(Causa nuevaCausa, Instant nuevoPlazo, Instant now) {
        this.causa = nuevaCausa;
        this.plazoDecision = nuevoPlazo;
        this.estado = Estado.PENDIENTE;
        this.decididoEn = null;
        this.decididoPor = null;
        this.bookingNuevoId = null;
        this.actualizadoEn = now;
    }

    /** Opciones según la causa: con el viaje cancelado no hay un horario nuevo que aceptar. */
    public List<Opcion> opciones() {
        if (causa == Causa.CANCELACION) {
            return List.of(Opcion.REPROGRAMAR, Opcion.REEMBOLSO);
        }
        return List.of(Opcion.ACEPTAR_CAMBIO, Opcion.REPROGRAMAR, Opcion.REEMBOLSO);
    }

    public boolean pendiente() {
        return estado == Estado.PENDIENTE;
    }

    public boolean plazoVencido(Instant now) {
        return !plazoDecision.isAfter(now);
    }

    /** Registra la decisión; solo una vez (lanza IllegalStateException si ya estaba decidida). */
    public void decidir(Estado resultado, DecididoPor quien, UUID nuevoBookingId, Instant now) {
        if (!pendiente()) {
            throw new IllegalStateException("La reserva afectada " + bookingId + " ya se decidió: " + estado);
        }
        if (resultado == Estado.PENDIENTE) {
            throw new IllegalArgumentException("La decisión no puede ser PENDIENTE");
        }
        this.estado = resultado;
        this.decididoPor = quien;
        this.decididoEn = now;
        this.bookingNuevoId = nuevoBookingId;
        this.actualizadoEn = now;
    }

    public void marcarAnulacionResuelta(Instant now) {
        this.anulacionResueltaEn = now;
        this.actualizadoEn = now;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public Causa getCausa() {
        return causa;
    }

    public Instant getPlazoDecision() {
        return plazoDecision;
    }

    public Estado getEstado() {
        return estado;
    }

    public Instant getDecididoEn() {
        return decididoEn;
    }

    public DecididoPor getDecididoPor() {
        return decididoPor;
    }

    public UUID getBookingNuevoId() {
        return bookingNuevoId;
    }

    public Instant getAnulacionResueltaEn() {
        return anulacionResueltaEn;
    }
}
