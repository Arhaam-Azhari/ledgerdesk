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
    private final LedgerService ledger;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public record Reopen(long version, String reason) {}
    public record Statement(LocalDate startsOn, LocalDate endsOn, String openingBalance, String closingBalance) {}
    public record Preview(BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal importedMovement,
            BigDecimal statementDifference, BigDecimal bookBalance, BigDecimal outstandingDeposits,
            BigDecimal outstandingPayments, BigDecimal adjustedBankBalance, BigDecimal bookDifference,
            List<Map<String, Object>> unmatchedTransactions, List<Map<String, Object>> outstandingEntries,
            List<Map<String, Object>> futureDatedMatches) {}

    public BankReconciliation(JdbcTemplate db, LedgerService ledger, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.db = db; this.ledger = ledger; this.json = json;
    }

    @Transactional
    public String close(Statement statement, String key, String actor) {
        ledger.lockBusiness();
        if (statement == null) throw new IllegalArgumentException("Statement details are required.");
        String hash = ledger.fingerprint(List.of("reconciliation-close", statement));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        // Recalculate under the same lock used by postings, imports and matches.
        Preview result = preview(statement);
        var closed = db.queryForList("SELECT * FROM bank_reconciliations WHERE business_id = 1 AND status = 'CLOSED' ORDER BY ends_on DESC");
        if (closed.isEmpty()) {
            var openings = db.queryForList("SELECT * FROM opening_bank_balances WHERE business_id = 1");
            if (openings.isEmpty()) {
                if (result.openingBalance().signum() != 0)
                    throw new IllegalArgumentException("The first statement must start from zero or use a recorded opening bank balance.");
            } else {
                var opening = openings.get(0);
                LocalDate firstDay = ((java.sql.Date) opening.get("as_of")).toLocalDate().plusDays(1);
                if (!statement.startsOn().equals(firstDay) || result.openingBalance().compareTo((BigDecimal) opening.get("balance")) != 0)
                    throw new IllegalArgumentException("Start the day after the opening balance date and carry its exact bank balance.");
            }
            int earlier = db.queryForObject("SELECT COUNT(*) FROM bank_transactions WHERE business_id = 1 AND posted_on < ?", Integer.class, statement.startsOn())
                    + db.queryForObject("""
                        SELECT COUNT(*) FROM journal_entries e WHERE e.business_id = 1 AND e.entry_date < ?
                        AND NOT EXISTS (SELECT 1 FROM opening_bank_balances o WHERE o.id = e.source_id AND o.business_id = 1)
                        AND NOT EXISTS (SELECT 1 FROM opening_book_invoices i JOIN opening_book_imports o ON o.id = i.opening_books_id
                            WHERE i.entry_id = e.id AND o.business_id = e.business_id AND o.as_of = e.entry_date)
                        AND NOT EXISTS (SELECT 1 FROM opening_book_bills b JOIN opening_book_imports o ON o.id = b.opening_books_id
                            WHERE b.entry_id = e.id AND o.business_id = e.business_id AND o.as_of = e.entry_date)
                        """, Integer.class, statement.startsOn());
            if (earlier != 0) throw new IllegalArgumentException("The first statement must include the beginning of the recorded books and bank history.");
        } else {
            var latest = closed.get(0);
            LocalDate next = ((java.sql.Date) latest.get("ends_on")).toLocalDate().plusDays(1);
            if (!statement.startsOn().equals(next) || result.openingBalance().compareTo((BigDecimal) latest.get("closing_balance")) != 0)
                throw new IllegalArgumentException("Start the day after the latest closed statement and carry forward its closing balance.");
        }
        if (result.statementDifference().signum() != 0 || result.bookDifference().signum() != 0
                || !result.unmatchedTransactions().isEmpty() || !result.futureDatedMatches().isEmpty())
            throw new IllegalArgumentException("Resolve statement differences, unmatched rows and future-dated matches before closing.");
        String snapshot;
        try { snapshot = json.writeValueAsString(result); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        String id = ledger.id();
        db.update("INSERT INTO bank_reconciliations (id, business_id, account_code, starts_on, ends_on, opening_balance, closing_balance, snapshot, status, closed_at, closed_by) VALUES (?, 1, '1000', ?, ?, ?, ?, ?, 'CLOSED', ?, ?)",
                id, statement.startsOn(), statement.endsOn(), result.openingBalance(), result.closingBalance(), snapshot, java.time.LocalDateTime.now(), actor);
        ledger.complete(key, hash, id, actor, "BANK_RECONCILIATION_CLOSED");
        return id;
    }

    @Transactional
    public String reopen(String id, Reopen request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("A version and reason are required.");
        String reason = LedgerService.text(request.reason(), 240, "Reopening reason");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("reconciliation-reopen", id, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var closed = db.queryForList("SELECT * FROM bank_reconciliations WHERE business_id = 1 AND status = 'CLOSED' ORDER BY ends_on DESC");
        if (closed.isEmpty() || !closed.get(0).get("id").equals(id)
                || ((Number) closed.get(0).get("version")).longValue() != request.version())
            throw new IllegalArgumentException("Only the latest closed reconciliation can be reopened. Reload the workspace.");
        if (db.queryForObject("SELECT COUNT(*) FROM accounting_period_closes WHERE business_id = 1 AND status = 'CLOSED' AND ends_on >= ?", Integer.class, closed.get(0).get("starts_on")) != 0)
            throw new IllegalArgumentException("Reopen the affected accounting period before reopening its bank statement.");
        db.update("UPDATE bank_reconciliations SET status = 'REOPENED', version = version + 1, reopened_at = ?, reopened_by = ?, reopen_reason = ? WHERE id = ?",
                java.time.LocalDateTime.now(), actor, reason, id);
        ledger.complete(key, hash, id, actor, "BANK_RECONCILIATION_REOPENED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("bankReconciliations", db.queryForList("SELECT * FROM bank_reconciliations WHERE business_id = 1 ORDER BY closed_at DESC, id"));
    }

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
            SELECT l.id AS line_id, e.entry_date, e.memo, l.debit-l.credit AS amount,
                cash.invoice_number, cash.customer_name, cash.invoice_description,
                cash.bill_reference, cash.bill_vendor, cash.expense_description, cash.expense_vendor,
                cash.equity_kind, cash.equity_memo, cash.kind
            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
            LEFT JOIN (%s) cash ON cash.line_id = l.id
            WHERE e.business_id = 1 AND l.account_code = '1000' AND e.entry_date <= ?
              AND NOT EXISTS (SELECT 1 FROM opening_bank_balances o WHERE o.id = e.source_id AND o.business_id = 1)
              AND NOT EXISTS (SELECT 1 FROM bank_matches m JOIN bank_transactions t ON t.id = m.transaction_id
                  WHERE m.line_id = l.id AND t.posted_on <= ?)
            ORDER BY e.entry_date, l.id
            """.formatted(BankMatching.CASH_RECORDS), statement.endsOn(), statement.endsOn());
        outstanding.forEach(entry -> { if (entry.get("kind") != null) BankMatching.describe(entry); });
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
