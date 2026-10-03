package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashActivityService {
    public record Movement(String lineId, String entryId, String sourceId, LocalDate postedOn,
            String memo, String category, BigDecimal receipt, BigDecimal payment) {}
    public record Category(String code, BigDecimal receipts, BigDecimal payments, BigDecimal net) {}
    public record Activity(LocalDate startsOn, LocalDate endsOn, BigDecimal openingCash,
            BigDecimal receipts, BigDecimal payments, BigDecimal netChange, BigDecimal closingCash,
            BigDecimal difference, List<Category> categories, List<Movement> movements) {}
    private final JdbcTemplate db;
    public CashActivityService(JdbcTemplate db) { this.db = db; }
    private static BigDecimal zero() { return new BigDecimal("0.00"); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Activity report(LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || startsOn.getYear() < 1 || endsOn.getYear() > 9999 || startsOn.isAfter(endsOn))
            throw new IllegalArgumentException("Choose a valid cash activity period, with the start on or before the end.");
        if (db.queryForObject("SELECT COUNT(*) FROM opening_bank_balances WHERE business_id = 1 AND as_of >= ?", Integer.class, startsOn) != 0)
            throw new IllegalArgumentException("Start cash activity after the opening balance date.");
        BigDecimal opening = balance(startsOn, false), closing = balance(endsOn, true);
        var rows = db.queryForList("""
            SELECT l.id AS line_id, e.id AS entry_id, e.source_id, e.entry_date, e.memo,
                   l.debit, l.credit, c.counterpart, c.kind, c.account_count
            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
            LEFT JOIN (
                SELECT j.entry_id, MIN(j.account_code) AS counterpart, MIN(a.kind) AS kind,
                       COUNT(DISTINCT j.account_code) AS account_count
                FROM journal_lines j JOIN accounts a ON a.code = j.account_code
                WHERE j.account_code <> '1000' GROUP BY j.entry_id
            ) c ON c.entry_id = e.id
            WHERE e.business_id = 1 AND l.account_code = '1000' AND e.entry_date BETWEEN ? AND ?
            ORDER BY e.entry_date, e.id, l.id
            """, startsOn, endsOn);
        var movements = new ArrayList<Movement>();
        BigDecimal receipts = zero(), payments = zero();
        for (var row : rows) {
            String category = "OTHER";
            if (row.get("account_count") instanceof Number count && count.intValue() == 1) {
                String code = row.get("counterpart").toString();
                category = switch (code) {
                    case "1100" -> "CUSTOMERS";
                    case "2000" -> "SUPPLIERS";
                    case "3000", "3100" -> "OWNER";
                    default -> "EXPENSE".equals(row.get("kind")) ? "DIRECT_PURCHASES" : "OTHER";
                };
            }
            BigDecimal receipt = ((BigDecimal) row.get("debit")).setScale(2);
            BigDecimal payment = ((BigDecimal) row.get("credit")).setScale(2);
            receipts = receipts.add(receipt); payments = payments.add(payment);
            movements.add(new Movement(row.get("line_id").toString(), row.get("entry_id").toString(),
                    row.get("source_id").toString(), ((java.sql.Date) row.get("entry_date")).toLocalDate(),
                    row.get("memo").toString(), category, receipt, payment));
        }
        var categories = new ArrayList<Category>();
        for (String code : List.of("CUSTOMERS", "SUPPLIERS", "DIRECT_PURCHASES", "OWNER", "OTHER")) {
            BigDecimal incoming = zero(), outgoing = zero();
            for (var movement : movements) if (movement.category().equals(code)) {
                incoming = incoming.add(movement.receipt()); outgoing = outgoing.add(movement.payment());
            }
            categories.add(new Category(code, incoming, outgoing, incoming.subtract(outgoing)));
        }
        BigDecimal net = receipts.subtract(payments);
        return new Activity(startsOn, endsOn, opening, receipts, payments, net, closing,
                opening.add(net).subtract(closing), categories, movements);
    }

    private BigDecimal balance(LocalDate cutoff, boolean inclusive) {
        // Read the ledger independently so the report can expose a reconciliation difference.
        return db.queryForObject("SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l "
                + "JOIN journal_entries e ON e.id=l.entry_id WHERE e.business_id=1 AND l.account_code='1000' "
                + "AND e.entry_date " + (inclusive ? "<= ?" : "< ?"), BigDecimal.class, cutoff).setScale(2);
    }
}
