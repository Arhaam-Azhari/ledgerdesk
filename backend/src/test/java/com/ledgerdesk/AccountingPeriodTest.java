package com.ledgerdesk;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountingPeriodTest {
    @Autowired AccountingPeriodService periods;
    @Autowired OpeningBankBalance opening;
    @Autowired BankReconciliation bank;
    @Autowired LedgerService ledger;
    @Autowired EquityService equity;
    @Autowired PurchaseService purchases;
    @Autowired PrepaidService prepaid;
    @Autowired FixedAssetService assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    AccountingPeriodService.Close request() { return new AccountingPeriodService.Close(end, "Reviewed statements, documents and adjustments"); }
    void seed() { opening.post(new OpeningBankBalance.Opening(start.minusDays(1), "1000.25", "Cleared opening"), "opening", "test"); }
    String closeBank(LocalDate first, LocalDate last) {
        return bank.close(new BankReconciliation.Statement(first, last, "1000.25", "1000.25"), "bank-" + last, "test");
    }
    String prepared() { seed(); return closeBank(start, end); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void previewAndCloseRetainBalancedReportsWithoutPosting() {
        String bankId = prepared();
        var before = ledger.state();
        var p = periods.preview(end);
        assertThat(p.ready()).isTrue();
        assertThat(p.startsOn()).isEqualTo(start);
        assertThat(p.bankReconciliationId()).isEqualTo(bankId);
        assertThat(p.reports().balanceSheet().totalAssets()).isEqualByComparingTo("1000.25");
        assertThat(p.cashActivity().receipts()).isEqualByComparingTo("0");
        assertThat(ledger.state()).isEqualTo(before);
        String id = periods.close(request(), "period", "test");
        var row = db.queryForMap("SELECT * FROM accounting_period_closes WHERE id = ?", id);
        assertThat(row.get("snapshot").toString()).contains(bankId, "1000.25");
        assertThat(row.get("closed_by")).isEqualTo("test");
        assertThat(count("journal_lines")).isEqualTo(2);
        assertThat(count("accounting_period_closes")).isEqualTo(1);
    }
    @Test void missingBankCloseBlocksPeriodClose() {
        seed();
        assertThat(periods.preview(end).ready()).isFalse();
        assertThatThrownBy(() -> periods.close(request(), "period", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("accounting_period_closes")).isZero();
    }
    @Test void exactRetriesAndChangedKeysAreSafeAfterReopen() {
        prepared();
        String id = periods.close(request(), "period", "test");
        assertThat(periods.close(request(), "period", "test")).isEqualTo(id);
        assertThatThrownBy(() -> periods.close(new AccountingPeriodService.Close(end, "Different review"), "period", "test")).isInstanceOf(IllegalArgumentException.class);
        var reopen = new AccountingPeriodService.Reopen(1, "Review missed supporting document");
        periods.reopen(id, reopen, "reopen", "test");
        assertThat(periods.reopen(id, reopen, "reopen", "test")).isEqualTo(id);
        assertThat(periods.close(request(), "period", "test")).isEqualTo(id);
        assertThat(db.queryForObject("SELECT status FROM accounting_period_closes WHERE id = ?", String.class, id)).isEqualTo("REOPENED");
    }
    @Test void consecutiveClosesCarryTheNextStartAndOnlyLatestCanReopen() {
        prepared();
        String first = periods.close(request(), "first", "test");
        LocalDate november = LocalDate.of(2026, 11, 30);
        closeBank(end.plusDays(1), november);
        assertThat(periods.preview(november).startsOn()).isEqualTo(end.plusDays(1));
        String second = periods.close(new AccountingPeriodService.Close(november, "November review"), "second", "test");
        assertThatThrownBy(() -> periods.reopen(first, new AccountingPeriodService.Reopen(1, "Old period"), "old", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods.reopen(second, new AccountingPeriodService.Reopen(2, "Stale version"), "stale", "test")).isInstanceOf(IllegalArgumentException.class);
        periods.reopen(second, new AccountingPeriodService.Reopen(1, "Latest review"), "latest", "test");
        assertThat(periods.preview(november).startsOn()).isEqualTo(end.plusDays(1));
    }
    @Test void periodProtectsPostingsAndTheSupportingBankStatement() {
        String bankId = prepared();
        String id = periods.close(request(), "period", "test");
        assertThatThrownBy(() -> equity.post(new EquityService.Transfer("CONTRIBUTION", end, "Old funding", "10"), "old-fund", "test")).hasMessageContaining("closed accounting period");
        assertThatThrownBy(() -> bank.reopen(bankId, new BankReconciliation.Reopen(1, "Change bank proof"), "bank-reopen", "test")).hasMessageContaining("accounting period");
        periods.reopen(id, new AccountingPeriodService.Reopen(1, "Recheck period"), "period-reopen", "test");
        bank.reopen(bankId, new BankReconciliation.Reopen(1, "Recheck bank"), "bank-reopen", "test");
        equity.post(new EquityService.Transfer("CONTRIBUTION", end, "Allowed after both reopen", "10"), "fund", "test");
        assertThat(count("journal_lines")).isEqualTo(4);
    }
    @Test void recloseKeepsTheOriginalSnapshotAndReopeningReason() {
        prepared();
        String original = periods.close(request(), "period", "test");
        String snapshot = db.queryForObject("SELECT snapshot FROM accounting_period_closes WHERE id = ?", String.class, original);
        periods.reopen(original, new AccountingPeriodService.Reopen(1, "Another review"), "reopen", "test");
        String replacement = periods.close(request(), "reclose", "test");
        assertThat(replacement).isNotEqualTo(original);
        assertThat(db.queryForObject("SELECT snapshot FROM accounting_period_closes WHERE id = ?", String.class, original)).isEqualTo(snapshot);
        assertThat(db.queryForObject("SELECT reopen_reason FROM accounting_period_closes WHERE id = ?", String.class, original)).isEqualTo("Another review");
        assertThat(count("accounting_period_closes")).isEqualTo(2);
    }
    @Test void invalidDatesNotesAndEarlierPeriodLeaveNoClose() {
        prepared();
        for (LocalDate date : List.of(end.minusDays(1), LocalDate.of(0, 1, 31), LocalDate.of(2026, 8, 31)))
            assertThatThrownBy(() -> periods.preview(date)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods.preview(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods.close(new AccountingPeriodService.Close(end, " "), "blank", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("accounting_period_closes")).isZero();
    }
    @Test void unbalancedBooksCannotClose() {
        prepared();
        db.update("UPDATE journal_lines SET debit = debit + 1 WHERE account_code = '1000'");
        assertThat(periods.preview(end).ready()).isFalse();
        assertThatThrownBy(() -> periods.close(request(), "period", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void auditFailureRollsBackCloseAndRequestKey() {
        prepared();
        var before = ledger.state();
        assertThatThrownBy(() -> periods.close(request(), "failed", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(ledger.state()).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands WHERE command_key = 'failed'", Integer.class)).isZero();
    }
    @Test void concurrentCloseKeepsOneActivePeriod() throws Exception {
        prepared();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.<java.util.concurrent.Callable<Boolean>>of(
                () -> { try { periods.close(request(), "one", "test"); return true; } catch (IllegalArgumentException e) { return false; } },
                () -> { try { periods.close(request(), "two", "test"); return true; } catch (IllegalArgumentException e) { return false; } });
            assertThat(executor.invokeAll(tasks).stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(count("accounting_period_closes")).isEqualTo(1);
    }
    @Test void scheduledMonthsBlockUntilRecognizedOrEnded() {
        seed();
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Supplier", "supplier@example.test"), "vendor", "test");
        String expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start, "5100", "60"), "expense", "test");
        String plan = prepaid.create(new PrepaidService.Plan(expense, start, 2, "Two months software"), "plan", "test");
        String equipment = purchases.postExpense(new PurchaseService.Expense(vendor, "Equipment", start, "5000", "100"), "equipment", "test");
        String asset = assets.create(new FixedAssetService.Asset(equipment, start, 2, "Work equipment", "0"), "asset", "test");
        assertThat(periods.preview(end).pendingPrepaidMonths()).isEqualTo(1);
        assertThat(periods.preview(end).pendingDepreciationMonths()).isEqualTo(1);
        prepaid.recognize(plan, new PrepaidService.Recognition(end), "recognize", "test");
        assets.depreciate(asset, new FixedAssetService.Depreciation(end), "depreciate", "test");
        closeBank(start, end);
        assertThat(periods.preview(end).ready()).isTrue();
        periods.close(request(), "period", "test");
    }
    @Test void cancellationAndCorrectionBeforeCutoffRemoveDueWork() {
        seed();
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Supplier", "supplier@example.test"), "vendor", "test");
        String expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start, "5100", "60"), "expense", "test");
        String plan = prepaid.create(new PrepaidService.Plan(expense, start, 2, "Software plan"), "plan", "test");
        prepaid.cancel(plan, new PrepaidService.Cancellation(start.plusDays(5), "Benefit ended"), "cancel", "test");
        String equipment = purchases.postExpense(new PurchaseService.Expense(vendor, "Equipment", start, "5000", "100"), "equipment", "test");
        String asset = assets.create(new FixedAssetService.Asset(equipment, start, 2, "Equipment", "0"), "asset", "test");
        assets.correct(asset, new FixedAssetService.Correction("Wrong allocation"), "correct", "test");
        closeBank(start, end);
        assertThat(periods.preview(end).pendingPrepaidMonths()).isZero();
        assertThat(periods.preview(end).pendingDepreciationMonths()).isZero();
        assertThat(periods.preview(end).ready()).isTrue();
    }
    @Test void futureCancellationDoesNotHideEarlierUnpostedRecognition() {
        seed();
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Supplier", "supplier@example.test"), "vendor", "test");
        String expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start, "5100", "60"), "expense", "test");
        String plan = prepaid.create(new PrepaidService.Plan(expense, start, 2, "Software plan"), "plan", "test");
        prepaid.cancel(plan, new PrepaidService.Cancellation(end.plusDays(1), "Later benefit ended"), "cancel", "test");
        closeBank(start, end);
        assertThat(periods.preview(end).pendingPrepaidMonths()).isEqualTo(1);
        assertThat(periods.preview(end).ready()).isFalse();
    }
    @Test void endpointReadsAreSharedAndWritesRequireOwnerAndCsrf() throws Exception {
        prepared();
        String body = "{\"endsOn\":\"2026-10-31\",\"reviewNote\":\"Reviewed period\"}";
        http.perform(get("/api/accounting-periods")).andExpect(status().isUnauthorized());
        http.perform(get("/api/accounting-periods/preview?endsOn=2026-10-31").with(user("reviewer").roles("REVIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        http.perform(post("/api/accounting-periods").with(user("reviewer").roles("REVIEWER")).with(csrf()).header("Idempotency-Key", "reviewer").contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/accounting-periods").with(user("owner").roles("OWNER")).header("Idempotency-Key", "owner").contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/accounting-periods").with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key", "owner").contentType("application/json").content(body)).andExpect(status().isOk());
    }
}
