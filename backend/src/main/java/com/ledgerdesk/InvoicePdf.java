package com.ledgerdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
public class InvoicePdf {
    @SuppressWarnings("unchecked")
    public byte[] render(Map<String, Object> snapshot) throws IOException {
        Map<String, Object> invoice = (Map<String, Object>) snapshot.get("invoice");
        List<Map<String, Object>> payments = (List<Map<String, Object>>) snapshot.get("payments");
        String number = LedgerService.invoiceNumber(((Number) invoice.get("number_value")).longValue());
        try (PDDocument document = new PDDocument();
             var fontStream = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if (fontStream == null) throw new IOException("Invoice font is missing.");
            PDType0Font font = PDType0Font.load(document, fontStream);
            document.getDocumentInformation().setTitle(number + " - " + snapshot.get("business"));
            try (Layout layout = new Layout(document, font, number)) {
                layout.text(snapshot.get("business").toString(), 22);
                layout.text("SERVICE INVOICE  /  " + number, 12);
                layout.gap(14);
                boolean voided = "VOID".equals(invoice.get("status"));
                layout.text(voided ? "VOID - no amount due" : "Posted invoice", 12);
                layout.text("Issued: " + invoice.get("issued_on") + "    Due: " + invoice.get("due_on"), 11);
                layout.gap(14);
                layout.text("BILL TO", 10);
                layout.text(invoice.get("customer_name").toString(), 13);
                layout.text(invoice.get("customer_email").toString(), 11);
                layout.gap(20);
                layout.text("SERVICE", 10);
                layout.text(invoice.get("description").toString(), 12);
                layout.gap(20);
                BigDecimal amount = (BigDecimal) invoice.get("amount");
                BigDecimal paid = (BigDecimal) invoice.get("paid");
                layout.text("Invoice total: USD " + amount.toPlainString(), 13);
                layout.text("Payments recorded: USD " + paid.toPlainString(), 12);
                layout.text("Amount due: USD " + (voided ? BigDecimal.ZERO.setScale(2) : amount.subtract(paid)).toPlainString(), 16);
                if (!payments.isEmpty()) {
                    layout.gap(20);
                    layout.text("PAYMENT HISTORY", 10);
                    for (var payment : payments)
                        layout.text(payment.get("paid_on") + "    USD " + payment.get("amount"), 11);
                }
                layout.gap(20);
                layout.text("Single service amount in USD. No sales tax, discount, or payment instructions are configured.", 9);
                layout.text("This copy reflects payments and invoice status at the time of download.", 9);
                layout.text("Sample business - local development document.", 9);
                if (layout.substituted)
                    layout.text("Characters outside this font appear as Unicode codes in square brackets.", 9);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static final class Layout implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final String number;
        private PDPageContentStream stream;
        private float y;
        private int page;
        private boolean substituted;
        private static final float LEFT = 50;
        private static final float WIDTH = PDRectangle.LETTER.getWidth() - 100;

        Layout(PDDocument document, PDType0Font font, String number) throws IOException {
            this.document = document;
            this.font = font;
            this.number = number;
            nextPage();
        }

        private void nextPage() throws IOException {
            if (stream != null) stream.close();
            PDPage sheet = new PDPage(PDRectangle.LETTER);
            document.addPage(sheet);
            stream = new PDPageContentStream(document, sheet);
            page++;
            draw(number + "  |  Page " + page, 9, 30);
            y = 735;
        }

        private String printable(String raw) throws IOException {
            StringBuilder result = new StringBuilder();
            for (int cp : raw.codePoints().toArray()) {
                if (Character.isISOControl(cp) || Character.isWhitespace(cp)) { result.append(' '); continue; }
                String glyph = new String(Character.toChars(cp));
                try { font.encode(glyph); result.append(glyph); }
                catch (IllegalArgumentException unsupported) {
                    result.append(String.format(java.util.Locale.ROOT, "[U+%04X]", cp));
                    substituted = true;
                }
            }
            return result.toString();
        }

        void gap(float height) { y -= height; }

        void text(String raw, float size) throws IOException {
            String text = printable(raw);
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            // Split by code point so a long email or unbroken description cannot run off the page.
            for (int cp : text.codePoints().toArray()) {
                String glyph = new String(Character.toChars(cp));
                if (line.length() > 0 && font.getStringWidth(line + glyph) / 1000 * size > WIDTH) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(glyph);
            }
            if (line.length() > 0) lines.add(line.toString());
            for (String value : lines) {
                if (y - size < 65) nextPage();
                draw(value, size, y);
                y -= size * 1.5f;
            }
        }

        private void draw(String text, float size, float atY) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(24 / 255f, 43 / 255f, 53 / 255f);
            stream.newLineAtOffset(LEFT, atY);
            stream.showText(text);
            stream.endText();
        }

        @Override public void close() throws IOException { stream.close(); }
    }
}
