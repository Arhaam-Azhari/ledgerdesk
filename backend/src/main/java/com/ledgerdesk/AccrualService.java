package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccrualService {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    private final PurchaseService purchases;
    public record Accrual(LocalDate postedOn, String memo, String accountCode, String amount) {}
    public record Reversal(LocalDate reversedOn, String reason) {}

    public record BillArrival(String vendorId, String reference, String description, LocalDate issuedOn, LocalDate dueOn, String amount) {}

    public AccrualService(JdbcTemplate db, LedgerService ledger, PurchaseService purchases) {
        this.db = db;
        this.ledger = ledger;
        this.purchases = purchases;
    }

    private static void validDate(LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999)
            throw new IllegalArgumentException("Choose a valid accrual date.");
    }

    @Transactional
    public String post(Accrual request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Accrual details are required.");
        validDate(request.postedOn());
        String memo = LedgerService.text(request.memo(), 240, "Accrual memo");
        String account = LedgerService.text(request.accountCode(), 4, "Expense account");
        if (!account.equals(request.accountCode()) || db.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE code = ? AND kind = 'EXPENSE' AND code NOT IN ('5600', '5700')", Integer.class, account) != 1)
            throw new IllegalArgumentException("Choose an operating expense category for the accrual.");
        BigDecimal amount = LedgerService.money(request.amount());
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("expense-accrual", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        ledger.requireOpenDate(request.postedOn());
        String id = ledger.id();
        db.update("INSERT INTO expense_accruals VALUES (?, 1, ?, ?, ?, ?)", id, request.postedOn(), memo, account, amount);
        // No supplier bill exists yet. Keep this liability out of accounts payable and its aging report.
        ledger.journal(id, request.postedOn(), memo, account, "2100", amount);
        ledger.complete(key, hash, id, actor, "EXPENSE_ACCRUAL_POSTED");
        return id;
    }

    @Transactional
    public String reverse(String accrualId, Reversal request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Reversal details are required.");
        validDate(request.reversedOn());
        String reason = LedgerService.text(request.reason(), 240, "Reversal reason");
        LedgerService.text(accrualId, 36, "Accrual ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("expense-accrual-reversal", accrualId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        String id = reverseEntry(accrualId, request, reason);
        ledger.complete(key, hash, id, actor, "EXPENSE_ACCRUAL_REVERSED");
        return id;
    }

    private String reverseEntry(String accrualId, Reversal request, String reason) {
        var found = db.queryForList("SELECT * FROM expense_accruals WHERE id = ? AND business_id = 1", accrualId);
        if (found.isEmpty()) throw new IllegalArgumentException("Accrual not found.");
        var accrual = found.get(0);
        LocalDate posted = ((java.sql.Date) accrual.get("posted_on")).toLocalDate();
        if (request.reversedOn().isBefore(posted))
            throw new IllegalArgumentException("The reversal date cannot precede the accrual.");
        if (db.queryForObject("SELECT COUNT(*) FROM accrual_reversals WHERE accrual_id = ?", Integer.class, accrualId) != 0)
            throw new IllegalArgumentException("This accrual was already reversed. Reload the workspace.");
        ledger.requireOpenDate(request.reversedOn());
        var original = db.queryForList("""
            SELECT l.account_code, l.debit, l.credit FROM journal_entries e
            JOIN journal_lines l ON l.entry_id = e.id
            WHERE e.business_id = 1 AND e.source_id = ? ORDER BY l.account_code
            """, accrualId);
        BigDecimal amount = (BigDecimal) accrual.get("amount");
        boolean liability = false, expense = false;
        for (var line : original) {
            BigDecimal debit = (BigDecimal) line.get("debit"), credit = (BigDecimal) line.get("credit");
            if (line.get("account_code").equals("2100") && debit.signum() == 0 && credit.compareTo(amount) == 0) liability = true;
            if (line.get("account_code").equals(accrual.get("account_code")) && credit.signum() == 0 && debit.compareTo(amount) == 0) expense = true;
        }
        if (original.size() != 2 || !liability || !expense)
            throw new IllegalArgumentException("The original accrual journal is inconsistent. Review it before reversing.");
        String id = ledger.id();
        db.update("INSERT INTO accrual_reversals VALUES (?, ?, ?, ?)", id, accrualId, request.reversedOn(), reason);
        // A later reversal leaves the earlier expense and liability visible at their original cutoff.
        ledger.journal(id, request.reversedOn(), "Accrual reversal: " + reason, "2100", accrual.get("account_code").toString(), amount);
        return id;
    }

    @Transactional
    public String receiveBill(String accrualId, BillArrival request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Supplier bill details are required.");
        validDate(request.issuedOn());
        validDate(request.dueOn());
        LedgerService.text(accrualId, 36, "Accrual ID");
        String reference = LedgerService.text(request.reference(), 80, "Bill reference");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("accrual-bill", accrualId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT account_code FROM expense_accruals WHERE id = ? AND business_id = 1", accrualId);
        if (found.isEmpty()) throw new IllegalArgumentException("Accrual not found.");
        String account = found.get(0).get("account_code").toString();
        String reason = "Replaced by supplier bill " + reference;
        // Both postings use the bill date, so reports never see the bill without its estimate offset.
        String reversal = reverseEntry(accrualId, new Reversal(request.issuedOn(), reason), reason);
        String bill = purchases.createBill(new PurchaseService.Bill(request.vendorId(), reference, request.description(),
                request.issuedOn(), request.dueOn(), account, request.amount()));
        db.update("INSERT INTO accrual_bills VALUES (?, ?, ?)", accrualId, bill, reversal);
        ledger.complete(key, hash, bill, actor, "ACCRUAL_BILL_POSTED");
        return bill;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("accruals", db.queryForList("""
            SELECT a.*, r.id AS reversal_id, r.reversed_on, r.reason AS reversal_reason,
                b.id AS bill_id, b.reference AS bill_reference, b.amount AS bill_amount, b.status AS bill_status
            FROM expense_accruals a LEFT JOIN accrual_reversals r ON r.accrual_id = a.id
            LEFT JOIN accrual_bills link ON link.accrual_id = a.id
            LEFT JOIN bills b ON b.id = link.bill_id AND b.business_id = a.business_id
            WHERE a.business_id = 1 ORDER BY a.posted_on, a.id
            """));
    }
}
