package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpeningBankBalance {
    public record Opening(LocalDate asOf, String balance, String memo) {}
    private final JdbcTemplate db;
    private final LedgerService ledger;
    public OpeningBankBalance(JdbcTemplate db, LedgerService ledger) { this.db = db; this.ledger = ledger; }

    @Transactional
    public String post(Opening request, String key, String actor) {
        if (request == null || request.asOf() == null || request.asOf().getYear() < 1
                || request.asOf().isAfter(LocalDate.of(9999, 12, 30)))
            throw new IllegalArgumentException("Choose a valid opening date before the final supported day.");
        if (request.balance() == null || !request.balance().matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Enter a nonnegative opening balance with at most two decimal places.");
        BigDecimal balance = new BigDecimal(request.balance()).setScale(2);
        String memo = LedgerService.text(request.memo(), 240, "Opening balance note");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("opening-bank-balance", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        if (db.queryForObject("SELECT COUNT(*) FROM opening_bank_balances WHERE business_id = 1", Integer.class) != 0)
            throw new IllegalArgumentException("An opening bank balance is already recorded.");
        int existing = db.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE business_id = 1", Integer.class)
                + db.queryForObject("SELECT COUNT(*) FROM bank_imports WHERE business_id = 1", Integer.class)
                + db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations WHERE business_id = 1", Integer.class);
        if (existing != 0) throw new IllegalArgumentException("Record the opening bank balance before journal postings, bank imports or reconciliation.");
        String id = ledger.id();
        // The carried balance is equity, not a sale or an owner transfer today.
        if (balance.signum() > 0)
            ledger.journal(id, request.asOf(), memo, "1000", "3200", balance);
        db.update("INSERT INTO opening_bank_balances VALUES (?, 1, ?, ?, ?)", id, request.asOf(), balance, memo);
        ledger.complete(key, hash, id, actor, "OPENING_BANK_BALANCE_RECORDED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("openingBankBalances", db.queryForList("SELECT * FROM opening_bank_balances WHERE business_id = 1"));
    }
}
