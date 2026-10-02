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
    public record Accrual(LocalDate postedOn, String memo, String accountCode, String amount) {}
    public record Reversal(LocalDate reversedOn, String reason) {}

    public AccrualService(JdbcTemplate db, LedgerService ledger) {
        this.db = db;
        this.ledger = ledger;
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
                "SELECT COUNT(*) FROM accounts WHERE code = ? AND kind = 'EXPENSE'", Integer.class, account) != 1)
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
        ledger.complete(key, hash, id, actor, "EXPENSE_ACCRUAL_REVERSED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("accruals", db.queryForList("""
            SELECT a.*, r.id AS reversal_id, r.reversed_on, r.reason AS reversal_reason
            FROM expense_accruals a LEFT JOIN accrual_reversals r ON r.accrual_id = a.id
            WHERE a.business_id = 1 ORDER BY a.posted_on, a.id
            """));
    }
}
