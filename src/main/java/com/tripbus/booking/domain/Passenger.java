package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "passengers")
public class Passenger {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(nullable = false)
    private String nombre;

    @Column(name = "tipo_documento", nullable = false)
    private String tipoDocumento;

    @Column(name = "numero_documento", nullable = false)
    private String numeroDocumento;

    private String asiento;

    protected Passenger() {
    }

    public Passenger(UUID bookingId, String nombre, String tipoDocumento, String numeroDocumento, String asiento) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.nombre = nombre;
        this.tipoDocumento = tipoDocumento;
        this.numeroDocumento = numeroDocumento;
        this.asiento = asiento;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getNombre() {
        return nombre;
    }

    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public String getNumeroDocumento() {
        return numeroDocumento;
    }

    public String getAsiento() {
        return asiento;
    }
}
