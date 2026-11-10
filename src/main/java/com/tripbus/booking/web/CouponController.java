package com.tripbus.booking.web;

import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.CouponService;
import com.tripbus.booking.web.dto.CouponRequest;
import com.tripbus.booking.web.dto.CouponResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** US-147: CRUD de cupones. ADMIN: los de TripBus y los de cualquier empresa; OPERADOR: los de su empresa. */
@RestController
@RequestMapping("/coupons")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> crear(@Valid @RequestBody CouponRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(couponService.crear(request, BookingViewer.of(authentication)));
    }

    @GetMapping
    public List<CouponResponse> listar(@RequestParam(required = false) UUID companyId, Authentication authentication) {
        return couponService.listar(BookingViewer.of(authentication), companyId);
    }

    @GetMapping("/{id}")
    public CouponResponse obtener(@PathVariable UUID id, Authentication authentication) {
        return couponService.obtener(id, BookingViewer.of(authentication));
    }

    @PutMapping("/{id}")
    public CouponResponse actualizar(@PathVariable UUID id, @Valid @RequestBody CouponRequest request,
                                     Authentication authentication) {
        return couponService.actualizar(id, request, BookingViewer.of(authentication));
    }

    /** Desactiva el cupón (204): deja de poder usarse pero se conserva por los usos que ya tuvo. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(@PathVariable UUID id, Authentication authentication) {
        couponService.desactivar(id, BookingViewer.of(authentication));
        return ResponseEntity.noContent().build();
    }
}
