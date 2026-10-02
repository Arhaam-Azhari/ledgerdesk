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
    public record DraftChanges(Invoice invoice, long version) {}
    public record DraftVersion(long version) {}
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

    static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw new IllegalArgumentException(field + " is required and must be at most " + max + " characters.");
        return value.trim();
    }

    String id() { return UUID.randomUUID().toString(); }

    void lockBusiness() {
        // One local business for this milestone. Serialize posting and retries together.
        db.queryForObject("SELECT id FROM businesses WHERE id = 1 FOR UPDATE", Long.class);
    }

    void requireOpenDate(LocalDate date) {
        if (db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations WHERE business_id = 1 AND status = 'CLOSED' AND ends_on >= ?", Integer.class, date) != 0)
            throw new IllegalArgumentException("This date belongs to a closed period. Reopen the latest reconciliation before changing it.");
    }

    String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(value))); }
        catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    String retry(String key, String fingerprint) {
        text(key, 100, "Request key");
        List<Map<String, Object>> found = db.queryForList("SELECT * FROM commands WHERE command_key = ?", key);
        if (found.isEmpty()) return null;
        if (!found.get(0).get("fingerprint").equals(fingerprint))
            throw new IllegalArgumentException("This request key was already used for different details.");
        return (String) found.get(0).get("result_id");
    }

    void complete(String key, String fingerprint, String result, String actor, String action) {
        db.update("INSERT INTO commands VALUES (?, ?, ?)", key, fingerprint, result);
        db.update("INSERT INTO audit_events VALUES (?, ?, ?, ?, ?)", id(), LocalDateTime.now(), actor, action, result);
    }

    void journal(String source, LocalDate date, String memo, String debitAccount,
                         String creditAccount, BigDecimal amount) {
        requireOpenDate(date);
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

    private BigDecimal validateInvoice(Invoice request) {
        if (request == null) throw new IllegalArgumentException("Invoice details are required.");
        BigDecimal amount = money(request.amount());
        text(request.description(), 240, "Description");
        if (request.issuedOn() == null || request.dueOn() == null || request.dueOn().isBefore(request.issuedOn()))
            throw new IllegalArgumentException("Due date must be on or after the invoice date.");
        if (db.queryForObject("SELECT COUNT(*) FROM customers WHERE id = ? AND business_id = 1", Integer.class, request.customerId()) != 1)
            throw new IllegalArgumentException("Customer not found.");
        return amount;
    }

    private String insertInvoice(Invoice request, BigDecimal amount) {
        String invoice = id();
        db.update("INSERT INTO invoices VALUES (?, 1, ?, ?, ?, ?, ?, 0, 'POSTED')", invoice,
                request.customerId(), request.description().trim(), request.issuedOn(), request.dueOn(), amount);
        long number = db.queryForObject("SELECT next_invoice_number FROM businesses WHERE id = 1", Long.class);
        db.update("INSERT INTO invoice_numbers VALUES (?, ?)", invoice, number);
        db.update("UPDATE businesses SET next_invoice_number = next_invoice_number + 1 WHERE id = 1");
        journal(invoice, request.issuedOn(), request.description().trim(), "1100", "4000", amount);
        return invoice;
    }

    @Transactional
    public String postInvoice(Invoice request, String key, String actor) {
        lockBusiness();
        BigDecimal amount = validateInvoice(request);
        String hash = fingerprint(List.of("invoice", request));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        String invoice = insertInvoice(request, amount);
        complete(key, hash, invoice, actor, "INVOICE_POSTED");
        return invoice;
    }

    @Transactional
    public String createDraft(Invoice request, String key, String actor) {
        lockBusiness();
        BigDecimal amount = validateInvoice(request);
        String hash = fingerprint(List.of("draft", request));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        String draft = id();
        db.update("INSERT INTO invoice_drafts (id, business_id, customer_id, description, issued_on, due_on, amount) VALUES (?, 1, ?, ?, ?, ?, ?)",
                draft, request.customerId(), request.description().trim(), request.issuedOn(), request.dueOn(), amount);
        complete(key, hash, draft, actor, "DRAFT_SAVED");
        return draft;
    }

    private Map<String, Object> draft(String draftId) {
        var rows = db.queryForList("SELECT * FROM invoice_drafts WHERE id = ? AND business_id = 1", draftId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Draft not found.");
        return rows.get(0);
    }

    private void editableDraft(Map<String, Object> draft, long version) {
        if (draft.get("posted_invoice_id") != null || Boolean.TRUE.equals(draft.get("cancelled")))
            throw new IllegalArgumentException("This draft was already posted or discarded.");
        // A second tab must reload rather than overwrite a newer saved version.
        if (((Number) draft.get("version")).longValue() != version)
            throw new IllegalArgumentException("This draft has changed. Reload the workspace before trying again.");
    }

    @Transactional
    public String updateDraft(String draftId, DraftChanges request, String key, String actor) {
        lockBusiness();
        BigDecimal amount = validateInvoice(request.invoice());
        String hash = fingerprint(List.of("draft-update", draftId, request));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        editableDraft(draft(draftId), request.version());
        Invoice details = request.invoice();
        db.update("UPDATE invoice_drafts SET customer_id = ?, description = ?, issued_on = ?, due_on = ?, amount = ?, version = version + 1 WHERE id = ?",
                details.customerId(), details.description().trim(), details.issuedOn(), details.dueOn(), amount, draftId);
        complete(key, hash, draftId, actor, "DRAFT_UPDATED");
        return draftId;
    }

    @Transactional
    public String postDraft(String draftId, long version, String key, String actor) {
        lockBusiness();
        String hash = fingerprint(List.of("draft-post", draftId, version));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        Map<String, Object> saved = draft(draftId);
        editableDraft(saved, version);
        Invoice request = new Invoice((String) saved.get("customer_id"), (String) saved.get("description"),
                ((java.sql.Date) saved.get("issued_on")).toLocalDate(), ((java.sql.Date) saved.get("due_on")).toLocalDate(),
                saved.get("amount").toString());
        String invoice = insertInvoice(request, validateInvoice(request));
        db.update("UPDATE invoice_drafts SET posted_invoice_id = ?, version = version + 1 WHERE id = ?", invoice, draftId);
        complete(key, hash, invoice, actor, "DRAFT_POSTED");
        return invoice;
    }

    @Transactional
    public String discardDraft(String draftId, long version, String key, String actor) {
        lockBusiness();
        String hash = fingerprint(List.of("draft-discard", draftId, version));
        String previous = retry(key, hash);
        if (previous != null) return previous;
        editableDraft(draft(draftId), version);
        db.update("UPDATE invoice_drafts SET cancelled = TRUE, version = version + 1 WHERE id = ?", draftId);
        complete(key, hash, draftId, actor, "DRAFT_DISCARDED");
        return draftId;
    }

    public static String invoiceNumber(long value) { return String.format(java.util.Locale.ROOT, "INV-%06d", value); }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> invoiceDocument(String invoiceId) {
        var rows = db.queryForList("SELECT i.*, n.number_value, c.name AS customer_name, c.email AS customer_email FROM invoices i JOIN invoice_numbers n ON n.invoice_id = i.id JOIN customers c ON c.id = i.customer_id WHERE i.id = ? AND i.business_id = 1", invoiceId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Invoice not found.");
        return Map.of("invoice", rows.get(0), "business", "Northline Design Studio", "payments",
                db.queryForList("SELECT paid_on, amount FROM payments WHERE invoice_id = ? ORDER BY paid_on, id", invoiceId));
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
        Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of("business", "Northline Design Studio", "currency", "USD", "customers",
                db.queryForList("SELECT c.*, COALESCE(SUM(CASE WHEN i.status = 'POSTED' THEN i.amount ELSE 0 END), 0) AS invoiced, COALESCE(SUM(i.paid), 0) AS paid, COALESCE(SUM(CASE WHEN i.status = 'POSTED' THEN i.amount - i.paid ELSE 0 END), 0) AS outstanding FROM customers c LEFT JOIN invoices i ON i.customer_id = c.id GROUP BY c.id, c.business_id, c.name, c.email ORDER BY c.name, c.id"), "invoices",
                db.queryForList("SELECT i.*, n.number_value, c.name AS customer_name FROM invoices i JOIN invoice_numbers n ON n.invoice_id = i.id JOIN customers c ON c.id = i.customer_id ORDER BY issued_on DESC, id"),
                "drafts", db.queryForList("SELECT d.*, c.name AS customer_name FROM invoice_drafts d JOIN customers c ON c.id = d.customer_id WHERE d.cancelled = FALSE AND d.posted_invoice_id IS NULL ORDER BY d.issued_on DESC, d.id"),
                "trialBalance", trial, "ledger", db.queryForList("SELECT e.entry_date, e.memo, e.source_id, e.id AS entry_id, a.code, a.name, l.debit, l.credit FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id JOIN accounts a ON a.code = l.account_code ORDER BY e.entry_date, e.id, l.credit"),
                "payments", db.queryForList("SELECT * FROM payments ORDER BY paid_on DESC"),
                "audit", db.queryForList("SELECT * FROM audit_events ORDER BY occurred_at DESC")));
        result.putAll(EquityService.readState(db));
        result.putAll(PurchaseService.readState(db));
        result.putAll(BankService.readState(db));
        result.putAll(BankMatching.readState(db));
        result.putAll(BankReconciliation.readState(db));
        return result;
    }
}
