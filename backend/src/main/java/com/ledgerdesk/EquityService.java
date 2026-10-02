package com.ledgerdesk;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquityService {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    public record Reversal(LocalDate reversedOn, String reason) {}
    public record Transfer(String kind, LocalDate postedOn, String memo, String amount) {}

    public EquityService(JdbcTemplate db, LedgerService ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    @Transactional
    public String post(Transfer request, String key, String actor) {
        if (request == null || !List.of("CONTRIBUTION", "DRAWING").contains(request.kind() == null ? "" : request.kind()))
            throw new IllegalArgumentException("Choose an owner contribution or drawing.");
        if (request.postedOn() == null || request.postedOn().getYear() < 1 || request.postedOn().getYear() > 9999)
            throw new IllegalArgumentException("Choose a valid posting date.");
        String memo = LedgerService.text(request.memo(), 240, "Memo");
        var amount = LedgerService.money(request.amount());
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("owner-equity", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        String id = ledger.id();
        db.update("INSERT INTO equity_transactions VALUES (?, 1, ?, ?, ?, ?)",
                id, request.kind(), request.postedOn(), memo, amount);
        // Owner funding is equity, even when it arrives in the same bank account as sales.
        boolean contribution = request.kind().equals("CONTRIBUTION");
        ledger.journal(id, request.postedOn(), memo,
                contribution ? "1000" : "3100", contribution ? "3000" : "1000", amount);
        ledger.complete(key, hash, id, actor, contribution ? "OWNER_CONTRIBUTION_POSTED" : "OWNER_DRAWING_POSTED");
        return id;
    }

    @Transactional
    public String reverse(String transferId, Reversal request, String key, String actor) {
        if (request == null || request.reversedOn() == null || request.reversedOn().getYear() < 1 || request.reversedOn().getYear() > 9999)
            throw new IllegalArgumentException("Choose a valid reversal date.");
        String reason = LedgerService.text(request.reason(), 240, "Reversal reason");
        LedgerService.text(transferId, 36, "Transfer ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("owner-equity-reversal", transferId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var rows = db.queryForList("SELECT * FROM equity_transactions WHERE id = ? AND business_id = 1", transferId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Owner transfer not found.");
        var transfer = rows.get(0);
        LocalDate posted = ((java.sql.Date) transfer.get("posted_on")).toLocalDate();
        if (request.reversedOn().isBefore(posted))
            throw new IllegalArgumentException("The reversal date cannot precede the original transfer.");
        ledger.requireOpenDate(posted);
        if (db.queryForObject("SELECT COUNT(*) FROM equity_reversals WHERE transfer_id = ?", Integer.class, transferId) != 0)
            throw new IllegalArgumentException("This owner transfer was already reversed. Reload the workspace.");
        if (db.queryForObject("SELECT COUNT(*) FROM bank_matches m JOIN journal_lines l ON l.id = m.line_id JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ?", Integer.class, transferId) != 0)
            throw new IllegalArgumentException("Unmatch this owner transfer before correcting it.");
        String reversal = ledger.id();
        db.update("INSERT INTO equity_reversals VALUES (?, ?, ?, ?)", reversal, transferId, request.reversedOn(), reason);
        boolean contribution = transfer.get("kind").equals("CONTRIBUTION");
        // Keep the original entry. The offset belongs to the chosen correction date.
        ledger.journal(reversal, request.reversedOn(), "Owner transfer reversal: " + reason,
                contribution ? "3000" : "1000", contribution ? "1000" : "3100",
                (java.math.BigDecimal) transfer.get("amount"));
        ledger.complete(key, hash, reversal, actor, "OWNER_TRANSFER_REVERSED");
        return reversal;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("equityTransactions", db.queryForList(
                "SELECT t.*, r.id AS reversal_id, r.reversed_on, r.reason AS reversal_reason FROM equity_transactions t LEFT JOIN equity_reversals r ON r.transfer_id = t.id WHERE t.business_id = 1 ORDER BY t.posted_on, t.id"));
    }
}
