package com.tripbus.booking.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tripbus.booking.web.dto.PassengerResponse;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** US-145: tiquete en PDF (A5) con el QR, los datos del viaje, los pasajeros y los asientos. Usa OpenPDF. */
final class TicketPdf {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.of("es", "CO"));
    private static final Color AZUL = new Color(0x1F, 0x4E, 0x79);
    private static final String SIN_DATO = "-";

    private TicketPdf() {
    }

    /** Lo que va en el PDF. Los datos del viaje pueden ser null (catalog no respondió y la reserva no los tiene). */
    record Datos(UUID ticketId, UUID bookingId, String estado, String origen, String destino, Instant fechaSalida,
                 String empresa, String placa, List<String> asientos, List<PassengerResponse> pasajeros,
                 BigDecimal total, String moneda, String qrCode) {
    }

    static byte[] render(Datos d) {
        Document document = new Document(PageSize.A5, 32, 32, 32, 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.addTitle("Tiquete TripBus " + d.ticketId());
        document.addCreator("TripBus");
        document.open();
        Font titulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, AZUL);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font negrita = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);

        document.add(new Paragraph("TripBus - Tiquete de viaje", titulo));
        document.add(new Paragraph("Tiquete N. " + d.ticketId(), negrita));
        document.add(new Paragraph("Reserva " + d.bookingId() + " - Estado: " + d.estado(), normal));

        PdfPTable viaje = new PdfPTable(new float[] {1, 2.4f});
        viaje.setWidthPercentage(100);
        viaje.setSpacingBefore(10);
        fila(viaje, "Origen", d.origen(), negrita, normal);
        fila(viaje, "Destino", d.destino(), negrita, normal);
        fila(viaje, "Salida", d.fechaSalida() == null ? null
                : FECHA.format(d.fechaSalida().atZone(TicketMailService.ZONA)) + " (hora de Colombia)", negrita, normal);
        fila(viaje, "Empresa", d.empresa(), negrita, normal);
        fila(viaje, "Placa", d.placa(), negrita, normal);
        fila(viaje, "Asientos", String.join(", ", d.asientos()), negrita, normal);
        fila(viaje, "Total", d.total().toPlainString() + " " + d.moneda(), negrita, normal);
        document.add(viaje);

        PdfPTable pasajeros = new PdfPTable(new float[] {2.2f, 1.6f, 0.8f});
        pasajeros.setWidthPercentage(100);
        pasajeros.setSpacingBefore(10);
        pasajeros.addCell(celda("Pasajero", negrita));
        pasajeros.addCell(celda("Documento", negrita));
        pasajeros.addCell(celda("Asiento", negrita));
        for (PassengerResponse p : d.pasajeros()) {
            pasajeros.addCell(celda(p.nombre(), normal));
            pasajeros.addCell(celda(p.tipoDocumento() + " " + p.numeroDocumento(), normal));
            pasajeros.addCell(celda(p.asiento(), normal));
        }
        document.add(pasajeros);

        document.add(qr(d.qrCode()));
        Paragraph aviso = new Paragraph("Presenta este código QR al abordar. Es válido para un solo uso.", normal);
        aviso.setAlignment(Element.ALIGN_CENTER);
        document.add(aviso);
        document.close();
        return out.toByteArray();
    }

    private static Image qr(String qrCode) {
        try {
            Image qr = Image.getInstance(TicketService.qrPng(qrCode));
            qr.scaleToFit(170, 170);
            qr.setAlignment(Element.ALIGN_CENTER);
            return qr;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo agregar el QR al PDF del tiquete", e);
        }
    }

    private static void fila(PdfPTable table, String etiqueta, String valor, Font fEtiqueta, Font fValor) {
        table.addCell(celda(etiqueta, fEtiqueta));
        table.addCell(celda(valor, fValor));
    }

    private static PdfPCell celda(String texto, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(texto == null || texto.isBlank() ? SIN_DATO : texto, font));
        cell.setPadding(4);
        cell.setBorderColor(Color.LIGHT_GRAY);
        return cell;
    }
}
