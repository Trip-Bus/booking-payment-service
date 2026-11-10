package com.tripbus.booking.web;

import com.tripbus.booking.service.ManifestService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** US-172: manifiesto de pasajeros de un viaje (JSON o ?format=csv). */
@RestController
@RequestMapping("/itineraries")
public class ManifestController {

    private static final String CSV = "csv";

    private final ManifestService manifestService;

    public ManifestController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    @GetMapping("/{id}/manifest")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR', 'CONDUCTOR')")
    public ResponseEntity<Object> manifest(@PathVariable UUID id,
                                           @RequestParam(value = "format", required = false) String format,
                                           Authentication authentication) {
        boolean csv = CSV.equalsIgnoreCase(format);
        ManifestService.Manifest manifest = manifestService.manifest(id, authentication, csv ? CSV : "json");
        if (!csv) {
            return ResponseEntity.ok(manifest);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", CSV, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"manifiesto-" + id + ".csv\"")
                .body(ManifestService.toCsv(manifest));
    }
}
