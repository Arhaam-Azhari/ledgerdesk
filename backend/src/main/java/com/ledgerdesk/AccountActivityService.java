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
public class AccountActivityService {
    public record Account(String code, String name, String kind) {}
    public record Movement(String entryId, String lineId, String sourceId, LocalDate postedOn,
            String memo, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}
    public record Activity(Account account, String currency, LocalDate startsOn, LocalDate endsOn,
            BigDecimal openingBalance, BigDecimal debits, BigDecimal credits, BigDecimal closingBalance,
            List<Movement> movements) {}
    private final JdbcTemplate db;
    public AccountActivityService(JdbcTemplate db) { this.db = db; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Activity activity(String code, LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || startsOn.getYear() < 1 || endsOn.getYear() > 9999 || startsOn.isAfter(endsOn))
            throw new IllegalArgumentException("Choose a valid account activity period, with the start on or before the end.");
        var accounts = db.query("SELECT code, name, kind FROM accounts WHERE code = ?",
                (row, index) -> new Account(row.getString("code"), row.getString("name"), row.getString("kind")), code);
        if (accounts.isEmpty()) throw new IllegalArgumentException("Account not found.");
        BigDecimal opening = db.queryForObject("""
            SELECT COALESCE(SUM(l.debit-l.credit), 0)
            FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
            WHERE e.business_id = 1 AND l.account_code = ? AND e.entry_date < ?
            """, BigDecimal.class, code, startsOn).setScale(2);
        var rows = db.queryForList("""
            SELECT e.id AS entry_id, l.id AS line_id, e.source_id, e.entry_date, e.memo, l.debit, l.credit
            FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
            WHERE e.business_id = 1 AND l.account_code = ? AND e.entry_date BETWEEN ? AND ?
            ORDER BY e.entry_date, e.id, l.id
            """, code, startsOn, endsOn);
        BigDecimal balance = opening, debits = new BigDecimal("0.00"), credits = new BigDecimal("0.00");
        var movements = new ArrayList<Movement>();
        // One signed convention works for debit and credit accounts, including unusual balances.
        for (var row : rows) {
            BigDecimal debit = ((BigDecimal) row.get("debit")).setScale(2);
            BigDecimal credit = ((BigDecimal) row.get("credit")).setScale(2);
            debits = debits.add(debit); credits = credits.add(credit);
            balance = balance.add(debit).subtract(credit);
            movements.add(new Movement((String) row.get("entry_id"), (String) row.get("line_id"),
                    (String) row.get("source_id"), ((java.sql.Date) row.get("entry_date")).toLocalDate(),
                    (String) row.get("memo"), debit, credit, balance));
        }
        return new Activity(accounts.get(0), "USD", startsOn, endsOn, opening, debits, credits, balance, List.copyOf(movements));
    }
}
