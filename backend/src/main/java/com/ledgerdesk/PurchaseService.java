package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {
    private final JdbcTemplate db;
    private final LedgerService ledger;

    public PurchaseService(JdbcTemplate db, LedgerService ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    public record Vendor(String name, String email) {}
    public record Bill(String vendorId, String reference, String description, LocalDate issuedOn,
                       LocalDate dueOn, String accountCode, String amount) {}
    public record Expense(String vendorId, String description, LocalDate spentOn, String accountCode, String amount) {}

    private void vendor(String id) {
        if (db.queryForObject("SELECT COUNT(*) FROM vendors WHERE id = ? AND business_id = 1", Integer.class, id) != 1)
            throw new IllegalArgumentException("Vendor not found.");
    }

    private void category(String code) {
        if (db.queryForObject("SELECT COUNT(*) FROM accounts WHERE code = ? AND kind = 'EXPENSE'", Integer.class, code) != 1)
            throw new IllegalArgumentException("Choose an operating expense category.");
    }

    @Transactional
    public String addVendor(Vendor request, String key, String actor) {
        String name = LedgerService.text(request.name(), 120, "Vendor name");
        String email = LedgerService.text(request.email(), 200, "Email");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email address.");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("vendor", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        String id = ledger.id();
        db.update("INSERT INTO vendors VALUES (?, 1, ?, ?)", id, name, email);
        ledger.complete(key, hash, id, actor, "VENDOR_CREATED");
        return id;
    }

    @Transactional
    public String postBill(Bill request, String key, String actor) {
        BigDecimal amount = LedgerService.money(request.amount());
        String description = LedgerService.text(request.description(), 240, "Description");
        String reference = LedgerService.text(request.reference(), 80, "Bill reference");
        if (request.issuedOn() == null || request.dueOn() == null || request.dueOn().isBefore(request.issuedOn()))
            throw new IllegalArgumentException("Due date must be on or after the bill date.");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("bill", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        vendor(request.vendorId()); category(request.accountCode());
        String referenceKey = LedgerService.text(reference.toUpperCase(Locale.ROOT), 80, "Normalized bill reference");
        if (db.queryForObject("SELECT COUNT(*) FROM bills WHERE vendor_id = ? AND business_id = 1 AND reference_key = ?", Integer.class,
                request.vendorId(), referenceKey) != 0)
            throw new IllegalArgumentException("This vendor's bill reference is already recorded, including voided bills.");
        String id = ledger.id();
        db.update("INSERT INTO bills (id, business_id, vendor_id, reference, reference_key, description, issued_on, due_on, account_code, amount) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, request.vendorId(), reference, referenceKey, description, request.issuedOn(), request.dueOn(), request.accountCode(), amount);
        ledger.journal(id, request.issuedOn(), "Bill " + reference, request.accountCode(), "2000", amount);
        ledger.complete(key, hash, id, actor, "BILL_POSTED");
        return id;
    }

    private Map<String, Object> document(String type, String id) {
        String table = switch (type) {
            case "bills" -> "bills";
            case "expenses" -> "expenses";
            default -> throw new IllegalArgumentException("Receipts belong to a bill or a direct expense.");
        };
        var rows = db.queryForList("SELECT * FROM " + table + " WHERE id = ? AND business_id = 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Purchase record not found.");
        return rows.get(0);
    }

    @Transactional
    public String payBill(String billId, LedgerService.Payment request, String key, String actor) {
        BigDecimal amount = LedgerService.money(request.amount());
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("bill-payment", billId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var bill = document("bills", billId);
        LocalDate issued = ((java.sql.Date) bill.get("issued_on")).toLocalDate();
        if (request.paidOn() == null || request.paidOn().isBefore(issued))
            throw new IllegalArgumentException("Payment date must be on or after the bill date.");
        BigDecimal outstanding = ((BigDecimal) bill.get("amount")).subtract((BigDecimal) bill.get("paid"));
        if (!bill.get("status").equals("POSTED") || amount.compareTo(outstanding) > 0)
            throw new IllegalArgumentException("Payment exceeds the amount owed or the bill is void.");
        String id = ledger.id();
        db.update("INSERT INTO bill_payments VALUES (?, ?, ?, ?)", id, billId, request.paidOn(), amount);
        db.update("UPDATE bills SET paid = paid + ? WHERE id = ?", amount, billId);
        ledger.journal(id, request.paidOn(), "Payment for bill " + bill.get("reference"), "2000", "1000", amount);
        ledger.complete(key, hash, id, actor, "BILL_PAYMENT_RECORDED");
        return id;
    }

    @Transactional
    public String voidBill(String billId, LocalDate date, String key, String actor) {
        ledger.lockBusiness();
        String hash = ledger.fingerprint(java.util.Arrays.asList("bill-void", billId, date));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var bill = document("bills", billId);
        if (date == null || date.isBefore(((java.sql.Date) bill.get("issued_on")).toLocalDate()))
            throw new IllegalArgumentException("Reversal date must be on or after the bill date.");
        if (!bill.get("status").equals("POSTED") || ((BigDecimal) bill.get("paid")).signum() != 0)
            throw new IllegalArgumentException("Only an unpaid, posted bill can be voided.");
        ledger.journal(billId, date, "Bill reversal: " + bill.get("reference"), "2000", (String) bill.get("account_code"), (BigDecimal) bill.get("amount"));
        db.update("UPDATE bills SET status = 'VOID' WHERE id = ?", billId);
        ledger.complete(key, hash, billId, actor, "BILL_VOIDED");
        return billId;
    }

    @Transactional
    public String postExpense(Expense request, String key, String actor) {
        BigDecimal amount = LedgerService.money(request.amount());
        String description = LedgerService.text(request.description(), 240, "Description");
        if (request.spentOn() == null) throw new IllegalArgumentException("Expense date is required.");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("expense", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        vendor(request.vendorId()); category(request.accountCode());
        String id = ledger.id();
        db.update("INSERT INTO expenses (id, business_id, vendor_id, description, spent_on, account_code, amount) VALUES (?, 1, ?, ?, ?, ?, ?)",
                id, request.vendorId(), description, request.spentOn(), request.accountCode(), amount);
        ledger.journal(id, request.spentOn(), description, request.accountCode(), "1000", amount);
        ledger.complete(key, hash, id, actor, "EXPENSE_POSTED");
        return id;
    }

    @Transactional
    public String reverseExpense(String expenseId, LocalDate date, String key, String actor) {
        ledger.lockBusiness();
        String hash = ledger.fingerprint(java.util.Arrays.asList("expense-reverse", expenseId, date));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var expense = document("expenses", expenseId);
        if (date == null || date.isBefore(((java.sql.Date) expense.get("spent_on")).toLocalDate()))
            throw new IllegalArgumentException("Reversal date must be on or after the expense date.");
        if (!expense.get("status").equals("POSTED")) throw new IllegalArgumentException("This expense is already reversed.");
        if (db.queryForObject("SELECT COUNT(*) FROM bank_matches m JOIN journal_lines l ON l.id = m.line_id JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ?", Integer.class, expenseId) != 0)
            throw new IllegalArgumentException("Unmatch the bank transaction before correcting this expense.");
        ledger.journal(expenseId, date, "Expense correction", "1000", (String) expense.get("account_code"), (BigDecimal) expense.get("amount"));
        db.update("UPDATE expenses SET status = 'VOID' WHERE id = ?", expenseId);
        ledger.complete(key, hash, expenseId, actor, "EXPENSE_REVERSED");
        return expenseId;
    }

    @Transactional
    public String attachReceipt(String type, String recordId, ReceiptValidator.Validated file, String key, String actor) {
        ledger.lockBusiness();
        document(type, recordId);
        String hash = ledger.fingerprint(List.of("receipt", type, recordId, file.filename(), file.mediaType(), file.sha()));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        String column = type.equals("bills") ? "bill_id" : "expense_id";
        var existing = db.queryForList("SELECT id FROM receipts WHERE " + column + " = ? AND content_sha = ?", String.class, recordId, file.sha());
        if (!existing.isEmpty()) {
            ledger.complete(key, hash, existing.get(0), actor, "RECEIPT_REUSED");
            return existing.get(0);
        }
        if (db.queryForObject("SELECT COUNT(*) FROM receipts WHERE " + column + " = ?", Integer.class, recordId) >= 5)
            throw new IllegalArgumentException("This record already has five receipts.");
        String id = ledger.id();
        db.update("INSERT INTO receipts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, type.equals("bills") ? recordId : null,
                type.equals("expenses") ? recordId : null, file.filename(), file.mediaType(), file.sha(), file.bytes().length, file.bytes(), LocalDateTime.now());
        ledger.complete(key, hash, id, actor, "RECEIPT_ATTACHED");
        return id;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> receipt(String id) {
        var rows = db.queryForList("SELECT r.* FROM receipts r LEFT JOIN bills b ON b.id = r.bill_id LEFT JOIN expenses e ON e.id = r.expense_id WHERE r.id = ? AND (b.business_id = 1 OR e.business_id = 1)", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Receipt not found.");
        return rows.get(0);
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        // Metadata only: loading the workspace must not load every attachment's bytes.
        return Map.of("vendors", db.queryForList("SELECT v.*, COALESCE(SUM(CASE WHEN b.status = 'POSTED' THEN b.amount ELSE 0 END), 0) AS billed, COALESCE(SUM(b.paid), 0) AS paid, COALESCE(SUM(CASE WHEN b.status = 'POSTED' THEN b.amount - b.paid ELSE 0 END), 0) AS outstanding FROM vendors v LEFT JOIN bills b ON b.vendor_id = v.id AND b.business_id = 1 WHERE v.business_id = 1 GROUP BY v.id, v.business_id, v.name, v.email ORDER BY v.name, v.id"),
                "bills", db.queryForList("SELECT b.*, v.name AS vendor_name, a.name AS category FROM bills b JOIN vendors v ON v.id = b.vendor_id JOIN accounts a ON a.code = b.account_code WHERE b.business_id = 1 ORDER BY b.issued_on DESC, b.id"),
                "expenses", db.queryForList("SELECT e.*, v.name AS vendor_name, a.name AS category FROM expenses e JOIN vendors v ON v.id = e.vendor_id JOIN accounts a ON a.code = e.account_code WHERE e.business_id = 1 ORDER BY e.spent_on DESC, e.id"),
                "billPayments", db.queryForList("SELECT p.*, b.reference, v.name AS vendor_name FROM bill_payments p JOIN bills b ON b.id = p.bill_id JOIN vendors v ON v.id = b.vendor_id WHERE b.business_id = 1 ORDER BY p.paid_on DESC, p.id"),
                "expenseCategories", db.queryForList("SELECT code, name FROM accounts WHERE kind = 'EXPENSE' ORDER BY code"),
                "receipts", db.queryForList("SELECT r.id, r.bill_id, r.expense_id, r.filename, r.media_type, r.size_bytes, r.uploaded_at FROM receipts r LEFT JOIN bills b ON b.id = r.bill_id LEFT JOIN expenses e ON e.id = r.expense_id WHERE b.business_id = 1 OR e.business_id = 1 ORDER BY r.uploaded_at, r.id"));
    }
}
