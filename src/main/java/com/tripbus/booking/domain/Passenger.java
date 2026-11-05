package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "passengers")
public class Passenger {

    public static final String TIPO_ADULTO = "ADULTO";

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

    /** US-138: ADULTO, NINO o ADULTO_MAYOR. */
    @Column(name = "tipo_pasajero", nullable = false)
    private String tipoPasajero = TIPO_ADULTO;

    /** US-141: desglose del precio que dio catalog (null en reservas anteriores al Sprint 6). */
    @Column(name = "categoria_asiento")
    private String categoriaAsiento;

    @Column(name = "precio_base")
    private BigDecimal precioBase;

    @Column(name = "descuento_tipo")
    private BigDecimal descuentoTipo;

    @Column(name = "recargo_categoria")
    private BigDecimal recargoCategoria;

    private BigDecimal precio;

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

    /** US-138/US-141: tipo de pasajero y precio que le dio catalog (categoría del asiento y recargo). */
    public void asignarTarifa(String tipoPasajero, String categoriaAsiento, BigDecimal precioBase,
                              BigDecimal descuentoTipo, BigDecimal recargoCategoria, BigDecimal precio) {
        this.tipoPasajero = tipoPasajero;
        this.categoriaAsiento = categoriaAsiento;
        this.precioBase = precioBase;
        this.descuentoTipo = descuentoTipo;
        this.recargoCategoria = recargoCategoria;
        this.precio = precio;
    }

    /** US-139: el pasajero pasa a otro asiento del mismo viaje. */
    public void cambiarAsiento(String asiento) {
        this.asiento = asiento;
    }

    /** US-152: corrección del nombre o el documento (una sola vez, ver PassengerChange). */
    public void corregir(String nombre, String tipoDocumento, String numeroDocumento) {
        this.nombre = nombre;
        this.tipoDocumento = tipoDocumento;
        this.numeroDocumento = numeroDocumento;
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

    public String getTipoPasajero() {
        return tipoPasajero;
    }

    public String getCategoriaAsiento() {
        return categoriaAsiento;
    }

    public BigDecimal getPrecioBase() {
        return precioBase;
    }

    public BigDecimal getDescuentoTipo() {
        return descuentoTipo;
    }

    public BigDecimal getRecargoCategoria() {
        return recargoCategoria;
    }

    public BigDecimal getPrecio() {
        return precio;
    }
}
