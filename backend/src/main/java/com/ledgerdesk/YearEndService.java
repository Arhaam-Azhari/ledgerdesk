package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class YearEndService {
    public record TemporaryBalance(String code, String name, String kind,
            BigDecimal openingBalance, BigDecimal periodBalance, BigDecimal closingBalance) {}
    public record ClosingLine(String code, String name, BigDecimal debit, BigDecimal credit) {}
    public record Preview(int year, LocalDate startsOn, LocalDate endsOn, String currency,
            ReportService.ProfitLoss profitLoss, List<TemporaryBalance> temporaryAccounts,
            List<ClosingLine> proposedLines, BigDecimal retainedEarningsChange,
            BigDecimal proposedDebits, BigDecimal proposedCredits, String accountingPeriodId,
            String bankReconciliationId, int pendingPrepaidMonths, int pendingDepreciationMonths,
            List<String> blockers, boolean ready) {}
    private final JdbcTemplate db;
    private final ReportService reports;
    public record Close(int year, String reviewNote) {}
    private final AccountingPeriodService periods;
    private final LedgerService ledger;
    private final ObjectMapper json;
    public YearEndService(JdbcTemplate db, ReportService reports, AccountingPeriodService periods, LedgerService ledger, ObjectMapper json) {
        this.db = db; this.reports = reports; this.periods = periods; this.ledger = ledger; this.json = json;
    }
    private static BigDecimal zero() { return new BigDecimal("0.00"); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(int year) {
        if (year < 1 || year > 9999) throw new IllegalArgumentException("Choose a calendar year from 1 to 9999.");
        var retained = db.queryForList("SELECT id, snapshot FROM year_end_closes WHERE business_id = 1 AND calendar_year = ?", year);
        if (!retained.isEmpty()) {
            Preview original;
            try { original = json.readValue(retained.get(0).get("snapshot").toString(), Preview.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
            return new Preview(original.year(), original.startsOn(), original.endsOn(), original.currency(),
                    original.profitLoss(), original.temporaryAccounts(), original.proposedLines(), original.retainedEarningsChange(),
                    original.proposedDebits(), original.proposedCredits(), original.accountingPeriodId(), original.bankReconciliationId(),
                    original.pendingPrepaidMonths(), original.pendingDepreciationMonths(),
                    List.of("Earnings already closed for this year. Retained close ID: " + retained.get(0).get("id")), false);
        }
        LocalDate start = LocalDate.of(year, 1, 1), end = LocalDate.of(year, 12, 31);
        var financial = reports.reports(start, end);
        var rows = db.queryForList("""
            SELECT a.code, a.name, a.kind,
                COALESCE(SUM(CASE WHEN p.entry_date < ? THEN p.debit-p.credit ELSE 0 END), 0) AS opening_balance,
                COALESCE(SUM(CASE WHEN p.entry_date >= ? THEN p.debit-p.credit ELSE 0 END), 0) AS period_balance
            FROM accounts a LEFT JOIN (
                SELECT l.account_code, l.debit, l.credit, e.entry_date
                FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                WHERE e.business_id = 1 AND e.entry_date <= ?
            ) p ON p.account_code = a.code
            WHERE a.kind IN ('REVENUE', 'EXPENSE')
            GROUP BY a.code, a.name, a.kind ORDER BY a.code
            """, start, start, end);
        var accounts = new ArrayList<TemporaryBalance>();
        var lines = new ArrayList<ClosingLine>();
        var blockers = new ArrayList<String>();
        boolean olderBalances = false;
        for (var row : rows) {
            BigDecimal opening = ((BigDecimal) row.get("opening_balance")).setScale(2);
            BigDecimal period = ((BigDecimal) row.get("period_balance")).setScale(2);
            String code = row.get("code").toString(), name = row.get("name").toString();
            accounts.add(new TemporaryBalance(code, name, row.get("kind").toString(), opening, period, opening.add(period)));
            olderBalances |= opening.signum() != 0;
            // Draft only this year's offsets. Earlier balances must be resolved first.
            if (period.signum() != 0)
                lines.add(new ClosingLine(code, name, period.signum() < 0 ? period.negate() : zero(), period.signum() > 0 ? period : zero()));
        }
        BigDecimal profit = financial.profitLoss().netProfit().setScale(2);
        if (profit.signum() != 0) {
            String name = db.queryForObject("SELECT name FROM accounts WHERE code = '3300' AND kind = 'EQUITY'", String.class);
            lines.add(new ClosingLine("3300", name, profit.signum() < 0 ? profit.negate() : zero(), profit.signum() > 0 ? profit : zero()));
        }
        BigDecimal debits = zero(), credits = zero();
        for (var line : lines) { debits = debits.add(line.debit()); credits = credits.add(line.credit()); }
        var closes = db.queryForList("SELECT id FROM accounting_period_closes WHERE business_id = 1 AND status = 'CLOSED' AND ends_on = ? ORDER BY closed_at DESC, id", end);
        var banks = db.queryForList("SELECT id FROM bank_reconciliations WHERE business_id = 1 AND status = 'CLOSED' AND ends_on = ? ORDER BY closed_at DESC, id", end);
        int prepaid = periods.pendingPrepaidMonths(end), depreciation = periods.pendingDepreciationMonths(end);
        if (olderBalances) blockers.add("Revenue or expense accounts have balances before this year. Resolve earlier earnings closes first.");
        if (closes.isEmpty()) blockers.add("Complete and retain the accounting period close at this year-end.");
        if (banks.isEmpty()) blockers.add("Close the bank reconciliation at this year-end.");
        if (prepaid > 0) blockers.add("Post due prepaid expense months through this year-end.");
        if (depreciation > 0) blockers.add("Post due depreciation months through this year-end.");
        if (financial.trialBalance().debits().compareTo(financial.trialBalance().credits()) != 0
                || financial.balanceSheet().difference().signum() != 0)
            blockers.add("Resolve the trial balance or balance sheet difference.");
        if (debits.compareTo(credits) != 0) blockers.add("Resolve the proposed closing entry difference.");
        return new Preview(year, start, end, "USD", financial.profitLoss(), List.copyOf(accounts), List.copyOf(lines),
                profit, debits, credits, closes.isEmpty() ? null : closes.get(0).get("id").toString(),
                banks.isEmpty() ? null : banks.get(0).get("id").toString(), prepaid, depreciation,
                List.copyOf(blockers), blockers.isEmpty());
    }

    @Transactional
    public String close(Close request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Year-end details are required.");
        String note = LedgerService.text(request.reviewNote(), 240, "Year-end review note");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("year-end-close", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        Preview review = preview(request.year());
        if (!review.ready()) throw new IllegalArgumentException("Resolve the year-end preview blockers before closing earnings.");
        String snapshot;
        try { snapshot = json.writeValueAsString(review); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        String id = ledger.id(), entry = null;
        if (!review.proposedLines().isEmpty()) {
            entry = ledger.id();
            // This controlled entry clears earnings after period review; ordinary backdated postings stay blocked.
            db.update("INSERT INTO journal_entries VALUES (?, 1, ?, ?, ?)", entry, review.endsOn(), "Earnings close for " + request.year(), id);
            for (var line : review.proposedLines())
                db.update("INSERT INTO journal_lines VALUES (?, ?, ?, ?, ?)", ledger.id(), entry, line.code(), line.debit(), line.credit());
        }
        db.update("""
            INSERT INTO year_end_closes (id, business_id, calendar_year, starts_on, ends_on, entry_id,
                accounting_period_id, bank_reconciliation_id, review_note, snapshot, closed_by, closed_at)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, request.year(), review.startsOn(), review.endsOn(), entry, review.accountingPeriodId(),
                review.bankReconciliationId(), note, snapshot, actor, LocalDateTime.now());
        ledger.complete(key, hash, id, actor, "YEAR_END_CLOSED");
        return id;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> history() {
        return Map.of("yearEndCloses", db.queryForList("SELECT * FROM year_end_closes WHERE business_id = 1 ORDER BY calendar_year DESC, id"));
    }

}
