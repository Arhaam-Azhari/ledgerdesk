package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerStatementService {
    public record Customer(String id, String name, String email) {}
    public record Movement(String entryId, String lineId, String sourceId, String invoiceId,
            String paymentId, String reference, LocalDate postedOn, String kind, String description,
            BigDecimal charge, BigDecimal reduction, BigDecimal balance) {}
    public record Statement(Customer customer, String currency, LocalDate startsOn, LocalDate endsOn,
            BigDecimal openingBalance, BigDecimal charges, BigDecimal payments, BigDecimal reversals,
            BigDecimal closingBalance, List<Movement> movements) {}
    private final JdbcTemplate db;
    // Receivables move once per invoice, payment or retained invoice reversal.
    private static final String ACTIVITY = """
        FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
        LEFT JOIN payments p ON p.id = e.source_id
        JOIN invoices i ON i.id = COALESCE(p.invoice_id, e.source_id)
        JOIN invoice_numbers n ON n.invoice_id = i.id
        WHERE e.business_id = 1 AND i.business_id = 1 AND i.customer_id = ?
            AND l.account_code = '1100'
        """;
    public CustomerStatementService(JdbcTemplate db) { this.db = db; }
    private static BigDecimal zero() { return new BigDecimal("0.00"); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statement statement(String customerId, LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || startsOn.getYear() < 1 || endsOn.getYear() > 9999 || startsOn.isAfter(endsOn))
            throw new IllegalArgumentException("Choose a valid statement period, with the start on or before the end.");
        var customers = db.query("SELECT id, name, email FROM customers WHERE id = ? AND business_id = 1",
                (row, index) -> new Customer(row.getString("id"), row.getString("name"), row.getString("email")), customerId);
        if (customers.isEmpty()) throw new IllegalArgumentException("Customer not found.");
        BigDecimal opening = db.queryForObject("SELECT COALESCE(SUM(l.debit-l.credit), 0) " + ACTIVITY
                + " AND e.entry_date < ?", BigDecimal.class, customerId, startsOn).setScale(2);
        var rows = db.queryForList("""
            SELECT e.id AS entry_id, l.id AS line_id, e.source_id, i.id AS invoice_id,
                p.id AS payment_id, n.number_value, e.entry_date, i.description,
                l.debit, l.credit
            """ + ACTIVITY + """
             AND e.entry_date BETWEEN ? AND ?
            ORDER BY e.entry_date, CASE WHEN l.debit > 0 THEN 0 WHEN p.id IS NOT NULL THEN 1 ELSE 2 END,
                n.number_value, e.id, l.id
            """, customerId, startsOn, endsOn);
        var movements = new ArrayList<Movement>();
        BigDecimal balance = opening, charges = zero(), payments = zero(), reversals = zero();
        for (var row : rows) {
            BigDecimal debit = ((BigDecimal) row.get("debit")).setScale(2);
            BigDecimal credit = ((BigDecimal) row.get("credit")).setScale(2);
            String paymentId = (String) row.get("payment_id");
            String kind = paymentId != null ? "PAYMENT" : debit.signum() > 0 ? "INVOICE" : "INVOICE_REVERSAL";
            charges = charges.add(debit);
            if (paymentId != null) payments = payments.add(credit); else reversals = reversals.add(credit);
            balance = balance.add(debit).subtract(credit);
            movements.add(new Movement((String) row.get("entry_id"), (String) row.get("line_id"),
                    (String) row.get("source_id"), (String) row.get("invoice_id"), paymentId,
                    LedgerService.invoiceNumber(((Number) row.get("number_value")).longValue()),
                    ((java.sql.Date) row.get("entry_date")).toLocalDate(), kind, (String) row.get("description"), debit, credit, balance));
        }
        return new Statement(customers.get(0), "USD", startsOn, endsOn, opening, charges, payments, reversals, balance, movements);
    }
}
