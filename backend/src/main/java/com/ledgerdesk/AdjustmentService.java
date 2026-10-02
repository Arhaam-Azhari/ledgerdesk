package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdjustmentService {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    public record Line(String accountCode, String debit, String credit) {}
    public record Adjustment(LocalDate postedOn, String memo, List<Line> lines) {}

    public AdjustmentService(JdbcTemplate db, LedgerService ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    private static BigDecimal amount(String value) {
        if (List.of("0", "0.0", "0.00").contains(value == null ? "" : value)) return new BigDecimal("0.00");
        return LedgerService.money(value);
    }

    @Transactional
    public String post(Adjustment request, String key, String actor) {
        if (request == null || request.postedOn() == null || request.postedOn().getYear() < 1 || request.postedOn().getYear() > 9999)
            throw new IllegalArgumentException("Choose a valid adjustment date.");
        String memo = LedgerService.text(request.memo(), 300, "Adjustment memo");
        if (request.lines() == null || request.lines().size() < 2 || request.lines().size() > 20)
            throw new IllegalArgumentException("Include between two and twenty adjustment lines.");
        var codes = new HashSet<String>();
        BigDecimal debits = new BigDecimal("0.00"), credits = new BigDecimal("0.00");
        for (Line line : request.lines()) {
            if (line == null) throw new IllegalArgumentException("Each adjustment line is required.");
            String code = LedgerService.text(line.accountCode(), 4, "Account code");
            if (!code.equals(line.accountCode()) || !codes.add(code))
                throw new IllegalArgumentException("Choose each account once in an adjustment.");
            // Control accounts need their document/payment workflows to keep aging and cash evidence consistent.
            if (db.queryForObject("SELECT COUNT(*) FROM accounts WHERE code = ? AND kind = 'EXPENSE'", Integer.class, code) != 1)
                throw new IllegalArgumentException("This adjustment supports operating expense categories only.");
            BigDecimal debit = amount(line.debit()), credit = amount(line.credit());
            if ((debit.signum() == 0) == (credit.signum() == 0))
                throw new IllegalArgumentException("Each line needs a positive debit or credit, with zero on the other side.");
            debits = debits.add(debit); credits = credits.add(credit);
        }
        if (debits.compareTo(credits) != 0)
            throw new IllegalArgumentException("Adjustment debits and credits must balance exactly.");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("expense-adjustment", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        ledger.requireOpenDate(request.postedOn());
        String id = ledger.id(), entry = ledger.id();
        db.update("INSERT INTO journal_adjustments VALUES (?, 1, ?, ?)", id, request.postedOn(), memo);
        db.update("INSERT INTO journal_entries VALUES (?, 1, ?, ?, ?)", entry, request.postedOn(), memo, id);
        for (Line line : request.lines())
            db.update("INSERT INTO journal_lines VALUES (?, ?, ?, ?, ?)", ledger.id(), entry,
                    line.accountCode(), amount(line.debit()), amount(line.credit()));
        ledger.complete(key, hash, id, actor, "JOURNAL_ADJUSTMENT_POSTED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("adjustments", db.queryForList("SELECT * FROM journal_adjustments WHERE business_id = 1 ORDER BY posted_on, id"),
                "adjustmentLines", db.queryForList("""
                    SELECT j.id AS adjustment_id, l.id, l.account_code, a.name, l.debit, l.credit
                    FROM journal_adjustments j JOIN journal_entries e ON e.source_id = j.id AND e.business_id = j.business_id
                    JOIN journal_lines l ON l.entry_id = e.id JOIN accounts a ON a.code = l.account_code
                    WHERE j.business_id = 1 ORDER BY j.posted_on, j.id, l.account_code
                    """));
    }
}
