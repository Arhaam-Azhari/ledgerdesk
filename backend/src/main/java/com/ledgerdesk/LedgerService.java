package com.ledgerdesk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final JdbcTemplate db;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public LedgerService(JdbcTemplate db, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.db = db;
        this.json = json;
    }
    public record Customer(String name, String email) {}
    public record Invoice(String customerId, String description, LocalDate issuedOn, LocalDate dueOn, String amount) {}
    public record Payment(LocalDate paidOn, String amount) {}

    static BigDecimal money(String value) {
        try {
            if (value == null || !value.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            BigDecimal result = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (result.signum() <= 0) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Enter a positive amount with at most two decimal places.");
        }
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw new IllegalArgumentException(field + " is required and must be at most " + max + " characters.");
        return value.trim();
    }

    private String id() { return UUID.randomUUID().toString(); }

    private void lockBusiness() {
        // One local business for this milestone. Serialize posting and retries together.
        db.queryForObject("SELECT id FROM businesses WHERE id = 1 FOR UPDATE", Long.class);
    }

    private String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(value))); }
        catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String retry(String key, String fingerprint) {
        text(key, 100, "Request key");
        List<Map<String, Object>> found = db.queryForList("SELECT * FROM commands WHERE command_key = ?", key);
        if (found.isEmpty()) return null;
        if (!found.get(0).get("fingerprint").equals(fingerprint))
            throw new IllegalArgumentException("This request key was already used for different details.");
        return (String) found.get(0).get("result_id");
    }

    private void complete(String key, String fingerprint, String result, String actor, String action) {
        db.update("INSERT INTO commands VALUES (?, ?, ?)", key, fingerprint, result);
        db.update("INSERT INTO audit_events VALUES (?, ?, ?, ?, ?)", id(), LocalDateTime.now(), actor, action, result);
    }

    private void journal(String source, LocalDate date, String memo, String debitAccount,
                         String creditAccount, BigDecimal amount) {
        String entry = id();
        db.update("INSERT INTO journal_entries VALUES (?, 1, ?, ?, ?)", entry, date, memo, source);
        db.update("INSERT INTO journal_lines VALUES (?, ?, ?, ?, 0)", id(), entry, debitAccount, amount);
        db.update("INSERT INTO journal_lines VALUES (?, ?, ?, 0, ?)", id(), entry, creditAccount, amount);
        // All supported postings create a debit and credit from the same exact amount.
    }

    @Transactional
    public String addCustomer(Customer request, String key, String actor) {
        String name = text(request.name(), 120, "Customer name");
        String email = text(request.email(), 200, "Email");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email address.");
        lockBusiness();
        String hash = fingerprint(List.of("customer", request));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        String customer = id();
        db.update("INSERT INTO customers VALUES (?, 1, ?, ?)", customer, name, email);
        complete(key, hash, customer, actor, "CUSTOMER_CREATED");
        return customer;
    }

    @Transactional
    public String postInvoice(Invoice request, String key, String actor) {
        BigDecimal amount = money(request.amount());
        String description = text(request.description(), 240, "Description");
        if (request.issuedOn() == null || request.dueOn() == null || request.dueOn().isBefore(request.issuedOn()))
            throw new IllegalArgumentException("Due date must be on or after the invoice date.");
        lockBusiness();
        String hash = fingerprint(List.of("invoice", request));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        if (db.queryForObject("SELECT COUNT(*) FROM customers WHERE id = ? AND business_id = 1", Integer.class, request.customerId()) != 1)
            throw new IllegalArgumentException("Customer not found.");
        String invoice = id();
        db.update("INSERT INTO invoices VALUES (?, 1, ?, ?, ?, ?, ?, 0, 'POSTED')", invoice,
                request.customerId(), description, request.issuedOn(), request.dueOn(), amount);
        journal(invoice, request.issuedOn(), description, "1100", "4000", amount);
        complete(key, hash, invoice, actor, "INVOICE_POSTED");
        return invoice;
    }

    @Transactional
    public String recordPayment(String invoiceId, Payment request, String key, String actor) {
        BigDecimal amount = money(request.amount());
        lockBusiness();
        String hash = fingerprint(List.of("payment", invoiceId, request));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        Map<String, Object> invoice = invoice(invoiceId);
        LocalDate issued = ((java.sql.Date) invoice.get("issued_on")).toLocalDate();
        if (request.paidOn() == null || request.paidOn().isBefore(issued))
            throw new IllegalArgumentException("Payment date must be on or after the invoice date.");
        BigDecimal outstanding = ((BigDecimal) invoice.get("amount")).subtract((BigDecimal) invoice.get("paid"));
        if (!invoice.get("status").equals("POSTED") || amount.compareTo(outstanding) > 0)
            throw new IllegalArgumentException("Payment exceeds the outstanding balance or the invoice is void.");
        String payment = id();
        db.update("INSERT INTO payments VALUES (?, ?, ?, ?)", payment, invoiceId, request.paidOn(), amount);
        db.update("UPDATE invoices SET paid = paid + ? WHERE id = ?", amount, invoiceId);
        journal(payment, request.paidOn(), "Payment for " + invoiceId, "1000", "1100", amount);
        complete(key, hash, payment, actor, "PAYMENT_RECORDED");
        return payment;
    }

    @Transactional
    public String voidInvoice(String invoiceId, LocalDate date, String key, String actor) {
        lockBusiness();
        String hash = fingerprint(java.util.Arrays.asList("void", invoiceId, date));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        Map<String, Object> invoice = invoice(invoiceId);
        if (date == null || date.isBefore(((java.sql.Date) invoice.get("issued_on")).toLocalDate()))
            throw new IllegalArgumentException("Reversal date must be on or after the invoice date.");
        if (!invoice.get("status").equals("POSTED") || ((BigDecimal) invoice.get("paid")).signum() != 0)
            throw new IllegalArgumentException("Only an unpaid, posted invoice can be voided.");
        journal(invoiceId, date, "Invoice reversal", "4000", "1100", (BigDecimal) invoice.get("amount"));
        db.update("UPDATE invoices SET status = 'VOID' WHERE id = ?", invoiceId);
        complete(key, hash, invoiceId, actor, "INVOICE_VOIDED");
        return invoiceId;
    }

    private Map<String, Object> invoice(String id) {
        List<Map<String, Object>> invoices = db.queryForList("SELECT * FROM invoices WHERE id = ? AND business_id = 1", id);
        if (invoices.isEmpty()) throw new IllegalArgumentException("Invoice not found.");
        return invoices.get(0);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> state() {
        List<Map<String, Object>> trial = db.queryForList("SELECT a.code, a.name, a.kind, COALESCE(SUM(l.debit), 0) AS debits, COALESCE(SUM(l.credit), 0) AS credits FROM accounts a LEFT JOIN journal_lines l ON l.account_code = a.code GROUP BY a.code, a.name, a.kind ORDER BY a.code");
        return Map.of("business", "Northline Design Studio", "currency", "USD", "customers",
                db.queryForList("SELECT * FROM customers ORDER BY name"), "invoices",
                db.queryForList("SELECT i.*, c.name AS customer_name FROM invoices i JOIN customers c ON c.id = i.customer_id ORDER BY issued_on DESC, id"),
                "trialBalance", trial, "ledger", db.queryForList("SELECT e.entry_date, e.memo, e.source_id, e.id AS entry_id, a.code, a.name, l.debit, l.credit FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id JOIN accounts a ON a.code = l.account_code ORDER BY e.entry_date, e.id, l.credit"),
                "payments", db.queryForList("SELECT * FROM payments ORDER BY paid_on DESC"),
                "audit", db.queryForList("SELECT * FROM audit_events ORDER BY occurred_at DESC"));
    }
}
