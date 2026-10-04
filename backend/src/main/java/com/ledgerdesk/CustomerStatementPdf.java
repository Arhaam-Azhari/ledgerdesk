package com.ledgerdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
public class CustomerStatementPdf {
    public byte[] render(CustomerStatementService.Statement statement) throws IOException {
        try (PDDocument document = new PDDocument();
             var fontStream = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if (fontStream == null) throw new IOException("Statement font is missing.");
            PDType0Font font = PDType0Font.load(document, fontStream);
            document.getDocumentInformation().setTitle("Customer statement - " + statement.customer().name());
            String period = statement.startsOn() + " to " + statement.endsOn();
            try (PdfLayout layout = new PdfLayout(document, font, "Customer statement  /  " + period)) {
                layout.text("Ledgerdesk", 22);
                layout.text("CUSTOMER ACCOUNT STATEMENT", 12);
                layout.gap(14);
                layout.text(statement.customer().name(), 13);
                layout.text(statement.customer().email(), 11);
                layout.text("Period: " + period + " (inclusive)", 11);
                layout.gap(14);
                layout.text("Opening amount owed: " + money(statement.openingBalance()), 11);
                layout.text("Invoice charges: " + money(statement.charges()), 11);
                layout.text("Payments received: " + money(statement.payments()), 11);
                layout.text("Invoice reversals: " + money(statement.reversals()), 11);
                layout.text("Closing amount owed: " + money(statement.closingBalance()), 14);
                layout.gap(14);
                layout.text("STATEMENT ACTIVITY", 10);
                for (var movement : statement.movements()) {
                    // Keep the date and amounts together when a new activity block starts.
                    layout.keepTogether(52);
                    String kind = switch (movement.kind()) {
                        case "INVOICE" -> "Invoice";
                        case "PAYMENT" -> "Payment";
                        case "INVOICE_REVERSAL" -> "Invoice reversal";
                        default -> throw new IllegalArgumentException("Unknown statement movement kind.");
                    };
                    layout.text(movement.postedOn() + "  |  " + kind + "  |  " + movement.reference(), 10);
                    layout.text("Charge " + money(movement.charge()) + "  /  Reduction " + money(movement.reduction())
                            + "  /  Balance " + money(movement.balance()), 9);
                    layout.text(movement.description(), 10);
                    layout.gap(7);
                }
                if (statement.movements().isEmpty())
                    layout.text("No activity in this period. Any earlier balance is carried forward.", 11);
                layout.gap(14);
                layout.text("Opening + charges - payments - reversals = closing.", 9);
                layout.text("Postings use accounting dates. Same-day invoices appear before payments and reversals; this is not a posting-time order.", 9);
                layout.text("Contact details are current. Figures reflect the books at download time for the selected dates.", 9);
                layout.text("Local accounting copy. No payment instructions or customer delivery are configured. Ledger references are available in the screen and CSV.", 9);
                if (layout.substituted())
                    layout.text("Characters outside this font appear as Unicode codes in square brackets.", 9);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String money(BigDecimal value) { return "USD " + value.setScale(2).toPlainString(); }
}
