package com.tripbus.booking.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * US-146: comprobante de pago o de reembolso en PDF (A5). Número consecutivo, fecha, empresa y NIT, método de pago con
 * los últimos 4 dígitos, desglose por pasajero, cupón aplicado y total. No muestra la comisión de TripBus. Usa OpenPDF.
 */
final class ReceiptPdf {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.of("es", "CO"));
    private static final Color AZUL = new Color(0x1F, 0x4E, 0x79);
    private static final String SIN_DATO = "-";

    private ReceiptPdf() {
    }

    /** Un pasajero del desglose. */
    record Linea(String nombre, String tipoPasajero, String asiento, String categoria, BigDecimal precio) {
    }

    /**
     * Lo que va en el comprobante. titulo: "Comprobante de pago" o "Comprobante de reembolso". nit null = la empresa
     * no lo tiene en catalog (solo se muestra el nombre). cupon y descuento null si no hubo cupón.
     */
    record Datos(String titulo, String numero, Instant fecha, String empresa, String nit, UUID bookingId,
                 String viaje, String metodo, List<Linea> pasajeros, BigDecimal subtotal, String cupon,
                 BigDecimal descuento, String etiquetaTotal, BigDecimal total, String moneda, String motivo) {
    }

    static byte[] render(Datos d) {
        Document document = new Document(PageSize.A5, 32, 32, 32, 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.addTitle(d.titulo() + " " + d.numero());
        document.addCreator("TripBus");
        document.open();
        Font titulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, AZUL);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font negrita = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

        document.add(new Paragraph("TripBus - " + d.titulo(), titulo));
        document.add(new Paragraph("Comprobante N. " + d.numero(), negrita));

        PdfPTable datos = new PdfPTable(new float[] {1, 2.4f});
        datos.setWidthPercentage(100);
        datos.setSpacingBefore(10);
        fila(datos, "Fecha", FECHA.format(d.fecha().atZone(CommissionService.ZONA)) + " (hora de Colombia)", negrita,
                normal);
        fila(datos, "Empresa", d.empresa(), negrita, normal);
        if (d.nit() != null && !d.nit().isBlank()) {
            fila(datos, "NIT", d.nit(), negrita, normal);
        }
        fila(datos, "Reserva", d.bookingId().toString(), negrita, normal);
        fila(datos, "Viaje", d.viaje(), negrita, normal);
        fila(datos, "Método de pago", d.metodo(), negrita, normal);
        if (d.motivo() != null) {
            fila(datos, "Motivo", d.motivo(), negrita, normal);
        }
        document.add(datos);

        PdfPTable pasajeros = new PdfPTable(new float[] {2.2f, 1.2f, 0.8f, 1.2f, 1.2f});
        pasajeros.setWidthPercentage(100);
        pasajeros.setSpacingBefore(10);
        for (String encabezado : List.of("Pasajero", "Tipo", "Asiento", "Categoría", "Precio")) {
            pasajeros.addCell(celda(encabezado, negrita));
        }
        for (Linea l : d.pasajeros()) {
            pasajeros.addCell(celda(l.nombre(), normal));
            pasajeros.addCell(celda(l.tipoPasajero(), normal));
            pasajeros.addCell(celda(l.asiento(), normal));
            pasajeros.addCell(celda(l.categoria(), normal));
            pasajeros.addCell(celda(dinero(l.precio(), d.moneda()), normal));
        }
        document.add(pasajeros);

        PdfPTable totales = new PdfPTable(new float[] {2.4f, 1});
        totales.setWidthPercentage(100);
        totales.setSpacingBefore(10);
        fila(totales, "Subtotal", dinero(d.subtotal(), d.moneda()), negrita, normal);
        if (d.cupon() != null) {
            fila(totales, "Cupón " + d.cupon(), "-" + dinero(d.descuento(), d.moneda()), negrita, normal);
        }
        fila(totales, d.etiquetaTotal(), dinero(d.total(), d.moneda()), negrita, negrita);
        document.add(totales);
        document.close();
        return out.toByteArray();
    }

    static String dinero(BigDecimal valor, String moneda) {
        return valor == null ? SIN_DATO : valor.stripTrailingZeros().toPlainString() + " " + moneda;
    }

    private static void fila(PdfPTable table, String etiqueta, String valor, Font fEtiqueta, Font fValor) {
        TicketPdf.fila(table, etiqueta, valor, fEtiqueta, fValor);
    }

    private static PdfPCell celda(String texto, Font font) {
        return TicketPdf.celda(texto, font);
    }
}
