package com.ledgerdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
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
            try (PdfLayout layout = new PdfLayout(document, font, number)) {
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
                if (layout.substituted())
                    layout.text("Characters outside this font appear as Unicode codes in square brackets.", 9);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

}
