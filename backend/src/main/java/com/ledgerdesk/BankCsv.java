package com.ledgerdesk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BankCsv {
    public record Row(String transactionId, LocalDate date, String description, BigDecimal amount) {}
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_ROWS = 500;

    public List<Row> parse(String csv) {
        if (csv == null || csv.isBlank() || csv.length() > MAX_BYTES || csv.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Choose a nonempty CSV no larger than 256 KiB.");
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        var records = records(csv);
        if (records.isEmpty() || !records.get(0).equals(List.of("transaction_id", "date", "description", "amount")))
            throw new IllegalArgumentException("CSV headers must be transaction_id,date,description,amount in that order.");
        if (records.size() < 2) throw new IllegalArgumentException("The CSV has no transactions.");
        var ids = new HashSet<String>();
        var rows = new ArrayList<Row>();
        for (int i = 1; i < records.size(); i++) {
            var fields = records.get(i);
            try {
                if (fields.size() != 4) throw new IllegalArgumentException("Expected four fields.");
                String id = LedgerService.text(fields.get(0), 120, "Transaction ID");
                String description = LedgerService.text(fields.get(2), 240, "Description");
                if (id.codePoints().anyMatch(Character::isISOControl) || description.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("IDs and descriptions must not contain control characters.");
                if (!ids.add(id)) throw new IllegalArgumentException("Transaction ID appears twice in this CSV.");
                String rawDate = fields.get(1).trim();
                if (!rawDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Use YYYY-MM-DD dates.");
                LocalDate date = LocalDate.parse(rawDate);
                if (date.getYear() < 1) throw new IllegalArgumentException("Date year must be between 0001 and 9999.");
                String rawAmount = fields.get(3).trim();
                boolean outgoing = rawAmount.startsWith("-");
                BigDecimal amount = LedgerService.money(outgoing ? rawAmount.substring(1) : rawAmount);
                rows.add(new Row(id, date, description, outgoing ? amount.negate() : amount));
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException("CSV record " + (i + 1) + ": invalid calendar date.");
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("CSV record " + (i + 1) + ": " + error.getMessage());
            }
        }
        return List.copyOf(rows);
    }

    private List<List<String>> records(String csv) {
        var result = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false, closedQuote = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closedQuote = true; }
                } else field.append(c);
            } else if (c == ',' || c == '\r' || c == '\n') {
                row.add(field.toString()); field.setLength(0); closedQuote = false;
                if (c != ',') {
                    result.add(List.copyOf(row)); row.clear();
                    if (result.size() > MAX_ROWS + 1) throw new IllegalArgumentException("Import at most 500 transactions at a time.");
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                }
            } else if (c == '"' && field.isEmpty() && !closedQuote) quoted = true;
            else {
                if (closedQuote || c == '"') throw new IllegalArgumentException("Malformed CSV quoting.");
                field.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV contains an unclosed quoted field.");
        if (!row.isEmpty() || !field.isEmpty() || closedQuote) {
            row.add(field.toString()); result.add(List.copyOf(row));
        }
        if (result.size() > MAX_ROWS + 1) throw new IllegalArgumentException("Import at most 500 transactions at a time.");
        return result;
    }
}
