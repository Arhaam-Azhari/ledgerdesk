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
public class FixedAssetService {
    private final JdbcTemplate db;
    private final LedgerService ledger;
    public record Asset(String expenseId, LocalDate inServiceOn, int months, String name, String residualValue) {}
    public record Depreciation(LocalDate periodOn) {}
    public record Correction(String reason) {}
    public record Retirement(LocalDate retiredOn, String reason) {}
    public FixedAssetService(JdbcTemplate db, LedgerService ledger) { this.db = db; this.ledger = ledger; }

    private static void validDate(LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999)
            throw new IllegalArgumentException("Choose a valid fixed asset date.");
    }

    @Transactional
    public String create(Asset request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Fixed asset details are required.");
        validDate(request.inServiceOn());
        if (request.inServiceOn().getDayOfMonth() != 1 || request.months() < 1 || request.months() > 600)
            throw new IllegalArgumentException("Start on the first of a month and choose between one and six hundred months.");
        YearMonth first = YearMonth.from(request.inServiceOn());
        if (first.plusMonths(request.months() - 1).getYear() > 9999)
            throw new IllegalArgumentException("The depreciation schedule must end by year 9999.");
        String name = LedgerService.text(request.name(), 240, "Asset name");
        LedgerService.text(request.expenseId(), 36, "Expense ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("fixed-asset", request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT * FROM expenses WHERE id = ? AND business_id = 1", request.expenseId());
        if (found.isEmpty()) throw new IllegalArgumentException("Paid expense not found.");
        var expense = found.get(0);
        if (!expense.get("status").equals("POSTED")) throw new IllegalArgumentException("Choose an unreversed paid expense.");
        if (db.queryForObject("SELECT COUNT(*) FROM fixed_assets WHERE expense_id = ?", Integer.class, request.expenseId()) != 0)
            throw new IllegalArgumentException("This expense already funds a fixed asset.");
        if (db.queryForObject("SELECT COUNT(*) FROM prepaid_plans p WHERE p.expense_id = ? AND NOT EXISTS (SELECT 1 FROM prepaid_corrections c WHERE c.plan_id = p.id)", Integer.class, request.expenseId()) != 0)
            throw new IllegalArgumentException("This purchase funds a prepaid plan; it cannot also fund a fixed asset.");
        LocalDate funded = ((java.sql.Date) expense.get("spent_on")).toLocalDate();
        validDate(funded);
        if (request.inServiceOn().isBefore(funded)) throw new IllegalArgumentException("The service period cannot start before the payment.");
        ledger.requireOpenDate(funded);
        BigDecimal total = (BigDecimal) expense.get("amount");
        if (request.residualValue() == null || !request.residualValue().matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Enter a nonnegative residual value with at most two decimal places.");
        BigDecimal residual = new BigDecimal(request.residualValue()).setScale(2);
        if (residual.compareTo(total) >= 0) throw new IllegalArgumentException("Residual value must be less than purchase cost.");
        BigDecimal depreciable = total.subtract(residual);
        BigDecimal monthly = depreciable.divide(BigDecimal.valueOf(request.months()), 2, RoundingMode.DOWN);
        if (monthly.signum() <= 0) throw new IllegalArgumentException("Each depreciation month must receive at least one cent.");
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
                "SELECT COUNT(*) FROM accounts WHERE code = ? AND kind = 'EXPENSE' AND code NOT IN ('5600', '5700')", Integer.class, account) != 1)
            throw new IllegalArgumentException("The paid expense journal is inconsistent. Review it before capitalizing.");
        String id = ledger.id();
        db.update("INSERT INTO fixed_assets VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)", id, request.expenseId(), funded,
                request.inServiceOn(), request.months(), name, account, total, residual);
        // Capitalize the paid cost without creating another bank payment.
        ledger.journal(id, funded, "Equipment setup: " + name, "1500", account, total);
        for (int i = 0; i < request.months(); i++) {
            BigDecimal amount = i == request.months() - 1 ? depreciable.subtract(monthly.multiply(BigDecimal.valueOf(i))) : monthly;
            db.update("INSERT INTO asset_periods VALUES (?, ?, ?, ?, NULL)", ledger.id(), id, first.plusMonths(i).atEndOfMonth(), amount);
        }
        ledger.complete(key, hash, id, actor, "FIXED_ASSET_CREATED");
        return id;
    }

    @Transactional
    public String depreciate(String assetId, Depreciation request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Depreciation details are required.");
        validDate(request.periodOn());
        LedgerService.text(assetId, 36, "Asset ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("asset-depreciation", assetId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var found = db.queryForList("SELECT * FROM fixed_assets WHERE id = ? AND business_id = 1", assetId);
        if (found.isEmpty()) throw new IllegalArgumentException("Fixed asset not found.");
        requireActive(assetId);
        var periods = db.queryForList("SELECT * FROM asset_periods WHERE asset_id = ? AND period_on = ?", assetId, request.periodOn());
        if (periods.isEmpty()) throw new IllegalArgumentException("Choose a scheduled depreciation month-end.");
        var period = periods.get(0);
        if (period.get("entry_id") != null) throw new IllegalArgumentException("This month was already depreciated.");
        if (db.queryForObject("SELECT COUNT(*) FROM asset_periods WHERE asset_id = ? AND period_on < ? AND entry_id IS NULL", Integer.class, assetId, request.periodOn()) != 0)
            throw new IllegalArgumentException("Depreciate the earlier scheduled months first.");
        ledger.requireOpenDate(request.periodOn());
        String id = period.get("id").toString();
        ledger.journal(id, request.periodOn(), "Depreciation: " + found.get(0).get("name"), "5600", "1590", (BigDecimal) period.get("amount"));
        String entry = db.queryForObject("SELECT id FROM journal_entries WHERE business_id = 1 AND source_id = ?", String.class, id);
        db.update("UPDATE asset_periods SET entry_id = ? WHERE id = ?", entry, id);
        ledger.complete(key, hash, id, actor, "ASSET_DEPRECIATION_POSTED");
        return id;
    }

    private Map<String, Object> asset(String id) {
        var found = db.queryForList("SELECT * FROM fixed_assets WHERE id = ? AND business_id = 1", id);
        if (found.isEmpty()) throw new IllegalArgumentException("Fixed asset not found.");
        return found.get(0);
    }

    private void requireActive(String id) {
        if (db.queryForObject("SELECT COUNT(*) FROM asset_corrections WHERE asset_id = ?", Integer.class, id) != 0)
            throw new IllegalArgumentException("This asset was corrected back to a direct expense.");
        if (db.queryForObject("SELECT COUNT(*) FROM asset_retirements WHERE asset_id = ?", Integer.class, id) != 0)
            throw new IllegalArgumentException("This asset was already retired.");
    }

    private void requireJournal(String source, LocalDate date, String debitCode, String creditCode, BigDecimal amount) {
        var lines = db.queryForList("SELECT l.account_code, l.debit, l.credit FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id WHERE e.business_id = 1 AND e.source_id = ? AND e.entry_date = ?", source, date);
        boolean debit = false, credit = false;
        for (var line : lines) {
            BigDecimal d = (BigDecimal) line.get("debit"), c = (BigDecimal) line.get("credit");
            if (line.get("account_code").equals(debitCode) && d.compareTo(amount) == 0 && c.signum() == 0) debit = true;
            if (line.get("account_code").equals(creditCode) && c.compareTo(amount) == 0 && d.signum() == 0) credit = true;
        }
        if (lines.size() != 2 || !debit || !credit)
            throw new IllegalArgumentException("The asset journal is inconsistent. Review it before ending the asset.");
    }

    @Transactional
    public String correct(String assetId, Correction request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Correction details are required.");
        String reason = LedgerService.text(request.reason(), 240, "Correction reason");
        LedgerService.text(assetId, 36, "Asset ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("asset-correction", assetId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var record = asset(assetId);
        requireActive(assetId);
        if (db.queryForObject("SELECT COUNT(*) FROM asset_periods WHERE asset_id = ? AND entry_id IS NOT NULL", Integer.class, assetId) != 0)
            throw new IllegalArgumentException("Only an asset without posted depreciation can be corrected.");
        LocalDate date = ((java.sql.Date) record.get("funded_on")).toLocalDate();
        ledger.requireOpenDate(date);
        BigDecimal cost = (BigDecimal) record.get("cost");
        String account = record.get("account_code").toString();
        requireJournal(assetId, date, "1500", account, cost);
        String id = ledger.id();
        ledger.journal(id, date, "Equipment correction: " + reason, account, "1500", cost);
        db.update("INSERT INTO asset_corrections VALUES (?, ?, ?, ?)", id, assetId, date, reason);
        ledger.complete(key, hash, id, actor, "FIXED_ASSET_CORRECTED");
        return id;
    }

    @Transactional
    public String retire(String assetId, Retirement request, String key, String actor) {
        if (request == null) throw new IllegalArgumentException("Retirement details are required.");
        validDate(request.retiredOn());
        String reason = LedgerService.text(request.reason(), 240, "Retirement reason");
        LedgerService.text(assetId, 36, "Asset ID");
        ledger.lockBusiness();
        String hash = ledger.fingerprint(List.of("asset-retirement", assetId, request));
        String previous = ledger.retry(key, hash);
        if (previous != null) return previous;
        var record = asset(assetId);
        requireActive(assetId);
        LocalDate funded = ((java.sql.Date) record.get("funded_on")).toLocalDate();
        if (request.retiredOn().isBefore(funded)) throw new IllegalArgumentException("Retirement cannot precede the purchase.");
        var periods = db.queryForList("SELECT * FROM asset_periods WHERE asset_id = ? ORDER BY period_on", assetId);
        BigDecimal accumulated = new BigDecimal("0.00"), scheduled = new BigDecimal("0.00");
        for (var period : periods) {
            LocalDate date = ((java.sql.Date) period.get("period_on")).toLocalDate();
            BigDecimal amount = (BigDecimal) period.get("amount");
            scheduled = scheduled.add(amount);
            if (period.get("entry_id") != null) {
                if (date.isAfter(request.retiredOn())) throw new IllegalArgumentException("Retire on or after every posted depreciation date.");
                requireJournal(period.get("id").toString(), date, "5600", "1590", amount);
                accumulated = accumulated.add(amount);
            } else if (date.isBefore(request.retiredOn()))
                throw new IllegalArgumentException("Post the earlier depreciation months before retirement.");
        }
        BigDecimal cost = (BigDecimal) record.get("cost"), residual = (BigDecimal) record.get("residual_value");
        if (periods.size() != ((Number) record.get("months")).intValue() || scheduled.compareTo(cost.subtract(residual)) != 0)
            throw new IllegalArgumentException("The asset schedule is inconsistent. Review it before retirement.");
        requireJournal(assetId, funded, "1500", record.get("account_code").toString(), cost);
        ledger.requireOpenDate(request.retiredOn());
        BigDecimal bookValue = cost.subtract(accumulated);
        String id = ledger.id(), entry = ledger.id();
        // Remove gross cost and its contra asset; only the remaining book value becomes a loss.
        db.update("INSERT INTO journal_entries VALUES (?, 1, ?, ?, ?)", entry, request.retiredOn(), "Equipment retirement: " + reason, id);
        db.update("INSERT INTO journal_lines VALUES (?, ?, '1500', 0, ?)", ledger.id(), entry, cost);
        if (accumulated.signum() > 0) db.update("INSERT INTO journal_lines VALUES (?, ?, '1590', ?, 0)", ledger.id(), entry, accumulated);
        if (bookValue.signum() > 0) db.update("INSERT INTO journal_lines VALUES (?, ?, '5700', ?, 0)", ledger.id(), entry, bookValue);
        db.update("INSERT INTO asset_retirements VALUES (?, ?, ?, ?, ?, ?, ?)", id, assetId, request.retiredOn(), reason, cost, accumulated, bookValue);
        ledger.complete(key, hash, id, actor, "FIXED_ASSET_RETIRED");
        return id;
    }

    static Map<String, Object> readState(JdbcTemplate db) {
        return Map.of("fixedAssets", db.queryForList("SELECT a.*, c.id AS correction_id, c.corrected_on, c.reason AS correction_reason, r.id AS retirement_id, r.retired_on, r.reason AS retirement_reason, r.book_value AS retirement_loss, e.description, v.name AS vendor_name FROM fixed_assets a LEFT JOIN asset_corrections c ON c.asset_id = a.id LEFT JOIN asset_retirements r ON r.asset_id = a.id JOIN expenses e ON e.id = a.expense_id JOIN vendors v ON v.id = e.vendor_id WHERE a.business_id = 1 ORDER BY a.funded_on, a.id"),
                "assetPeriods", db.queryForList("SELECT r.* FROM asset_periods r JOIN fixed_assets a ON a.id = r.asset_id WHERE a.business_id = 1 ORDER BY r.period_on, r.id"));
    }
}
