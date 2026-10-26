package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.ManifestAccessLogRepository;
import com.tripbus.booking.repository.ManifestRow;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** US-172: acceso al manifiesto por rol y empresa, orden de los asientos y CSV. */
class ManifestServiceTest {

    private static final UUID EMPRESA = UUID.randomUUID();

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UUID viaje = UUID.randomUUID();
    private ManifestService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        service = new ManifestService(tickets, bookings, mock(ManifestAccessLogRepository.class), catalog, tx,
                Clock.systemUTC());
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        when(tickets.findManifest(viaje)).thenReturn(List.of(
                fila("B", "10", "USADO"), fila("A", "2", "EMITIDO"), fila("C", "1A", "EMITIDO")));
    }

    private static ManifestRow fila(String nombre, String asiento, String estado) {
        return new ManifestRow(nombre, "CC", "123", asiento, UUID.randomUUID(), estado,
                "USADO".equals(estado) ? Instant.now() : null);
    }

    private static JwtAuthenticationToken token(String rol, UUID empresa) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString())
                .claim("role", rol);
        if (empresa != null) {
            jwt.claim("companyId", empresa.toString());
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_" + rol)));
    }

    @Test
    void sinCatalogSeUsaLaEmpresaDeLasReservas() {
        when(bookings.findCompanyIdsByItineraryId(viaje)).thenReturn(List.of(EMPRESA));

        ManifestService.Manifest m = service.manifest(viaje, token("CONDUCTOR", EMPRESA), "json");

        assertThat(m.origen()).isNull();
        assertThat(m.pasajeros()).extracting(ManifestRow::asiento).containsExactly("1A", "2", "10");
        assertThat(m.totales()).isEqualTo(new ManifestService.Totales(3, 1, 2));
    }

    @Test
    void sinEmpresaConocidaSoloElAdminYSiElViajeExiste() {
        when(bookings.findCompanyIdsByItineraryId(viaje)).thenReturn(List.of(EMPRESA, UUID.randomUUID()));
        assertThatThrownBy(() -> service.manifest(viaje, token("OPERADOR", EMPRESA), "json"))
                .isInstanceOf(ApiException.class);
        assertThat(service.manifest(viaje, token("ADMIN", null), "json").pasajeros()).hasSize(3);

        when(bookings.findCompanyIdsByItineraryId(viaje)).thenReturn(List.of());
        assertThatThrownBy(() -> service.manifest(viaje, token("ADMIN", null), "json"))
                .isInstanceOf(ApiException.class);

        when(catalog.itinerary(viaje)).thenReturn(new CatalogClient.ItinerarySummary(viaje, "Bogotá", "Cali",
                Instant.now(), null, "Expreso", "ABC"));
        assertThat(service.manifest(viaje, token("ADMIN", null), "csv").origen()).isEqualTo("Bogotá");
        assertThatThrownBy(() -> service.manifest(viaje, token("PASAJERO", EMPRESA), "json"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ordenNaturalDeAsientos() {
        assertThat(ManifestService.compararAsientos("2", "10")).isNegative();
        assertThat(ManifestService.compararAsientos("10A", "10B")).isNegative();
        assertThat(ManifestService.compararAsientos("3", "VIP")).isNegative();
        assertThat(ManifestService.compararAsientos("VIP", "3")).isPositive();
        assertThat(ManifestService.compararAsientos("A", "B")).isNegative();
        assertThat(ManifestService.compararAsientos(null, "1")).isPositive();
        assertThat(ManifestService.compararAsientos(null, null)).isZero();
    }

    @Test
    void elCsvEscapaComasComillasYFormulas() {
        assertThat(ManifestService.celda(null)).isEmpty();
        assertThat(ManifestService.celda("Ana")).isEqualTo("Ana");
        assertThat(ManifestService.celda("Pérez, Ana")).isEqualTo("\"Pérez, Ana\"");
        assertThat(ManifestService.celda("Ana \"la\" Pérez")).isEqualTo("\"Ana \"\"la\"\" Pérez\"");
        assertThat(ManifestService.celda("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ManifestService.celda("+57")).isEqualTo("'+57");
        assertThat(ManifestService.celda("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(ManifestService.celda("a\rb")).isEqualTo("\"a\rb\"");
        assertThat(ManifestService.celda("")).isEmpty();

        when(bookings.findCompanyIdsByItineraryId(viaje)).thenReturn(List.of(EMPRESA));
        String csv = ManifestService.toCsv(service.manifest(viaje, token("OPERADOR", EMPRESA), "csv"));
        assertThat(csv.lines().toList()).hasSize(8).first().asString().startsWith("nombre,");
        assertThat(csv).contains("vendidos,3").contains("abordados,1").contains("pendientes,2");
    }
}
