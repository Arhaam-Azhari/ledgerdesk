package com.ledgerdesk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrepaidService {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    public record Plan(String expenseId, LocalDate startsOn, int months, String memo) {}
    public record Recognition(LocalDate periodOn) {}
    public record Cancellation(LocalDate cancelledOn, String reason) {}
    public record Correction(String reason) {}
    public PrepaidService(JdbcTemplate db, LedgerService ledger) { this.db = db; this.ledger = ledger; }

    private static void validDate(LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999)
            throw new IllegalArgumentException("Choose a valid prepaid expense date.");
    }

    @Transactional
    public String create(Plan request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Prepaid plan details are required.");
        validDate(request.startsOn());
        if (request.startsOn().getDayOfMonth() != 1 || request.months() < 1 || request.months() > 60)
            throw new IllegalArgumentException("Start on the first of a month and choose between one and sixty months.");
        YearMonth first = YearMonth.from(request.startsOn());
        if (first.plusMonths(request.months() - 1).getYear() > 9999)
            throw new IllegalArgumentException("The prepaid schedule must end by year 9999.");
        String memo = LedgerService.text(request.memo(), 240, "Prepaid memo");
        LedgerService.text(request.expenseId(), 36, "Expense ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("prepaid-plan", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT * FROM expenses WHERE id = ? AND business_id = 1", request.expenseId());
        if (found.isEmpty()) throw new IllegalArgumentException("Paid expense not found.");
        var expense = found.get(0);
        if (!expense.get("status").equals("POSTED")) throw new IllegalArgumentException("Choose an unreversed paid expense.");
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_plans WHERE expense_id = ?", Integer.class, request.expenseId()) != 0)
            throw new IllegalArgumentException("This expense already has a prepaid plan.");
        if (db.queryForObject("SELECT COUNT(*) FROM fixed_assets a WHERE a.expense_id = ? AND NOT EXISTS (SELECT 1 FROM asset_corrections c WHERE c.asset_id = a.id)", Integer.class, request.expenseId()) != 0)
            throw new IllegalArgumentException("This expense funds a fixed asset.");
        LocalDate funded = ((java.sql.Date) expense.get("spent_on")).toLocalDate();
        validDate(funded);
        if (request.startsOn().isBefore(funded)) throw new IllegalArgumentException("The benefit period cannot start before the payment.");
        ledger.requireOpenDate(funded);
        BigDecimal total = (BigDecimal) expense.get("amount");
        BigDecimal monthly = total.divide(BigDecimal.valueOf(request.months()), 2, RoundingMode.DOWN);
        if (monthly.signum() <= 0) throw new IllegalArgumentException("Each month must receive at least one cent.");
        String account = expense.get("account_code").toString();
        var original = db.queryForList("""
            SELECT l.account_code, l.debit, l.credit FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
            WHERE e.business_id = 1 AND e.source_id = ?
            """, request.expenseId());
        boolean cash = false, cost = false;
        for (var line : original) {
            BigDecimal debit = (BigDecimal) line.get("debit"), credit = (BigDecimal) line.get("credit");
            if (line.get("account_code").equals("1000") && debit.signum() == 0 && credit.compareTo(total) == 0) cash = true;
            if (line.get("account_code").equals(account) && credit.signum() == 0 && debit.compareTo(total) == 0) cost = true;
        }
        if (original.size() != 2 || !cash || !cost || db.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE code = ? AND kind = 'EXPENSE'", Integer.class, account) != 1)
            throw new IllegalArgumentException("The paid expense journal is inconsistent. Review it before deferring.");
        String id = ledger.id();
        db.update("INSERT INTO prepaid_plans VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)", id, request.expenseId(), funded,
                request.startsOn(), request.months(), memo, account, total);
        // Reclassify on the payment date; the existing bank line and receipt evidence remain intact.
        ledger.journal(id, funded, "Prepaid setup: " + memo, "1300", account, total);
        for (int i = 0; i < request.months(); i++) {
            BigDecimal amount = i == request.months() - 1 ? total.subtract(monthly.multiply(BigDecimal.valueOf(i))) : monthly;
            db.update("INSERT INTO prepaid_periods VALUES (?, ?, ?, ?, NULL)", ledger.id(), id, first.plusMonths(i).atEndOfMonth(), amount);
        }
        ledger.complete(key, hash, id, actor, "PREPAID_PLAN_CREATED");
        return id;
    }

    @Transactional
    public String recognize(String planId, Recognition request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Recognition details are required.");
        validDate(request.periodOn());
        LedgerService.text(planId, 36, "Prepaid plan ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("prepaid-recognition", planId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT * FROM prepaid_plans WHERE id = ? AND business_id = 1", planId);
        if (found.isEmpty()) throw new IllegalArgumentException("Prepaid plan not found.");
        var plan = found.get(0);
        requireUncorrected(planId);
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_cancellations WHERE plan_id = ?", Integer.class, planId) != 0)
            throw new IllegalArgumentException("This prepaid plan was cancelled.");
        var periods = db.queryForList("SELECT * FROM prepaid_periods WHERE plan_id = ? AND period_on = ?", planId, request.periodOn());
        if (periods.isEmpty()) throw new IllegalArgumentException("Choose a scheduled month-end date.");
        var period = periods.get(0);
        if (period.get("entry_id") != null) throw new IllegalArgumentException("This month was already recognized.");
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_periods WHERE plan_id = ? AND period_on < ? AND entry_id IS NULL", Integer.class, planId, request.periodOn()) != 0)
            throw new IllegalArgumentException("Recognize the earlier scheduled months first.");
        ledger.requireOpenDate(request.periodOn());
        String id = period.get("id").toString();
        ledger.journal(id, request.periodOn(), "Prepaid recognition: " + plan.get("memo"), plan.get("account_code").toString(), "1300", (BigDecimal) period.get("amount"));
        String entry = db.queryForObject("SELECT id FROM journal_entries WHERE business_id = 1 AND source_id = ?", String.class, id);
        db.update("UPDATE prepaid_periods SET entry_id = ? WHERE id = ?", entry, id);
        ledger.complete(key, hash, id, actor, "PREPAID_EXPENSE_RECOGNIZED");
        return id;
    }

    @Transactional
    public String cancel(String planId, Cancellation request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Cancellation details are required.");
        validDate(request.cancelledOn());
        String reason = LedgerService.text(request.reason(), 240, "Cancellation reason");
        LedgerService.text(planId, 36, "Prepaid plan ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("prepaid-cancellation", planId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT * FROM prepaid_plans WHERE id = ? AND business_id = 1", planId);
        if (found.isEmpty()) throw new IllegalArgumentException("Prepaid plan not found.");
        var plan = found.get(0);
        requireUncorrected(planId);
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_cancellations WHERE plan_id = ?", Integer.class, planId) != 0)
            throw new IllegalArgumentException("This prepaid plan was already cancelled.");
        LocalDate funded = ((java.sql.Date) plan.get("funded_on")).toLocalDate();
        if (request.cancelledOn().isBefore(funded) || db.queryForObject(
                "SELECT COUNT(*) FROM prepaid_periods WHERE plan_id = ? AND entry_id IS NOT NULL AND period_on > ?",
                Integer.class, planId, request.cancelledOn()) != 0)
            throw new IllegalArgumentException("Cancel on or after funding and every posted recognition.");
        BigDecimal remaining = db.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM prepaid_periods WHERE plan_id = ? AND entry_id IS NULL", BigDecimal.class, planId);
        if (remaining.signum() <= 0) throw new IllegalArgumentException("This plan is fully recognized; there is no remaining benefit to cancel.");
        ledger.requireOpenDate(request.cancelledOn());
        String id = ledger.id();
        // An ended benefit is expensed now. Cancellation does not imply a supplier refund.
        ledger.journal(id, request.cancelledOn(), "Prepaid cancellation: " + reason, plan.get("account_code").toString(), "1300", remaining);
        db.update("INSERT INTO prepaid_cancellations VALUES (?, ?, ?, ?, ?)", id, planId, request.cancelledOn(), reason, remaining);
        ledger.complete(key, hash, id, actor, "PREPAID_PLAN_CANCELLED");
        return id;
    }

    private void requireUncorrected(String planId) {
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_corrections WHERE plan_id = ?", Integer.class, planId) != 0)
            throw new IllegalArgumentException("This prepaid plan was corrected back to a direct expense.");
    }

    @Transactional
    public String correct(String planId, Correction request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Correction details are required.");
        String reason = LedgerService.text(request.reason(), 240, "Correction reason");
        LedgerService.text(planId, 36, "Prepaid plan ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("prepaid-correction", planId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT * FROM prepaid_plans WHERE id = ? AND business_id = 1", planId);
        if (found.isEmpty()) throw new IllegalArgumentException("Prepaid plan not found.");
        var plan = found.get(0);
        requireUncorrected(planId);
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_cancellations WHERE plan_id = ?", Integer.class, planId) != 0
                || db.queryForObject("SELECT COUNT(*) FROM prepaid_periods WHERE plan_id = ? AND entry_id IS NOT NULL", Integer.class, planId) != 0)
            throw new IllegalArgumentException("Only an unrecognized, uncancelled plan can be corrected.");
        LocalDate date = ((java.sql.Date) plan.get("funded_on")).toLocalDate();
        ledger.requireOpenDate(date);
        BigDecimal amount = (BigDecimal) plan.get("amount");
        String account = plan.get("account_code").toString();
        var lines = db.queryForList("SELECT l.account_code, l.debit, l.credit FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id WHERE e.business_id = 1 AND e.source_id = ? AND e.entry_date = ?", planId, date);
        boolean asset = false, expense = false;
        for (var line : lines) {
            BigDecimal debit = (BigDecimal) line.get("debit"), credit = (BigDecimal) line.get("credit");
            if (line.get("account_code").equals("1300") && debit.compareTo(amount) == 0 && credit.signum() == 0) asset = true;
            if (line.get("account_code").equals(account) && debit.signum() == 0 && credit.compareTo(amount) == 0) expense = true;
        }
        if (lines.size() != 2 || !asset || !expense)
            throw new IllegalArgumentException("The prepaid setup journal is inconsistent. Review it before correcting.");
        String id = ledger.id();
        // Offset the setup on its original open date; leave the purchase and bank line alone.
        ledger.journal(id, date, "Prepaid correction: " + reason, account, "1300", amount);
        db.update("INSERT INTO prepaid_corrections VALUES (?, ?, ?, ?)", id, planId, date, reason);
        ledger.complete(key, hash, id, actor, "PREPAID_PLAN_CORRECTED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("prepaidPlans", db.queryForList("SELECT p.*, x.id AS correction_id, x.corrected_on, x.reason AS correction_reason, c.id AS cancellation_id, c.cancelled_on, c.reason AS cancellation_reason, c.amount AS cancelled_amount, e.description, v.name AS vendor_name FROM prepaid_plans p LEFT JOIN prepaid_corrections x ON x.plan_id = p.id LEFT JOIN prepaid_cancellations c ON c.plan_id = p.id JOIN expenses e ON e.id = p.expense_id JOIN vendors v ON v.id = e.vendor_id WHERE p.business_id = 1 ORDER BY p.funded_on, p.id"),
                "prepaidPeriods", db.queryForList("SELECT r.* FROM prepaid_periods r JOIN prepaid_plans p ON p.id = r.plan_id WHERE p.business_id = 1 ORDER BY r.period_on, r.id"));
    }
}
