package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * US-147: cupón de descuento. company_id null = de TripBus (sirve en cualquier empresa). {@code usos} solo lo cambian
 * las consultas atómicas de CouponRepository; por eso se escriben solo las columnas que cambian (@DynamicUpdate): editar
 * un cupón nunca pisa su contador de usos.
 */
@Entity
@Table(name = "coupons")
@DynamicUpdate
public class Coupon {

    public enum Tipo { PORCENTAJE, VALOR }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String codigo;

    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false)
    private BigDecimal valor;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "vigente_desde", nullable = false)
    private Instant vigenteDesde;

    @Column(name = "vigente_hasta", nullable = false)
    private Instant vigenteHasta;

    @Column(name = "usos_maximos")
    private Integer usosMaximos;

    @Column(nullable = false, insertable = false, updatable = false)
    private int usos;

    @Column(name = "usos_por_usuario")
    private Integer usosPorUsuario;

    @Column(name = "compra_minima")
    private BigDecimal compraMinima;

    /** Ids de ruta separados por comas; null = cualquier ruta. */
    @Column(name = "route_ids")
    private String routeIds;

    @Column(nullable = false)
    private boolean activo;

    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected Coupon() {
    }

    /** Datos editables de un cupón (crear y actualizar). */
    public record Datos(String codigo, Tipo tipo, BigDecimal valor, UUID companyId, Instant vigenteDesde,
                        Instant vigenteHasta, Integer usosMaximos, Integer usosPorUsuario, BigDecimal compraMinima,
                        List<UUID> routeIds, boolean activo) {
    }

    public Coupon(Datos datos, UUID creadoPor, Instant now) {
        this.id = UUID.randomUUID();
        this.creadoPor = creadoPor;
        this.creadoEn = now;
        aplicar(datos, now);
    }

    public void actualizar(Datos d, Instant now) {
        aplicar(d, now);
    }

    /** Privado para poder usarlo en el constructor (un método de entidad JPA no puede ser final). */
    private void aplicar(Datos d, Instant now) {
        this.codigo = d.codigo().trim();
        this.tipo = d.tipo().name();
        this.valor = d.valor();
        this.companyId = d.companyId();
        this.vigenteDesde = d.vigenteDesde();
        this.vigenteHasta = d.vigenteHasta();
        this.usosMaximos = d.usosMaximos();
        this.usosPorUsuario = d.usosPorUsuario();
        this.compraMinima = d.compraMinima();
        this.routeIds = d.routeIds() == null || d.routeIds().isEmpty() ? null
                : String.join(",", d.routeIds().stream().map(UUID::toString).toList());
        this.activo = d.activo();
        this.actualizadoEn = now;
    }

    public void desactivar(Instant now) {
        this.activo = false;
        this.actualizadoEn = now;
    }

    /** Descuento sobre el subtotal: porcentaje (al peso más cercano) o valor fijo; nunca más que el subtotal. */
    public BigDecimal descuentoPara(BigDecimal subtotal) {
        BigDecimal descuento = getTipo() == Tipo.PORCENTAJE
                ? subtotal.multiply(valor).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                : valor;
        return descuento.min(subtotal);
    }

    public boolean vigenteEn(Instant momento) {
        return !momento.isBefore(vigenteDesde) && momento.isBefore(vigenteHasta);
    }

    /** Sin rutas = cualquier ruta; con rutas, el viaje debe ser de una de ellas. */
    public boolean aplicaARuta(UUID routeId) {
        return getRouteIds().isEmpty() || (routeId != null && getRouteIds().contains(routeId));
    }

    public List<UUID> getRouteIds() {
        return routeIds == null || routeIds.isBlank() ? List.of()
                : Arrays.stream(routeIds.split(",")).map(String::trim).map(UUID::fromString).toList();
    }

    public UUID getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public Tipo getTipo() {
        return Tipo.valueOf(tipo);
    }

    public BigDecimal getValor() {
        return valor;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public Instant getVigenteDesde() {
        return vigenteDesde;
    }

    public Instant getVigenteHasta() {
        return vigenteHasta;
    }

    public Integer getUsosMaximos() {
        return usosMaximos;
    }

    public int getUsos() {
        return usos;
    }

    public Integer getUsosPorUsuario() {
        return usosPorUsuario;
    }

    public BigDecimal getCompraMinima() {
        return compraMinima;
    }

    public boolean isActivo() {
        return activo;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
