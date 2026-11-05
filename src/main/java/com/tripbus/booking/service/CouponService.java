package com.tripbus.booking.service;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Coupon;
import com.tripbus.booking.domain.CouponRedemption;
import com.tripbus.booking.repository.CouponRedemptionRepository;
import com.tripbus.booking.repository.CouponRepository;
import com.tripbus.booking.web.dto.CouponRequest;
import com.tripbus.booking.web.dto.CouponResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * US-147: cupones de descuento. Al crear la reserva se valida vigencia, empresa del viaje, ruta, compra mínima y usos;
 * el descuento se resta antes del pago y queda en la reserva. No se combina con una tarifa de oferta (409
 * COUPON_NOT_COMBINABLE). Los usos se cuentan de forma atómica y se liberan si la reserva vence o el pago se rechaza.
 *
 * <p>CRUD: el ADMIN administra los cupones de TripBus (company_id null) y los de cualquier empresa; el OPERADOR solo los
 * de su empresa (los demás responden 404, como si no existieran).
 */
@Service
public class CouponService {

    static final String NOT_FOUND = "COUPON_NOT_FOUND";

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final Clock clock;

    public CouponService(CouponRepository coupons, CouponRedemptionRepository redemptions, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    /** Dónde se quiere usar el cupón: empresa y ruta del viaje, subtotal de la compra y si es de oferta. */
    public record Uso(UUID companyId, UUID routeId, BigDecimal subtotal, boolean deOferta, UUID userId) {
    }

    /** Descuento que da un cupón válido para una compra. */
    public record Descuento(UUID couponId, String codigo, BigDecimal valor) {
    }

    // ------------------------------------------------------------------ uso en una compra

    /** Valida el cupón para esta compra (sin gastar un uso) y calcula su descuento. */
    @Transactional(readOnly = true)
    public Descuento validar(String codigo, Uso uso) {
        Coupon cupon = coupons.findByCodigo(codigo.trim()).filter(Coupon::isActivo)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND, "El cupón no existe"));
        if (uso.deOferta()) {
            throw ApiException.conflict("COUPON_NOT_COMBINABLE", "El cupón no se puede usar con una tarifa de oferta");
        }
        Instant ahora = clock.instant();
        if (ahora.isBefore(cupon.getVigenteDesde())) {
            throw ApiException.conflict("COUPON_NOT_YET_VALID", "El cupón todavía no está vigente");
        }
        if (!cupon.vigenteEn(ahora)) {
            throw ApiException.conflict("COUPON_EXPIRED", "El cupón ya venció");
        }
        if (cupon.getCompanyId() != null && !cupon.getCompanyId().equals(uso.companyId())) {
            throw ApiException.conflict("COUPON_NOT_APPLICABLE", "El cupón no aplica a la empresa de este viaje");
        }
        if (!cupon.aplicaARuta(uso.routeId())) {
            throw ApiException.conflict("COUPON_NOT_APPLICABLE", "El cupón no aplica a la ruta de este viaje");
        }
        if (cupon.getCompraMinima() != null && uso.subtotal().compareTo(cupon.getCompraMinima()) < 0) {
            throw ApiException.conflict("COUPON_MINIMUM_NOT_MET",
                    "La compra mínima para este cupón es " + cupon.getCompraMinima().toPlainString());
        }
        if (cupon.getUsosMaximos() != null && coupons.usos(cupon.getId()) >= cupon.getUsosMaximos()) {
            throw agotado();
        }
        validarUsosDelUsuario(cupon, uso.userId());
        return new Descuento(cupon.getId(), cupon.getCodigo(), cupon.descuentoPara(uso.subtotal()));
    }

    /**
     * Gasta un uso del cupón para la reserva, en la transacción que la crea: si ya no quedan usos (otra compra se llevó
     * el último) lanza 409 y la reserva no se crea. El límite por usuario se revisa con la fila del cupón ya bloqueada.
     */
    public void redimir(Descuento descuento, UUID bookingId, UUID userId) {
        Instant ahora = clock.instant();
        if (coupons.sumarUso(descuento.couponId(), ahora) == 0) {
            throw agotado();
        }
        Coupon cupon = coupons.findById(descuento.couponId()).orElseThrow();
        validarUsosDelUsuario(cupon, userId);
        redemptions.save(new CouponRedemption(cupon.getId(), bookingId, userId, descuento.valor(), ahora));
    }

    /** Devuelve el uso del cupón de la reserva (venció o el pago se rechazó). Idempotente. */
    public void liberar(UUID bookingId) {
        redemptions.findByBookingIdAndEstado(bookingId, CouponRedemption.ACTIVO).ifPresent(r -> {
            Instant ahora = clock.instant();
            if (redemptions.liberar(bookingId, ahora) == 1) {
                coupons.restarUso(r.getCouponId(), ahora);
            }
        });
    }

    private void validarUsosDelUsuario(Coupon cupon, UUID userId) {
        if (cupon.getUsosPorUsuario() != null && redemptions.countByCouponIdAndUserIdAndEstado(cupon.getId(), userId,
                CouponRedemption.ACTIVO) >= cupon.getUsosPorUsuario()) {
            throw ApiException.conflict("COUPON_USER_LIMIT_REACHED", "Ya usaste este cupón el máximo de veces permitido");
        }
    }

    private static ApiException agotado() {
        return ApiException.conflict("COUPON_EXHAUSTED", "El cupón ya no tiene usos disponibles");
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public CouponResponse crear(CouponRequest request, BookingViewer viewer) {
        Coupon.Datos datos = datos(request, empresaPermitida(viewer, request.companyId()));
        if (coupons.findByCodigo(datos.codigo()).isPresent()) {
            throw codigoEnUso();
        }
        try {
            return respuesta(coupons.saveAndFlush(new Coupon(datos, viewer.userId(), clock.instant())));
        } catch (DataIntegrityViolationException e) {
            throw codigoEnUso();
        }
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> listar(BookingViewer viewer, UUID companyId) {
        List<Coupon> lista;
        if (viewer.admin()) {
            lista = companyId == null ? coupons.findAllByOrderByCreadoEnDesc()
                    : coupons.findByCompanyIdOrderByCreadoEnDesc(companyId);
        } else {
            lista = coupons.findByCompanyIdOrderByCreadoEnDesc(empresaDelOperador(viewer));
        }
        return lista.stream().map(this::respuesta).toList();
    }

    @Transactional(readOnly = true)
    public CouponResponse obtener(UUID id, BookingViewer viewer) {
        return respuesta(visible(id, viewer));
    }

    @Transactional
    public CouponResponse actualizar(UUID id, CouponRequest request, BookingViewer viewer) {
        Coupon cupon = visible(id, viewer);
        Coupon.Datos datos = datos(request, empresaPermitida(viewer, request.companyId()));
        coupons.findByCodigo(datos.codigo()).filter(otro -> !otro.getId().equals(id)).ifPresent(otro -> {
            throw codigoEnUso();
        });
        if (datos.usosMaximos() != null && datos.usosMaximos() < coupons.usos(id)) {
            throw ApiException.conflict("COUPON_USES_BELOW_CURRENT",
                    "usosMaximos no puede ser menor que los usos que ya tiene el cupón");
        }
        cupon.actualizar(datos, clock.instant());
        return respuesta(coupons.saveAndFlush(cupon));
    }

    /** Desactiva el cupón (se conserva por los usos que ya tuvo). */
    @Transactional
    public void desactivar(UUID id, BookingViewer viewer) {
        Coupon cupon = visible(id, viewer);
        cupon.desactivar(clock.instant());
        coupons.save(cupon);
    }

    private Coupon visible(UUID id, BookingViewer viewer) {
        Coupon cupon = coupons.findById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND, "El cupón no existe"));
        if (!viewer.admin() && !Objects.equals(cupon.getCompanyId(), empresaDelOperador(viewer))) {
            throw ApiException.notFound(NOT_FOUND, "El cupón no existe");
        }
        return cupon;
    }

    /** ADMIN: la empresa que pida (null = TripBus). OPERADOR: solo la suya. */
    private static UUID empresaPermitida(BookingViewer viewer, UUID companyId) {
        if (viewer.admin()) {
            return companyId;
        }
        UUID propia = empresaDelOperador(viewer);
        if (companyId != null && !companyId.equals(propia)) {
            throw ApiException.forbidden("FORBIDDEN_COMPANY", "Solo puede administrar los cupones de su empresa");
        }
        return propia;
    }

    private static UUID empresaDelOperador(BookingViewer viewer) {
        if (!viewer.operador() || viewer.companyId() == null) {
            throw ApiException.forbidden("FORBIDDEN", "No tiene permisos para administrar cupones");
        }
        return viewer.companyId();
    }

    static Coupon.Datos datos(CouponRequest r, UUID companyId) {
        Coupon.Tipo tipo = Coupon.Tipo.valueOf(r.tipo());
        if (tipo == Coupon.Tipo.PORCENTAJE && r.valor().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("INVALID_COUPON", "Un cupón de porcentaje no puede superar el 100 %");
        }
        if (!r.vigenteHasta().isAfter(r.vigenteDesde())) {
            throw ApiException.badRequest("INVALID_COUPON", "vigenteHasta debe ser posterior a vigenteDesde");
        }
        return new Coupon.Datos(r.codigo().trim(), tipo, r.valor(), companyId, r.vigenteDesde(), r.vigenteHasta(),
                r.usosMaximos(), r.usosPorUsuario(), r.compraMinima(), r.routeIds(), r.activo() == null || r.activo());
    }

    private static ApiException codigoEnUso() {
        return ApiException.conflict("COUPON_CODE_TAKEN", "Ya existe un cupón con ese código");
    }

    private CouponResponse respuesta(Coupon c) {
        return new CouponResponse(c.getId(), c.getCodigo(), c.getTipo().name(), c.getValor(), c.getCompanyId(),
                c.getVigenteDesde(), c.getVigenteHasta(), c.getUsosMaximos(), coupons.usos(c.getId()),
                c.getUsosPorUsuario(), c.getCompraMinima(), c.getRouteIds(), c.isActivo());
    }
}
