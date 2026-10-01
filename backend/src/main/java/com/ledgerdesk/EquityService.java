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

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("equityTransactions", db.queryForList(
                "SELECT * FROM equity_transactions WHERE business_id = 1 ORDER BY posted_on, id"));
    }
}
