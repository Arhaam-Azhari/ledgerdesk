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
class FixedAssetTest {
    @Autowired FixedAssetService assets;
    @Autowired PrepaidService prepaid;
    @Autowired PurchaseService purchases;
    @Autowired LedgerService ledger;
    @Autowired ReportService reports;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    private String expense;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Equipment supplier", "accounts@example.test"), "vendor", "test");
        expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Office equipment", start, "5500", "100"), "expense", "test");
    }
    private FixedAssetService.Asset asset(String residual) { return new FixedAssetService.Asset(expense, start, 3, "Office equipment", residual); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Test void capitalizationPreservesPaymentAndAllocatesTheFinalRemainder() {
        var cash = db.queryForList("SELECT * FROM journal_lines WHERE account_code = '1000'");
        assets.create(asset("0"), "asset", "test");
        assertThat(db.queryForList("SELECT amount FROM asset_periods ORDER BY period_on")).extracting(r -> r.get("amount").toString()).containsExactly("33.33", "33.33", "33.34");
        assertThat(db.queryForList("SELECT * FROM journal_lines WHERE account_code = '1000'")).isEqualTo(cash);
        assertThatThrownBy(() -> prepaid.create(new PrepaidService.Plan(expense, start, 3, "Benefit"), "prepaid", "test")).hasMessageContaining("fixed asset");
        String vendor = db.queryForObject("SELECT vendor_id FROM expenses WHERE id = ?", String.class, expense);
        assertThatThrownBy(() -> purchases.postExpense(new PurchaseService.Expense(vendor, "Wrong category", start, "5600", "10"), "wrong", "test")).hasMessageContaining("operating expense category");
        var r = reports.reports(start, end);
        assertThat(r.profitLoss().expenses()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1500"); assertThat(a.amount()).isEqualByComparingTo("100"); });
        assertThat(r.payables().total()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat((List<?>) ledger.state().get("fixedAssets")).hasSize(1);
    }
    @Test void depreciationPreservesCostAndFinishesAtResidualValueWithDatedReports() {
        String id = assets.create(asset("10"), "asset", "test");
        assets.depreciate(id, new FixedAssetService.Depreciation(end), "oct", "test");
        var october = reports.reports(start, end);
        assertThat(october.profitLoss().expenses()).isEqualByComparingTo("30");
        assets.depreciate(id, new FixedAssetService.Depreciation(LocalDate.of(2026, 11, 30)), "nov", "test");
        assets.depreciate(id, new FixedAssetService.Depreciation(LocalDate.of(2026, 12, 31)), "dec", "test");
        assertThat(reports.reports(start, end)).isEqualTo(october);
        var result = reports.reports(start, LocalDate.of(2026, 12, 31));
        assertThat(result.profitLoss().expenses()).isEqualByComparingTo("90");
        assertThat(result.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1500"); assertThat(a.amount()).isEqualByComparingTo("100"); });
        assertThat(result.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1590"); assertThat(a.amount()).isEqualByComparingTo("-90"); });
        assertThat(result.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(count("journal_lines")).isEqualTo(10);
    }
    @Test void retriesCannotDuplicateAssetsOrDepreciationAndClosedDatesRemainProtected() {
        String id = assets.create(asset("0"), "asset", "test");
        assertThat(assets.create(asset("0"), "asset", "test")).isEqualTo(id);
        assertThatThrownBy(() -> assets.create(asset("0"), "other", "test")).hasMessageContaining("already funds");
        assertThatThrownBy(() -> assets.create(asset("10"), "asset", "test")).hasMessageContaining("different details");
        var request = new FixedAssetService.Depreciation(end);
        String entry = assets.depreciate(id, request, "oct", "test");
        assertThat(assets.depreciate(id, request, "oct", "test")).isEqualTo(entry);
        assertThatThrownBy(() -> assets.depreciate(id, request, "other", "test")).hasMessageContaining("already depreciated");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThat(assets.depreciate(id, request, "oct", "test")).isEqualTo(entry);
        assertThat(count("journal_lines")).isEqualTo(6);
    }
    @Test void unknownSkippedAndClosedPeriodsCannotDepreciate() {
        String id = assets.create(asset("0"), "asset", "test");
        assertThatThrownBy(() -> assets.depreciate(id, new FixedAssetService.Depreciation(LocalDate.of(2026, 11, 30)), "skip", "test")).hasMessageContaining("earlier");
        assertThatThrownBy(() -> assets.depreciate(id, new FixedAssetService.Depreciation(end.minusDays(1)), "bad", "test")).hasMessageContaining("scheduled");
        assertThatThrownBy(() -> assets.depreciate("missing", new FixedAssetService.Depreciation(end), "bad", "test")).hasMessageContaining("not found");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> assets.depreciate(id, new FixedAssetService.Depreciation(end), "closed", "test")).hasMessageContaining("closed period");
        assertThat(count("journal_lines")).isEqualTo(4);
    }
    @Test void invalidLivesResidualValuesDatesAndClosedFundingAreRejected() {
        for (String residual : new String[]{"-1", "100", "101", "1.001", "", "x"})
            assertThatThrownBy(() -> assets.create(asset(residual), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        for (var bad : List.of(new FixedAssetService.Asset(expense, null, 3, "Equipment", "0"),
                new FixedAssetService.Asset(expense, start.plusDays(1), 3, "Equipment", "0"),
                new FixedAssetService.Asset(expense, start.minusMonths(1), 3, "Equipment", "0"),
                new FixedAssetService.Asset(expense, start, 0, "Equipment", "0"),
                new FixedAssetService.Asset(expense, start, 601, "Equipment", "0"),
                new FixedAssetService.Asset(expense, LocalDate.of(9999, 12, 1), 3, "Equipment", "0"),
                new FixedAssetService.Asset(expense, start, 600, "Equipment", "99.99"),
                new FixedAssetService.Asset(expense, start, 3, " ", "0")))
            assertThatThrownBy(() -> assets.create(bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> assets.create(asset("0"), "closed", "test")).hasMessageContaining("closed period");
        assertThat(count("fixed_assets")).isZero();
    }
    @Test void aPurchaseCannotFundBothPrepaidAndFixedAssetRecordsOrBeReversed() {
        String id = prepaid.create(new PrepaidService.Plan(expense, start, 3, "Benefit"), "prepaid", "test");
        assertThatThrownBy(() -> assets.create(asset("0"), "asset", "test")).hasMessageContaining("prepaid plan");
        prepaid.correct(id, new PrepaidService.Correction("Wrong classification"), "correct", "test");
        assets.create(asset("0"), "asset", "test");
        assertThatThrownBy(() -> purchases.reverseExpense(expense, start, "reverse", "test")).hasMessageContaining("fixed asset");
        assertThatThrownBy(() -> prepaid.create(new PrepaidService.Plan(expense, start, 3, "Benefit"), "other", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("fixed_assets")).isEqualTo(1);
    }
    @Test void inconsistentSourceAndAuditFailuresRollBackAssetAndDepreciation() {
        db.update("UPDATE journal_lines SET credit = 99 WHERE account_code = '1000'");
        assertThatThrownBy(() -> assets.create(asset("0"), "bad", "test")).hasMessageContaining("inconsistent");
        db.update("UPDATE journal_lines SET credit = 100 WHERE account_code = '1000'");
        assertThatThrownBy(() -> assets.create(asset("0"), "bad", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("fixed_assets")).isZero(); assertThat(count("asset_periods")).isZero();
        String id = assets.create(asset("0"), "asset", "test");
        assertThatThrownBy(() -> assets.depreciate(id, new FixedAssetService.Depreciation(end), "bad", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("journal_lines")).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM asset_periods WHERE entry_id IS NOT NULL", Integer.class)).isZero();
        assertThat(count("commands")).isEqualTo(3);
    }
    @Test void bothEndpointsRequireAuthenticationCsrfAndRequestKeys() throws Exception {
        check("/api/assets", "{\"expenseId\":\"" + expense + "\",\"inServiceOn\":\"2026-10-01\",\"months\":3,\"name\":\"Equipment\",\"residualValue\":\"0\"}", "asset");
        String id = db.queryForObject("SELECT id FROM fixed_assets", String.class);
        check("/api/assets/" + id + "/depreciate", "{\"periodOn\":\"2026-10-31\"}", "oct");
    }
    private void check(String url, String body, String key) throws Exception {
        http.perform(post(url).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isUnauthorized());
        http.perform(post(url).with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isForbidden());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isOk());
    }
}
