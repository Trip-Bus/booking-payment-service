package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.domain.ManifestAccessLog;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.ManifestAccessLogRepository;
import com.tripbus.booking.repository.ManifestRow;
import com.tripbus.booking.repository.TicketRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US-172: manifiesto de pasajeros de un viaje para el ADMIN y para el OPERADOR y el CONDUCTOR de la empresa del viaje
 * (claim companyId; la validación contra la asignación de fleet llega después). Otra empresa responde 404 como si el
 * viaje no existiera. Solo tiquetes EMITIDO (pendiente de abordar) y USADO (abordó). Es información personal: cada
 * consulta queda en manifest_access_log, también las denegadas.
 */
@Service
public class ManifestService {

    static final String EMITIDO = TicketService.EMITIDO;
    static final String USADO = TicketService.USADO;
    private static final Pattern NUMERO = Pattern.compile("^(\\d+)(.*)$");

    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final ManifestAccessLogRepository accessLog;
    private final CatalogClient catalog;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ManifestService(TicketRepository ticketRepository, BookingRepository bookingRepository,
                           ManifestAccessLogRepository accessLog, CatalogClient catalog, TransactionTemplate tx,
                           Clock clock) {
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.accessLog = accessLog;
        this.catalog = catalog;
        this.tx = tx;
        this.clock = clock;
    }

    public record Totales(int vendidos, int abordados, int pendientes) {
    }

    public record Manifest(UUID itineraryId, String origen, String destino, Instant fechaSalida, String empresa,
                           List<ManifestRow> pasajeros, Totales totales) {
    }

    public Manifest manifest(UUID itineraryId, Authentication authentication, String formato) {
        UUID userId = CurrentUser.id(authentication);
        String rol = rol(authentication);
        CatalogClient.ItinerarySummary viaje = catalog.itinerary(itineraryId);
        List<UUID> empresasDeReservas = bookingRepository.findCompanyIdsByItineraryId(itineraryId);
        UUID empresa = empresa(viaje, empresasDeReservas);
        boolean existe = viaje != null || !empresasDeReservas.isEmpty();
        boolean permitido = "ADMIN".equals(rol) && existe
                || ("OPERADOR".equals(rol) || "CONDUCTOR".equals(rol))
                && empresa != null && empresa.equals(CurrentUser.companyId(authentication));
        tx.executeWithoutResult(s -> accessLog.save(new ManifestAccessLog(itineraryId, userId, rol, formato, permitido,
                clock.instant())));
        if (!permitido) {
            throw ApiException.notFound("ITINERARY_NOT_FOUND", "El viaje no existe");
        }
        List<ManifestRow> pasajeros = ticketRepository.findManifest(itineraryId).stream()
                .sorted(Comparator.comparing(ManifestRow::asiento, ManifestService::compararAsientos))
                .toList();
        int abordados = (int) pasajeros.stream().filter(p -> USADO.equals(p.estadoTiquete())).count();
        return new Manifest(itineraryId, viaje != null ? viaje.origen() : null, viaje != null ? viaje.destino() : null,
                viaje != null ? viaje.fechaSalida() : null, viaje != null ? viaje.empresa() : null, pasajeros,
                new Totales(pasajeros.size(), abordados, pasajeros.size() - abordados));
    }

    /** Empresa del viaje según catalog; si no responde, la que guardaron sus reservas (si es una sola). */
    private static UUID empresa(CatalogClient.ItinerarySummary viaje, List<UUID> empresasDeReservas) {
        if (viaje != null && viaje.companyId() != null) {
            return viaje.companyId();
        }
        return empresasDeReservas.size() == 1 ? empresasDeReservas.get(0) : null;
    }

    private static String rol(Authentication authentication) {
        for (String rol : List.of("ADMIN", "OPERADOR", "CONDUCTOR")) {
            if (CurrentUser.hasRole(authentication, rol)) {
                return rol;
            }
        }
        return CurrentUser.role(authentication);
    }

    /** Orden natural: "2" antes que "10"; los que no empiezan por número van al final en orden alfabético. */
    static int compararAsientos(String a, String b) {
        Matcher ma = NUMERO.matcher(a == null ? "" : a);
        Matcher mb = NUMERO.matcher(b == null ? "" : b);
        boolean na = ma.matches();
        boolean nb = mb.matches();
        if (na && nb) {
            int porNumero = Long.compare(Long.parseLong(ma.group(1)), Long.parseLong(mb.group(1)));
            return porNumero != 0 ? porNumero : ma.group(2).compareTo(mb.group(2));
        }
        if (na != nb) {
            return na ? -1 : 1;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** El mismo contenido en CSV (UTF-8, separado por comas, con los totales al final). */
    public static String toCsv(Manifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("nombre,tipoDocumento,numeroDocumento,asiento,bookingId,estadoTiquete,abordadoEn\n");
        for (ManifestRow p : manifest.pasajeros()) {
            sb.append(celda(p.nombre())).append(',')
                    .append(celda(p.tipoDocumento())).append(',')
                    .append(celda(p.numeroDocumento())).append(',')
                    .append(celda(p.asiento())).append(',')
                    .append(p.bookingId()).append(',')
                    .append(celda(p.estadoTiquete())).append(',')
                    .append(p.abordadoEn() != null ? p.abordadoEn().toString() : "").append('\n');
        }
        Totales t = manifest.totales();
        sb.append('\n')
                .append("vendidos,").append(t.vendidos()).append('\n')
                .append("abordados,").append(t.abordados()).append('\n')
                .append("pendientes,").append(t.pendientes()).append('\n');
        return sb.toString();
    }

    /**
     * Celda CSV: entre comillas si tiene coma, comillas o salto de línea. Un valor que empieza por = + - @ se
     * antepone con ' para que una hoja de cálculo no lo ejecute como fórmula (inyección CSV).
     */
    static String celda(String valor) {
        if (valor == null) {
            return "";
        }
        String v = valor;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
