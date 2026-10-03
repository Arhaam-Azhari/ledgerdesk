package com.ledgerdesk;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AccountingPeriodService {
    public record Close(LocalDate endsOn, String reviewNote) {}
    public record Reopen(int version, String reason) {}
    public record Preview(LocalDate startsOn, LocalDate endsOn, String bankReconciliationId,
            int pendingPrepaidMonths, int pendingDepreciationMonths, boolean ready,
            ReportService.Reports reports, CashActivityService.Activity cashActivity) {}
    private final JdbcTemplate db;
    private final LedgerService ledger;
    private final ReportService reports;
    private final CashActivityService cash;
    private final ObjectMapper json;
    public AccountingPeriodService(JdbcTemplate db, LedgerService ledger, ReportService reports,
            CashActivityService cash, ObjectMapper json) {
        this.db = db; this.ledger = ledger; this.reports = reports; this.cash = cash; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(LocalDate end) {
        if (end == null || end.getYear() < 1 || end.getYear() > 9999 || end.getDayOfMonth() != end.lengthOfMonth())
            throw new IllegalArgumentException("Choose a supported month-end date.");
        var closed = latest();
        LocalDate start;
        if (!closed.isEmpty()) {
            LocalDate previous = ((java.sql.Date) closed.get(0).get("ends_on")).toLocalDate();
            if (!end.isAfter(previous)) throw new IllegalArgumentException("Choose a month-end after the latest closed accounting period.");
            start = previous.plusDays(1);
        } else {
            var opening = db.queryForList("SELECT as_of FROM opening_bank_balances WHERE business_id = 1");
            if (!opening.isEmpty()) start = ((java.sql.Date) opening.get(0).get("as_of")).toLocalDate().plusDays(1);
            else {
                var first = db.queryForObject("SELECT MIN(entry_date) FROM journal_entries WHERE business_id = 1", java.sql.Date.class);
                start = first == null ? end.withDayOfMonth(1) : first.toLocalDate();
            }
            if (start.isAfter(end)) throw new IllegalArgumentException("The period must include the beginning of the recorded books.");
        }
        var bank = db.queryForList("SELECT id FROM bank_reconciliations WHERE business_id = 1 AND status = 'CLOSED' AND ends_on = ? ORDER BY closed_at DESC", end);
        int prepaid = db.queryForObject("""
            SELECT COUNT(*) FROM prepaid_periods r JOIN prepaid_plans p ON p.id = r.plan_id
            WHERE p.business_id = 1 AND p.funded_on <= ? AND r.period_on <= ? AND r.entry_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM prepaid_corrections c WHERE c.plan_id = p.id AND c.corrected_on <= ?)
            AND NOT EXISTS (SELECT 1 FROM prepaid_cancellations c WHERE c.plan_id = p.id AND c.cancelled_on <= ?)
            """, Integer.class, end, end, end, end);
        int depreciation = db.queryForObject("""
            SELECT COUNT(*) FROM asset_periods r JOIN fixed_assets a ON a.id = r.asset_id
            WHERE a.business_id = 1 AND a.funded_on <= ? AND r.period_on <= ? AND r.entry_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM asset_corrections c WHERE c.asset_id = a.id AND c.corrected_on <= ?)
            AND NOT EXISTS (SELECT 1 FROM asset_retirements r WHERE r.asset_id = a.id AND r.retired_on <= ?)
            """, Integer.class, end, end, end, end);
        var financial = reports.reports(start, end);
        var activity = cash.report(start, end);
        boolean balanced = financial.balanceSheet().difference().signum() == 0
                && financial.trialBalance().debits().compareTo(financial.trialBalance().credits()) == 0
                && activity.difference().signum() == 0;
        return new Preview(start, end, bank.isEmpty() ? null : bank.get(0).get("id").toString(),
                prepaid, depreciation, !bank.isEmpty() && prepaid == 0 && depreciation == 0 && balanced, financial, activity);
    }

    @Transactional
    public String close(Close request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Period details are required.");
        String note = LedgerService.text(request.reviewNote(), 240, "Period review note");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("accounting-period-close", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        // Recheck the reports and outstanding schedule work while postings are locked.
        Preview review = preview(request.endsOn());
        if (!review.ready()) throw new IllegalArgumentException("Close the bank statement at this month-end, finish due prepaid/depreciation months and resolve report differences first.");
        String snapshot;
        try { snapshot = json.writeValueAsString(review); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        String id = ledger.id();
        db.update("INSERT INTO accounting_period_closes (id, business_id, starts_on, ends_on, review_note, snapshot, status, closed_by, closed_at) VALUES (?, 1, ?, ?, ?, ?, 'CLOSED', ?, ?)",
                id, review.startsOn(), review.endsOn(), note, snapshot, actor, LocalDateTime.now());
        ledger.complete(key, hash, id, actor, "ACCOUNTING_PERIOD_CLOSED");
        return id;
    }

    @Transactional
    public String reopen(String id, Reopen request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("A version and reason are required.");
        String reason = LedgerService.text(request.reason(), 240, "Period reopening reason");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("accounting-period-reopen", id, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var closed = latest();
        if (closed.isEmpty() || !closed.get(0).get("id").equals(id)
                || ((Number) closed.get(0).get("version")).intValue() != request.version())
            throw new IllegalArgumentException("Only the latest closed accounting period can be reopened. Reload before trying again.");
        db.update("UPDATE accounting_period_closes SET status = 'REOPENED', version = version + 1, reopened_by = ?, reopened_at = ?, reopen_reason = ? WHERE id = ?",
                actor, LocalDateTime.now(), reason, id);
        ledger.complete(key, hash, id, actor, "ACCOUNTING_PERIOD_REOPENED");
        return id;
    }
    private List<Map<String, Object>> latest() {
        return db.queryForList("SELECT * FROM accounting_period_closes WHERE business_id = 1 AND status = 'CLOSED' ORDER BY ends_on DESC");
    }
    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("accountingPeriodCloses", db.queryForList("SELECT * FROM accounting_period_closes WHERE business_id = 1 ORDER BY closed_at DESC, id"));
    }
}
