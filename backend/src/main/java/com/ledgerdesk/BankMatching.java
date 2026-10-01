package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankMatching {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    public record Match(String lineId) {}
    public record Unmatch(String matchId) {}
    private static final String CASH_RECORDS = """
        SELECT l.id AS line_id, e.entry_date, e.memo, e.source_id, l.debit-l.credit AS amount,
            CASE WHEN p.id IS NOT NULL THEN 'Customer payment'
                 WHEN bp.id IS NOT NULL THEN 'Bill payment' ELSE 'Direct expense' END AS kind
        FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
        LEFT JOIN payments p ON p.id = e.source_id
        LEFT JOIN invoices i ON i.id = p.invoice_id
        LEFT JOIN bill_payments bp ON bp.id = e.source_id
        LEFT JOIN bills b ON b.id = bp.bill_id
        LEFT JOIN expenses x ON x.id = e.source_id
        WHERE l.account_code = '1000' AND e.business_id = 1 AND
            ((i.business_id = 1 AND i.status = 'POSTED') OR
             (b.business_id = 1 AND b.status = 'POSTED') OR
             (x.business_id = 1 AND x.status = 'POSTED'))
        """;

    public BankMatching(JdbcTemplate db, LedgerService ledger) { this.db = db; this.ledger = ledger; }

    private Map<String, Object> transaction(String id) {
        var rows = db.queryForList("SELECT * FROM bank_transactions WHERE id = ? AND business_id = 1 AND account_code = '1000'", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Bank transaction not found.");
        return rows.get(0);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> candidates(String transactionId) {
        var bank = transaction(transactionId);
        if (db.queryForObject("SELECT COUNT(*) FROM bank_matches WHERE transaction_id = ?", Integer.class, transactionId) != 0)
            return List.of();
        var rows = db.queryForList(CASH_RECORDS + " AND l.debit-l.credit = ? AND NOT EXISTS (SELECT 1 FROM bank_matches m WHERE m.line_id = l.id)", bank.get("amount"));
        LocalDate posted = ((java.sql.Date) bank.get("posted_on")).toLocalDate();
        for (var row : rows) {
            long days = Math.abs(ChronoUnit.DAYS.between(((java.sql.Date) row.get("entry_date")).toLocalDate(), posted));
            row.put("days_apart", days); row.put("near_date", days <= 7);
        }
        rows.sort(Comparator.<Map<String, Object>>comparingLong(row -> ((Number) row.get("days_apart")).longValue())
                .thenComparing(row -> row.get("line_id").toString()));
        return rows;
    }

    @Transactional
    public String match(String transactionId, Match request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Choose a recorded bank entry.");
        String lineId = LedgerService.text(request.lineId(), 36, "Ledger line");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("bank-match", transactionId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var bank = transaction(transactionId);
        var lines = db.queryForList(CASH_RECORDS + " AND l.id = ?", lineId);
        if (lines.isEmpty()) throw new IllegalArgumentException("Choose a posted customer payment, bill payment, or direct expense in this bank account.");
        if (((BigDecimal) lines.get(0).get("amount")).compareTo((BigDecimal) bank.get("amount")) != 0)
            throw new IllegalArgumentException("The recorded entry must have the same signed amount as the bank transaction.");
        if (db.queryForObject("SELECT COUNT(*) FROM bank_matches WHERE transaction_id = ? OR line_id = ?", Integer.class, transactionId, lineId) != 0)
            throw new IllegalArgumentException("This bank transaction or recorded entry is already matched. Reload the workspace.");
        String id = ledger.id();
        var now = LocalDateTime.now();
        db.update("INSERT INTO bank_matches VALUES (?, ?, ?, ?)", id, transactionId, lineId, now);
        db.update("INSERT INTO bank_match_events VALUES (?, ?, ?, ?, 'MATCHED', ?)", ledger.id(), id, transactionId, lineId, now);
        ledger.complete(key, hash, id, actor, "BANK_TRANSACTION_MATCHED");
        return id;
    }

    @Transactional
    public String unmatch(String transactionId, Unmatch request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("The current match is required.");
        String matchId = LedgerService.text(request.matchId(), 36, "Match ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("bank-unmatch", transactionId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        transaction(transactionId);
        var matches = db.queryForList("SELECT * FROM bank_matches WHERE transaction_id = ? AND id = ?", transactionId, matchId);
        if (matches.isEmpty()) throw new IllegalArgumentException("The match has changed or was already removed. Reload the workspace.");
        var match = matches.get(0);
        db.update("DELETE FROM bank_matches WHERE id = ?", matchId);
        db.update("INSERT INTO bank_match_events VALUES (?, ?, ?, ?, 'UNMATCHED', ?)", ledger.id(), matchId, transactionId, match.get("line_id"), LocalDateTime.now());
        ledger.complete(key, hash, matchId, actor, "BANK_TRANSACTION_UNMATCHED");
        return matchId;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("bankMatches", db.queryForList("SELECT m.*, e.entry_date, e.memo, l.debit-l.credit AS amount FROM bank_matches m JOIN bank_transactions t ON t.id = m.transaction_id JOIN journal_lines l ON l.id = m.line_id JOIN journal_entries e ON e.id = l.entry_id WHERE t.business_id = 1 ORDER BY m.matched_at, m.id"),
                "bankMatchEvents", db.queryForList("SELECT h.* FROM bank_match_events h JOIN bank_transactions t ON t.id = h.transaction_id WHERE t.business_id = 1 ORDER BY h.occurred_at, h.id"));
    }
}
