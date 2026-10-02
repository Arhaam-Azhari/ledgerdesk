package com.ledgerdesk;

import java.time.LocalDate;
import java.util.List;
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
class AssetEndingTest {
    @Autowired FixedAssetService assets;
    @Autowired PrepaidService prepaid;
    @Autowired PurchaseService purchases;
    @Autowired LedgerService ledger;
    @Autowired ReportService reports;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired BankService bank;
    @Autowired BankMatching matching;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    private String expense;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Equipment supplier", "accounts@example.test"), "vendor", "test");
        expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Office equipment", start, "5500", "100"), "expense", "test");
    }
    private FixedAssetService.Asset asset(String residual) { return new FixedAssetService.Asset(expense, start, 3, "Office equipment", residual); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Test void untouchedCorrectionRestoresExpenseRetainsHistoryAndAllowsPurchaseReversal() {
        var original = reports.reports(start, end);
        String id = assets.create(asset("0"), "asset", "test");
        var request = new FixedAssetService.Correction("Should be expensed immediately");
        String result = assets.correct(id, request, "correct", "test");
        assertThat(assets.correct(id, request, "correct", "test")).isEqualTo(result);
        assertThat(reports.reports(start, end).profitLoss()).isEqualTo(original.profitLoss());
        assertThat(count("fixed_assets")).isEqualTo(1); assertThat(count("asset_periods")).isEqualTo(3);
        assertThatThrownBy(() -> assets.correct(id, request, "again", "test")).hasMessageContaining("corrected back");
        assertThatThrownBy(() -> assets.depreciate(id, new FixedAssetService.Depreciation(end), "oct", "test")).hasMessageContaining("corrected back");
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(start, "Broken"), "retire", "test")).hasMessageContaining("corrected back");
        purchases.reverseExpense(expense, start, "reverse", "test");
        assertThat(reports.reports(start, end).profitLoss().expenses()).isEqualByComparingTo("0");
    }
    @Test void retirementRemovesCostAndContraAssetAndPreservesClosedHistory() {
        String id = assets.create(asset("10"), "asset", "test");
        assets.depreciate(id, new FixedAssetService.Depreciation(end), "oct", "test");
        var october = reports.reports(start, end);
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        var request = new FixedAssetService.Retirement(end.plusDays(1), "Equipment no longer usable");
        String result = assets.retire(id, request, "retire", "test");
        assertThat(assets.retire(id, request, "retire", "test")).isEqualTo(result);
        assertThat(reports.reports(start, end)).isEqualTo(october);
        var november = reports.reports(end.plusDays(1), LocalDate.of(2026, 11, 30));
        assertThat(november.profitLoss().expenses()).isEqualByComparingTo("70");
        assertThat(november.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1500"); assertThat(a.amount()).isEqualByComparingTo("0"); });
        assertThat(november.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1590"); assertThat(a.amount()).isEqualByComparingTo("0"); });
        assertThat(november.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(db.queryForObject("SELECT accumulated FROM asset_retirements", java.math.BigDecimal.class)).isEqualByComparingTo("30");
        assertThat(db.queryForObject("SELECT book_value FROM asset_retirements", java.math.BigDecimal.class)).isEqualByComparingTo("70");
        assertThatThrownBy(() -> assets.depreciate(id, new FixedAssetService.Depreciation(LocalDate.of(2026, 11, 30)), "nov", "test")).hasMessageContaining("retired");
        assertThatThrownBy(() -> assets.retire(id, request, "again", "test")).hasMessageContaining("already retired");
        assertThatThrownBy(() -> assets.correct(id, new FixedAssetService.Correction("Wrong"), "correct", "test")).hasMessageContaining("retired");
        assertThat(count("journal_lines")).isEqualTo(9);
    }
    @Test void retirementBeforeDepreciationExpensesFullCostWithoutZeroLines() {
        String id = assets.create(asset("0"), "asset", "test");
        assets.retire(id, new FixedAssetService.Retirement(start, "Damaged immediately"), "retire", "test");
        assertThat(reports.reports(start, end).profitLoss().expenses()).isEqualByComparingTo("100");
        assertThat(count("journal_lines")).isEqualTo(6);
        assertThat(count("asset_periods")).isEqualTo(3);
    }
    @Test void fullyDepreciatedRetirementRemovesBalancesWithNoLoss() {
        String id = assets.create(asset("0"), "asset", "test");
        for (int i = 0; i < 3; i++) assets.depreciate(id, new FixedAssetService.Depreciation(java.time.YearMonth.from(start).plusMonths(i).atEndOfMonth()), "month-" + i, "test");
        assets.retire(id, new FixedAssetService.Retirement(LocalDate.of(2027, 1, 1), "End of use"), "retire", "test");
        assertThat(reports.reports(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)).profitLoss().expenses()).isEqualByComparingTo("0");
        assertThat(count("journal_lines")).isEqualTo(12);
        assertThat(db.queryForObject("SELECT book_value FROM asset_retirements", java.math.BigDecimal.class)).isEqualByComparingTo("0");
    }
    @Test void invalidDatesSkippedMonthsClosedPeriodsAndUsedCorrectionsAreRejected() {
        String id = assets.create(asset("0"), "asset", "test");
        assertThatThrownBy(() -> assets.correct(id, new FixedAssetService.Correction(" "), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(null, "Reason"), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(start.minusDays(1), "Reason"), "bad", "test")).hasMessageContaining("precede");
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(end.plusDays(1), "Reason"), "bad", "test")).hasMessageContaining("earlier depreciation");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> assets.correct(id, new FixedAssetService.Correction("Wrong"), "bad", "test")).hasMessageContaining("closed period");
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(end, "Reason"), "bad", "test")).hasMessageContaining("closed period");
        assertThat(count("asset_corrections")).isZero(); assertThat(count("asset_retirements")).isZero();
    }
    @Test void postedDepreciationPreventsCorrectionOrEarlierRetirementAndInconsistentJournalsFail() {
        String id = assets.create(asset("0"), "asset", "test");
        assets.depreciate(id, new FixedAssetService.Depreciation(end), "oct", "test");
        assertThatThrownBy(() -> assets.correct(id, new FixedAssetService.Correction("Wrong"), "bad", "test")).hasMessageContaining("without posted depreciation");
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(start, "Broken"), "bad", "test")).hasMessageContaining("every posted depreciation");
        db.update("UPDATE journal_lines SET credit = 20 WHERE account_code = '1590'");
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(end.plusDays(1), "Broken"), "bad", "test")).hasMessageContaining("inconsistent");
        assertThat(count("asset_retirements")).isZero();
    }
    @Test void auditFailureRollsBackBothEndingKindsAndKeepsRequestKeysReusable() {
        String id = assets.create(asset("0"), "asset", "test");
        assertThatThrownBy(() -> assets.correct(id, new FixedAssetService.Correction("Wrong"), "correct", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("asset_corrections")).isZero(); assertThat(count("journal_lines")).isEqualTo(4);
        assertThatThrownBy(() -> assets.retire(id, new FixedAssetService.Retirement(start, "Broken"), "retire", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("asset_retirements")).isZero(); assertThat(count("journal_lines")).isEqualTo(4); assertThat(count("commands")).isEqualTo(3);
        assertThat(assets.correct(id, new FixedAssetService.Correction("Wrong"), "correct", "test")).isNotBlank();
    }
    private String match() {
        bank.importCsv(new BankService.Import("October", "transaction_id,date,description,amount\nEQUIPMENT,2026-10-01,Equipment,-100"), "import", "test");
        String bankId = db.queryForObject("SELECT id FROM bank_transactions", String.class);
        String line = db.queryForObject("SELECT id FROM journal_lines WHERE account_code = '1000'", String.class);
        matching.match(bankId, new BankMatching.Match(line), "match", "test");
        return bankId;
    }
    @Test void capitalizationDepreciationAndRetirementLeaveExistingBankMatchAndPreviewIntact() {
        String bankId = match(); var matches = db.queryForList("SELECT * FROM bank_matches");
        var statement = new BankReconciliation.Statement(start, end, "0", "-100");
        var before = reconciliation.preview(statement);
        String id = assets.create(asset("10"), "asset", "test");
        assets.depreciate(id, new FixedAssetService.Depreciation(end), "oct", "test");
        assets.retire(id, new FixedAssetService.Retirement(end.plusDays(1), "Broken"), "retire", "test");
        assertThat(db.queryForList("SELECT * FROM bank_matches")).isEqualTo(matches);
        assertThat(reconciliation.preview(statement)).isEqualTo(before);
        assertThat(matching.candidates(bankId)).isEmpty();
        assertThat(count("bank_match_events")).isEqualTo(1);
    }
    @Test void correctionPreservesMatchedPaymentAndItsPurchaseReversalGuard() {
        match(); var before = db.queryForList("SELECT * FROM bank_matches");
        String id = assets.create(asset("0"), "asset", "test");
        assets.correct(id, new FixedAssetService.Correction("Wrong"), "correct", "test");
        assertThat(db.queryForList("SELECT * FROM bank_matches")).isEqualTo(before);
        assertThatThrownBy(() -> purchases.reverseExpense(expense, start, "reverse", "test")).hasMessageContaining("Unmatch");
    }
    @Test void endingEndpointsRequireAuthenticationCsrfAndRequestKeys() throws Exception {
        String id = assets.create(asset("0"), "asset", "test");
        check("/api/assets/" + id + "/correct", "{\"reason\":\"Accidental capitalization\"}", "correct");
        DatabaseFixture.reset(db); clean();
        id = assets.create(asset("0"), "asset", "test");
        check("/api/assets/" + id + "/retire", "{\"retiredOn\":\"2026-10-01\",\"reason\":\"Broken\"}", "retire");
    }
    private void check(String url, String body, String key) throws Exception {
        http.perform(post(url).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isUnauthorized());
        http.perform(post(url).with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isForbidden());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isOk());
    }
}
