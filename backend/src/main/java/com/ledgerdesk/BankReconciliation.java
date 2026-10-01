package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankReconciliation {
    private final JdbcTemplate db;
    public record Statement(LocalDate startsOn, LocalDate endsOn, String openingBalance, String closingBalance) {}
    public record Preview(BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal importedMovement,
            BigDecimal statementDifference, BigDecimal bookBalance, BigDecimal outstandingDeposits,
            BigDecimal outstandingPayments, BigDecimal adjustedBankBalance, BigDecimal bookDifference,
            List<Map<String, Object>> unmatchedTransactions, List<Map<String, Object>> outstandingEntries,
            List<Map<String, Object>> futureDatedMatches) {}

    public BankReconciliation(JdbcTemplate db) { this.db = db; }

    private static BigDecimal balance(String value) {
        if (value == null || !value.matches("-?[0-9]{1,12}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Enter a balance with at most two decimal places.");
        return new BigDecimal(value).setScale(2);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Statement statement) {
        if (statement == null || statement.startsOn() == null || statement.endsOn() == null
                || statement.startsOn().getYear() < 1 || statement.endsOn().getYear() > 9999
                || statement.startsOn().isAfter(statement.endsOn()))
            throw new IllegalArgumentException("Choose a valid statement period, with the start on or before the end.");
        BigDecimal opening = balance(statement.openingBalance()), closing = balance(statement.closingBalance());
        BigDecimal movement = db.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM bank_transactions WHERE business_id = 1 AND account_code = '1000' AND posted_on BETWEEN ? AND ?",
                BigDecimal.class, statement.startsOn(), statement.endsOn());
        BigDecimal books = db.queryForObject("SELECT COALESCE(SUM(l.debit-l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.business_id = 1 AND l.account_code = '1000' AND e.entry_date <= ?",
                BigDecimal.class, statement.endsOn());
        // A payment cleared next month is still outstanding at this statement's end.
        var outstanding = db.queryForList("""
            SELECT l.id AS line_id, e.entry_date, e.memo, l.debit-l.credit AS amount
            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
            WHERE e.business_id = 1 AND l.account_code = '1000' AND e.entry_date <= ?
              AND NOT EXISTS (SELECT 1 FROM bank_matches m JOIN bank_transactions t ON t.id = m.transaction_id
                  WHERE m.line_id = l.id AND t.posted_on <= ?)
            ORDER BY e.entry_date, l.id
            """, statement.endsOn(), statement.endsOn());
        BigDecimal deposits = new BigDecimal("0.00"), payments = new BigDecimal("0.00");
        for (var entry : outstanding) {
            BigDecimal amount = (BigDecimal) entry.get("amount");
            if (amount.signum() > 0) deposits = deposits.add(amount);
            else payments = payments.subtract(amount);
        }
        // Include older unmatched rows too, so selecting a later start cannot hide them.
        var unmatched = db.queryForList("""
            SELECT t.* FROM bank_transactions t WHERE t.business_id = 1 AND t.account_code = '1000'
                AND t.posted_on <= ? AND NOT EXISTS (SELECT 1 FROM bank_matches m WHERE m.transaction_id = t.id)
            ORDER BY t.posted_on, t.id
            """, statement.endsOn());
        var future = db.queryForList("""
            SELECT t.id AS transaction_id, t.posted_on, l.id AS line_id, e.entry_date, t.amount
            FROM bank_transactions t JOIN bank_matches m ON m.transaction_id = t.id
            JOIN journal_lines l ON l.id = m.line_id JOIN journal_entries e ON e.id = l.entry_id
            WHERE t.business_id = 1 AND t.account_code = '1000' AND t.posted_on <= ? AND e.entry_date > ?
            ORDER BY t.posted_on, t.id
            """, statement.endsOn(), statement.endsOn());
        BigDecimal adjusted = closing.add(deposits).subtract(payments);
        return new Preview(opening, closing, movement, closing.subtract(opening.add(movement)), books,
                deposits, payments, adjusted, adjusted.subtract(books), unmatched, outstanding, future);
    }
}
