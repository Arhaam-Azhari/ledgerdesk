package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private final JdbcTemplate db;
    public record Account(String code, String name, String kind, BigDecimal amount) {}
    public record ProfitLoss(List<Account> accounts, BigDecimal revenue, BigDecimal expenses, BigDecimal netProfit) {}
    public record TrialRow(String code, String name, BigDecimal debit, BigDecimal credit) {}
    public record TrialBalance(List<TrialRow> accounts, BigDecimal debits, BigDecimal credits) {}
    public record BalanceSheet(List<Account> assets, List<Account> liabilities, List<Account> equityAccounts,
            BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal postedEquity,
            BigDecimal accumulatedEarnings, BigDecimal totalEquity, BigDecimal liabilitiesAndEquity, BigDecimal difference) {}
    public record AgingItem(String id, String reference, String party, LocalDate dueOn, long daysOverdue, String bucket, BigDecimal outstanding) {}
    public record Aging(List<AgingItem> items, Map<String, BigDecimal> buckets, BigDecimal total) {}
    public record Reports(LocalDate startsOn, LocalDate endsOn, ProfitLoss profitLoss, TrialBalance trialBalance, BalanceSheet balanceSheet, Aging receivables, Aging payables) {}
    public record ProfitPeriod(LocalDate startsOn, LocalDate endsOn, ProfitLoss profitLoss) {}
    public record ProfitComparison(ProfitPeriod current, ProfitPeriod previous, ProfitLoss change) {}
    public ReportService(JdbcTemplate db) { this.db = db; }
    private static BigDecimal zero() { return new BigDecimal("0.00"); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Reports reports(LocalDate startsOn, LocalDate endsOn) {
        validatePeriod(startsOn, endsOn);
        var profit = profitLoss(startsOn, endsOn);
        var balances = db.queryForList("""
            SELECT a.code, a.name, a.kind, COALESCE(SUM(cash.debit-cash.credit), 0) AS balance
            FROM accounts a LEFT JOIN (
                SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                WHERE e.business_id = 1 AND e.entry_date <= ?
            ) cash ON cash.account_code = a.code
            GROUP BY a.code, a.name, a.kind ORDER BY a.code
            """, endsOn);
        var trial = new ArrayList<TrialRow>();
        BigDecimal debits = zero(), credits = zero();
        for (var row : balances) {
            BigDecimal balance = ((BigDecimal) row.get("balance")).setScale(2);
            BigDecimal debit = balance.signum() > 0 ? balance : zero();
            BigDecimal credit = balance.signum() < 0 ? balance.negate() : zero();
            trial.add(new TrialRow(row.get("code").toString(), row.get("name").toString(), debit, credit));
            debits = debits.add(debit); credits = credits.add(credit);
        }
        return new Reports(startsOn, endsOn, profit,
                new TrialBalance(trial, debits, credits), balanceSheet(balances), aging(endsOn, true), aging(endsOn, false));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProfitComparison compareProfit(LocalDate startsOn, LocalDate endsOn,
            LocalDate previousStartsOn, LocalDate previousEndsOn) {
        validatePeriod(startsOn, endsOn);
        validatePeriod(previousStartsOn, previousEndsOn);
        if (!previousEndsOn.isBefore(startsOn))
            throw new IllegalArgumentException("The previous period must end before the current period starts.");
        // Both periods read the same database snapshot, even while another user posts work.
        var current = profitLoss(startsOn, endsOn);
        var previous = profitLoss(previousStartsOn, previousEndsOn);
        var previousAmounts = new java.util.HashMap<String, BigDecimal>();
        for (var account : previous.accounts()) previousAmounts.put(account.code(), account.amount());
        var changes = new ArrayList<Account>();
        for (var account : current.accounts())
            changes.add(new Account(account.code(), account.name(), account.kind(),
                    account.amount().subtract(previousAmounts.getOrDefault(account.code(), zero()))));
        var change = new ProfitLoss(changes, current.revenue().subtract(previous.revenue()),
                current.expenses().subtract(previous.expenses()), current.netProfit().subtract(previous.netProfit()));
        return new ProfitComparison(new ProfitPeriod(startsOn, endsOn, current),
                new ProfitPeriod(previousStartsOn, previousEndsOn, previous), change);
    }

    private void validatePeriod(LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || startsOn.getYear() < 1 || endsOn.getYear() > 9999 || startsOn.isAfter(endsOn))
            throw new IllegalArgumentException("Choose a valid report period, with the start on or before the end.");
    }

    private ProfitLoss profitLoss(LocalDate startsOn, LocalDate endsOn) {
        var period = db.queryForList("""
            SELECT a.code, a.name, a.kind, COALESCE(SUM(cash.debit-cash.credit), 0) AS balance
            FROM accounts a LEFT JOIN (
                SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                WHERE e.business_id = 1 AND e.entry_date BETWEEN ? AND ?
            ) cash ON cash.account_code = a.code
            WHERE a.kind IN ('REVENUE', 'EXPENSE') GROUP BY a.code, a.name, a.kind ORDER BY a.code
            """, startsOn, endsOn);
        var accounts = new ArrayList<Account>();
        BigDecimal revenue = zero(), expenses = zero();
        for (var row : period) {
            boolean income = row.get("kind").equals("REVENUE");
            BigDecimal amount = (BigDecimal) row.get("balance");
            if (income) { amount = amount.negate(); revenue = revenue.add(amount); }
            else expenses = expenses.add(amount);
            accounts.add(new Account(row.get("code").toString(), row.get("name").toString(), row.get("kind").toString(), amount.setScale(2)));
        }
        return new ProfitLoss(accounts, revenue, expenses, revenue.subtract(expenses));
    }

    private BalanceSheet balanceSheet(List<Map<String, Object>> balances) {
        var assets = new ArrayList<Account>();
        var liabilities = new ArrayList<Account>();
        var equity = new ArrayList<Account>();
        BigDecimal totalAssets = zero(), totalLiabilities = zero(), postedEquity = zero(), earnings = zero();
        for (var row : balances) {
            String kind = row.get("kind").toString();
            BigDecimal debitBalance = ((BigDecimal) row.get("balance")).setScale(2);
            BigDecimal amount = kind.equals("LIABILITY") || kind.equals("EQUITY") ? debitBalance.negate() : debitBalance;
            var account = new Account(row.get("code").toString(), row.get("name").toString(), kind, amount);
            switch (kind) {
                case "ASSET" -> { assets.add(account); totalAssets = totalAssets.add(amount); }
                case "LIABILITY" -> { liabilities.add(account); totalLiabilities = totalLiabilities.add(amount); }
                case "EQUITY" -> { equity.add(account); postedEquity = postedEquity.add(amount); }
                // Include every dated income/expense entry, not just the selected profit period.
                case "REVENUE", "EXPENSE" -> earnings = earnings.subtract(debitBalance);
                default -> { }
            }
        }
        BigDecimal totalEquity = postedEquity.add(earnings);
        BigDecimal rightSide = totalLiabilities.add(totalEquity);
        return new BalanceSheet(assets, liabilities, equity, totalAssets, totalLiabilities, postedEquity,
                earnings, totalEquity, rightSide, totalAssets.subtract(rightSide));
    }

    private Aging aging(LocalDate asOf, boolean receivables) {
        // Current paid/status fields cannot describe an earlier date. Read dated postings and payments instead.
        String sql = receivables ? """
            SELECT i.id, n.number_value, c.name AS party, i.due_on,
                COALESCE((SELECT SUM(l.debit-l.credit) FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                    WHERE e.business_id = 1 AND e.source_id = i.id AND l.account_code = '1100' AND e.entry_date <= ?), 0)
                - COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.invoice_id = i.id AND p.paid_on <= ?), 0) AS outstanding
            FROM invoices i JOIN invoice_numbers n ON n.invoice_id = i.id JOIN customers c ON c.id = i.customer_id
            WHERE i.business_id = 1 AND i.issued_on <= ? ORDER BY i.due_on, i.id
            """ : """
            SELECT b.id, b.reference, v.name AS party, b.due_on,
                COALESCE((SELECT SUM(l.credit-l.debit) FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                    WHERE e.business_id = 1 AND e.source_id = b.id AND l.account_code = '2000' AND e.entry_date <= ?), 0)
                - COALESCE((SELECT SUM(p.amount) FROM bill_payments p WHERE p.bill_id = b.id AND p.paid_on <= ?), 0) AS outstanding
            FROM bills b JOIN vendors v ON v.id = b.vendor_id
            WHERE b.business_id = 1 AND b.issued_on <= ? ORDER BY b.due_on, b.id
            """;
        var buckets = new java.util.LinkedHashMap<String, BigDecimal>();
        for (String bucket : List.of("Current", "1–30 days", "31–60 days", "61–90 days", "Over 90 days")) buckets.put(bucket, zero());
        var items = new ArrayList<AgingItem>();
        BigDecimal total = zero();
        for (var row : db.queryForList(sql, asOf, asOf, asOf)) {
            BigDecimal outstanding = ((BigDecimal) row.get("outstanding")).setScale(2);
            if (outstanding.signum() <= 0) continue;
            LocalDate due = ((java.sql.Date) row.get("due_on")).toLocalDate();
            long days = Math.max(0, ChronoUnit.DAYS.between(due, asOf));
            String bucket = days == 0 ? "Current" : days <= 30 ? "1–30 days" : days <= 60 ? "31–60 days" : days <= 90 ? "61–90 days" : "Over 90 days";
            String reference = receivables ? LedgerService.invoiceNumber(((Number) row.get("number_value")).longValue()) : row.get("reference").toString();
            items.add(new AgingItem(row.get("id").toString(), reference, row.get("party").toString(), due, days, bucket, outstanding));
            buckets.put(bucket, buckets.get(bucket).add(outstanding)); total = total.add(outstanding);
        }
        return new Aging(items, buckets, total);
    }
}
