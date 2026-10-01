package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankService {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    private final BankCsv parser;
    public record Import(String label, String csv) {}
    public record PreviewRow(BankCsv.Row transaction, boolean duplicate) {}
    public record Preview(List<PreviewRow> rows, int added, int duplicates) {}

    public BankService(JdbcTemplate db, LedgerService ledger, BankCsv parser) {
        this.db = db; this.ledger = ledger; this.parser = parser;
    }

    private Preview inspect(List<BankCsv.Row> rows) {
        var preview = new ArrayList<PreviewRow>();
        int duplicates = 0;
        for (var row : rows) {
            var existing = db.queryForList("SELECT posted_on, description, amount FROM bank_transactions WHERE business_id = 1 AND account_code = '1000' AND external_id = ?", row.transactionId());
            boolean duplicate = !existing.isEmpty();
            if (duplicate) {
                var original = existing.get(0);
                if (!((java.sql.Date) original.get("posted_on")).toLocalDate().equals(row.date())
                        || !original.get("description").equals(row.description())
                        || ((BigDecimal) original.get("amount")).compareTo(row.amount()) != 0)
                    throw new IllegalArgumentException("Transaction ID " + row.transactionId() + " is already imported with different details. Nothing was imported.");
                duplicates++;
            }
            preview.add(new PreviewRow(row, duplicate));
        }
        return new Preview(List.copyOf(preview), rows.size() - duplicates, duplicates);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Preview preview(Import request) {
        if (request == null) throw new IllegalArgumentException("Import details are required.");
        LedgerService.text(request.label(), 120, "Import label");
        return inspect(parser.parse(request.csv()));
    }

    @Transactional
    public String importCsv(Import request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Import details are required.");
        String label = LedgerService.text(request.label(), 120, "Import label");
        var rows = parser.parse(request.csv());
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("bank-import", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        // Recheck after taking the write lock: another tab may have imported since preview.
        var preview = inspect(rows);
        String id = ledger.id();
        db.update("INSERT INTO bank_imports VALUES (?, 1, '1000', ?, ?, ?, ?)", id, label, LocalDateTime.now(), preview.added(), preview.duplicates());
        for (var row : preview.rows()) {
            if (row.duplicate()) continue;
            var transaction = row.transaction();
            ledger.requireOpenDate(transaction.date());
            db.update("INSERT INTO bank_transactions VALUES (?, 1, '1000', ?, ?, ?, ?, ?)", ledger.id(), id,
                    transaction.transactionId(), transaction.date(), transaction.description(), transaction.amount());
        }
        // A bank statement is evidence for matching, not permission to post new accounting entries.
        ledger.complete(key, hash, id, actor, "BANK_CSV_IMPORTED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("bankImports", db.queryForList("SELECT * FROM bank_imports WHERE business_id = 1 ORDER BY imported_at DESC, id"),
                "bankTransactions", db.queryForList("SELECT t.*, i.label AS import_label FROM bank_transactions t JOIN bank_imports i ON i.id = t.import_id WHERE t.business_id = 1 ORDER BY posted_on DESC, t.id"));
    }
}
