package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-152: corrección del nombre o el documento de un pasajero (una por pasajero, lo exige la BD). */
@Entity
@Table(name = "passenger_changes")
public class PassengerChange {

    @Id
    private UUID id;

    @Column(name = "passenger_id", nullable = false, unique = true)
    private UUID passengerId;

    @Column(name = "nombre_anterior", nullable = false)
    private String nombreAnterior;

    @Column(name = "tipo_documento_anterior", nullable = false)
    private String tipoDocumentoAnterior;

    @Column(name = "numero_documento_anterior", nullable = false)
    private String numeroDocumentoAnterior;

    @Column(name = "nombre_nuevo", nullable = false)
    private String nombreNuevo;

    @Column(name = "tipo_documento_nuevo", nullable = false)
    private String tipoDocumentoNuevo;

    @Column(name = "numero_documento_nuevo", nullable = false)
    private String numeroDocumentoNuevo;

    /** Usuario (el dueño de la reserva) que hizo la corrección. */
    @Column(name = "hecho_por", nullable = false)
    private UUID hechoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected PassengerChange() {
    }

    /** Toma los valores anteriores del pasajero ANTES de corregirlo. */
    public PassengerChange(Passenger anterior, String nombreNuevo, String tipoDocumentoNuevo,
                           String numeroDocumentoNuevo, UUID hechoPor, Instant now) {
        this.id = UUID.randomUUID();
        this.passengerId = anterior.getId();
        this.nombreAnterior = anterior.getNombre();
        this.tipoDocumentoAnterior = anterior.getTipoDocumento();
        this.numeroDocumentoAnterior = anterior.getNumeroDocumento();
        this.nombreNuevo = nombreNuevo;
        this.tipoDocumentoNuevo = tipoDocumentoNuevo;
        this.numeroDocumentoNuevo = numeroDocumentoNuevo;
        this.hechoPor = hechoPor;
        this.creadoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPassengerId() {
        return passengerId;
    }

    public String getNombreAnterior() {
        return nombreAnterior;
    }

    public String getNumeroDocumentoAnterior() {
        return numeroDocumentoAnterior;
    }

    public String getNombreNuevo() {
        return nombreNuevo;
    }

    public UUID getHechoPor() {
        return hechoPor;
    }
}
