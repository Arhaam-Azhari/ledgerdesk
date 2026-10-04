package com.ledgerdesk;

import java.time.LocalDate;
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
class ReportTest {
    @Autowired ReportService reports;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    private String vendor;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
    }
    private String invoice(String amount, LocalDate issued, LocalDate due, String key) {
        return ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", issued, due, amount), key, "test");
    }
    private String bill(String amount, LocalDate issued, LocalDate due, String key) {
        return purchases.postBill(new PurchaseService.Bill(vendor, key, "Supplies", issued, due, "5000", amount), key, "test");
    }
    private ReportService.Reports report() { return reports.reports(start, end); }
    @Test void accrualProfitIsSeparateFromPaymentsAndTrialBalanceIsAsOfEnd() {
        String i = invoice("1200", start, end, "invoice");
        ledger.recordPayment(i, new LedgerService.Payment(start.plusDays(1), "700"), "paid", "test");
        String b = bill("600", start, end, "bill");
        purchases.payBill(b, new LedgerService.Payment(start.plusDays(1), "200"), "bill-paid", "test");
        purchases.postExpense(new PurchaseService.Expense(vendor, "Software", end, "5100", "50"), "expense", "test");
        var r = report();
        assertThat(r.profitLoss().revenue()).isEqualByComparingTo("1200");
        assertThat(r.profitLoss().expenses()).isEqualByComparingTo("650");
        assertThat(r.profitLoss().netProfit()).isEqualByComparingTo("550");
        assertThat(r.trialBalance().debits()).isEqualByComparingTo("1600");
        assertThat(r.trialBalance().credits()).isEqualByComparingTo("1600");
        assertThat(r.receivables().total()).isEqualByComparingTo("500");
        assertThat(r.payables().total()).isEqualByComparingTo("400");
    }
    @Test void periodProfitAndCumulativeTrialBalanceHaveDifferentCutoffs() {
        invoice("100", start.minusDays(1), start, "before");
        invoice("200", start, end, "first");
        invoice("300", end, end, "last");
        invoice("900", end.plusDays(1), end.plusDays(1), "future");
        var r = report();
        assertThat(r.profitLoss().revenue()).isEqualByComparingTo("500");
        assertThat(r.trialBalance().debits()).isEqualByComparingTo("600");
        assertThat(r.receivables().total()).isEqualByComparingTo("600");
    }
    @Test void historicalAgingIgnoresLaterCustomerAndVendorPayments() {
        String i = invoice("100", start, start, "invoice");
        String b = bill("80", start, start, "bill");
        ledger.recordPayment(i, new LedgerService.Payment(end.plusDays(1), "100"), "paid", "test");
        purchases.payBill(b, new LedgerService.Payment(end.plusDays(1), "80"), "bill-paid", "test");
        assertThat(report().receivables().total()).isEqualByComparingTo("100");
        assertThat(report().payables().total()).isEqualByComparingTo("80");
        var later = reports.reports(start, end.plusDays(1));
        assertThat(later.receivables().items()).isEmpty(); assertThat(later.payables().items()).isEmpty();
    }
    @Test void laterVoidsDoNotEraseHistoricalProfitOrAging() {
        String i = invoice("100", start, end, "invoice");
        String b = bill("80", start, end, "bill");
        ledger.voidInvoice(i, end.plusDays(1), "void-invoice", "test");
        purchases.voidBill(b, end.plusDays(1), "void-bill", "test");
        var before = report();
        assertThat(before.profitLoss().netProfit()).isEqualByComparingTo("20");
        assertThat(before.receivables().total()).isEqualByComparingTo("100");
        assertThat(before.payables().total()).isEqualByComparingTo("80");
        var after = reports.reports(start, end.plusDays(1));
        assertThat(after.profitLoss().netProfit()).isEqualByComparingTo("0");
        assertThat(after.receivables().items()).isEmpty(); assertThat(after.payables().items()).isEmpty();
    }
    @Test void reversalsInALaterPeriodAppearAsNegativeIncomeOrExpense() {
        String i = invoice("100", start.minusDays(1), start, "invoice");
        String b = bill("80", start.minusDays(1), start, "bill");
        ledger.voidInvoice(i, start, "void-invoice", "test");
        purchases.voidBill(b, start, "void-bill", "test");
        assertThat(report().profitLoss().revenue()).isEqualByComparingTo("-100");
        assertThat(report().profitLoss().expenses()).isEqualByComparingTo("-80");
    }
    @Test void agingBucketsIncludeDueTodayAndExactOverdueBoundaries() {
        for (int days : new int[]{-1, 0, 1, 30, 31, 60, 61, 90, 91}) {
            LocalDate due = end.minusDays(days);
            invoice("10", due.minusDays(1), due, "invoice-" + days);
            bill("10", due.minusDays(1), due, "bill-" + days);
        }
        for (var aging : java.util.List.of(report().receivables(), report().payables())) {
            assertThat(aging.buckets().get("Current")).isEqualByComparingTo("20");
            assertThat(aging.buckets().get("1–30 days")).isEqualByComparingTo("20");
            assertThat(aging.buckets().get("31–60 days")).isEqualByComparingTo("20");
            assertThat(aging.buckets().get("61–90 days")).isEqualByComparingTo("20");
            assertThat(aging.buckets().get("Over 90 days")).isEqualByComparingTo("10");
            assertThat(aging.total()).isEqualByComparingTo("90");
        }
    }
    @Test void draftsAndFutureExpensesAreExcludedAndReadsLeaveBooksUnchanged() {
        ledger.createDraft(new LedgerService.Invoice("demo-customer", "Draft", start, end, "500"), "draft", "test");
        purchases.postExpense(new PurchaseService.Expense(vendor, "Future", end.plusDays(1), "5100", "25"), "expense", "test");
        var before = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        var r = report();
        assertThat(r.profitLoss().netProfit()).isEqualByComparingTo("0");
        assertThat(r.trialBalance().debits()).isEqualByComparingTo("0");
        assertThat(r.receivables().items()).isEmpty();
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(before);
    }
    @Test void reportEndpointRequiresLoginValidDatesAndDisablesCaching() throws Exception {
        http.perform(get("/api/reports?startsOn=2026-10-01&endsOn=2026-10-31")).andExpect(status().isUnauthorized());
        http.perform(get("/api/reports?startsOn=2026-10-01&endsOn=2026-10-31").with(httpBasic("test", "test-only")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.profitLoss.netProfit").value("0.00"));
        http.perform(get("/api/reports?startsOn=2026-10-31&endsOn=2026-10-01").with(httpBasic("test", "test-only"))).andExpect(status().isBadRequest());
        http.perform(get("/api/reports?startsOn=bad&endsOn=2026-10-01").with(httpBasic("test", "test-only"))).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> reports.reports(null, end)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void balanceSheetExplainsAssetsWithLiabilitiesAndAccumulatedEarnings() {
        String i = invoice("1200", start, end, "invoice");
        ledger.recordPayment(i, new LedgerService.Payment(start, "700"), "paid", "test");
        String b = bill("600", start, end, "bill");
        purchases.payBill(b, new LedgerService.Payment(start, "200"), "bill-paid", "test");
        purchases.postExpense(new PurchaseService.Expense(vendor, "Software", end, "5100", "50"), "expense", "test");
        var balance = report().balanceSheet();
        assertThat(balance.totalAssets()).isEqualByComparingTo("950");
        assertThat(balance.totalLiabilities()).isEqualByComparingTo("400");
        assertThat(balance.accumulatedEarnings()).isEqualByComparingTo("550");
        assertThat(balance.postedEquity()).isEqualByComparingTo("0");
        assertThat(balance.totalEquity()).isEqualByComparingTo("550");
        assertThat(balance.liabilitiesAndEquity()).isEqualByComparingTo("950");
        assertThat(balance.difference()).isEqualByComparingTo("0");
    }
    @Test void accumulatedEarningsIncludeEarlierPeriodsAndIgnoreReportStart() {
        invoice("100", start.minusMonths(1), start, "earlier");
        invoice("200", start, end, "current");
        var r = report();
        assertThat(r.profitLoss().netProfit()).isEqualByComparingTo("200");
        assertThat(r.balanceSheet().accumulatedEarnings()).isEqualByComparingTo("300");
        assertThat(reports.reports(end, end).balanceSheet()).isEqualTo(r.balanceSheet());
    }
    @Test void laterPaymentsChangeLaterAssetCompositionButNotEarlierBalanceSheets() {
        String i = invoice("100", start, end, "invoice");
        var earlier = report().balanceSheet();
        ledger.recordPayment(i, new LedgerService.Payment(end.plusDays(1), "100"), "paid", "test");
        assertThat(report().balanceSheet()).isEqualTo(earlier);
        var later = reports.reports(start, end.plusDays(1)).balanceSheet();
        assertThat(later.totalAssets()).isEqualByComparingTo("100");
        assertThat(later.accumulatedEarnings()).isEqualByComparingTo("100");
        assertThat(later.assets()).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("1000"); assertThat(a.amount()).isEqualByComparingTo("100");
        });
    }
    @Test void negativeCashAndLossRemainVisibleRatherThanBeingClamped() {
        purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start, "5100", "25.50"), "expense", "test");
        var balance = report().balanceSheet();
        assertThat(balance.totalAssets()).isEqualByComparingTo("-25.50");
        assertThat(balance.accumulatedEarnings()).isEqualByComparingTo("-25.50");
        assertThat(balance.difference()).isEqualByComparingTo("0");
    }
    @Test void expenseCorrectionAffectsOnlyBalanceSheetsOnOrAfterItsDate() {
        String expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start, "5100", "25"), "expense", "test");
        var before = report().balanceSheet();
        purchases.reverseExpense(expense, end.plusDays(1), "reverse", "test");
        assertThat(report().balanceSheet()).isEqualTo(before);
        var after = reports.reports(start, end.plusDays(1)).balanceSheet();
        assertThat(after.totalAssets()).isEqualByComparingTo("0");
        assertThat(after.accumulatedEarnings()).isEqualByComparingTo("0");
        assertThat(after.difference()).isEqualByComparingTo("0");
    }
    @Test void emptyAndExactDecimalBalanceSheetsPreserveTheEquation() {
        assertThat(report().balanceSheet().difference()).isEqualByComparingTo("0");
        invoice("0.10", start, end, "first"); invoice("0.20", end, end, "last");
        invoice("100", end.plusDays(1), end.plusDays(1), "future");
        var balance = report().balanceSheet();
        assertThat(balance.totalAssets()).isEqualByComparingTo("0.30");
        assertThat(balance.accumulatedEarnings()).isEqualByComparingTo("0.30");
        assertThat(balance.difference()).isEqualByComparingTo("0");
    }
    @Test void equationDifferenceExposesAnUnbalancedUnsupportedDatabaseWrite() {
        db.update("INSERT INTO journal_entries VALUES ('broken', 1, ?, 'Diagnostic fixture', 'broken')", start);
        db.update("INSERT INTO journal_lines VALUES ('broken-line', 'broken', '1000', 5, 0)");
        assertThat(report().balanceSheet().difference()).isEqualByComparingTo("5");
        assertThat(report().balanceSheet().accumulatedEarnings()).isEqualByComparingTo("0");
    }

    @Test void profitComparisonUsesInclusiveAccrualPeriodsAndExactCategoryChangesWithoutWriting() {
        LocalDate previousStart = start.minusMonths(1), previousEnd = start.minusDays(1);
        String oldInvoice = invoice("100.10", previousStart, previousEnd, "old-first");
        invoice("200.20", previousEnd, previousEnd, "old-last");
        invoice("500.50", start, end, "new-first");
        invoice("600.60", end, end, "new-last");
        invoice("999", end.plusDays(1), end.plusDays(1), "future");
        ledger.createDraft(new LedgerService.Invoice("demo-customer", "Draft", start, end, "900"), "draft", "test");
        bill("100.15", previousStart, previousEnd, "old-bill");
        purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start, "5100", "50.10"), "new-expense", "test");
        ledger.recordPayment(oldInvoice, new LedgerService.Payment(start, "100.10"), "old-paid", "test");
        var lines = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        var activity = db.queryForList("SELECT * FROM audit_events ORDER BY id");
        var commands = db.queryForList("SELECT * FROM commands ORDER BY command_key");
        var result = reports.compareProfit(start, end, previousStart, previousEnd);
        assertThat(result.current().profitLoss()).isEqualTo(report().profitLoss());
        assertThat(result.previous().profitLoss()).isEqualTo(reports.reports(previousStart, previousEnd).profitLoss());
        assertThat(result.change().revenue()).isEqualByComparingTo("800.80");
        assertThat(result.change().expenses()).isEqualByComparingTo("-50.05");
        assertThat(result.change().netProfit()).isEqualByComparingTo("850.85");
        assertThat(result.change().accounts()).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("5000"); assertThat(a.amount()).isEqualByComparingTo("-100.15");
        }).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("5100"); assertThat(a.amount()).isEqualByComparingTo("50.10");
        });
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(lines);
        assertThat(db.queryForList("SELECT * FROM audit_events ORDER BY id")).isEqualTo(activity);
        assertThat(db.queryForList("SELECT * FROM commands ORDER BY command_key")).isEqualTo(commands);
    }

    @Test void comparisonKeepsDatedReversalsSignedInTheirOwnPeriod() {
        LocalDate previousStart = start.minusMonths(1), previousEnd = start.minusDays(1);
        String i = invoice("100.10", previousEnd, previousEnd, "old-invoice");
        String b = bill("80.05", previousEnd, previousEnd, "old-bill");
        ledger.voidInvoice(i, start, "void-invoice", "test");
        purchases.voidBill(b, start, "void-bill", "test");
        var comparison = reports.compareProfit(start, end, previousStart, previousEnd);
        assertThat(comparison.previous().profitLoss().netProfit()).isEqualByComparingTo("20.05");
        assertThat(comparison.current().profitLoss().revenue()).isEqualByComparingTo("-100.10");
        assertThat(comparison.current().profitLoss().expenses()).isEqualByComparingTo("-80.05");
        assertThat(comparison.change().revenue()).isEqualByComparingTo("-200.20");
        assertThat(comparison.change().expenses()).isEqualByComparingTo("-160.10");
        assertThat(comparison.change().netProfit()).isEqualByComparingTo("-40.10");
    }

    @Test void comparisonAcceptsUnequalPeriodsAndGapsWithoutIncludingGapPostings() {
        LocalDate previousStart = LocalDate.of(2024, 2, 29), currentStart = LocalDate.of(2024, 4, 1);
        invoice("10.25", previousStart, previousStart, "leap-day");
        invoice("999", previousStart.plusDays(1), previousStart.plusDays(1), "gap");
        invoice("15.35", currentStart.plusDays(1), currentStart.plusDays(1), "current");
        var comparison = reports.compareProfit(currentStart, currentStart.plusDays(1), previousStart, previousStart);
        assertThat(comparison.previous().startsOn()).isEqualTo(previousStart);
        assertThat(comparison.previous().endsOn()).isEqualTo(previousStart);
        assertThat(comparison.current().endsOn()).isEqualTo(currentStart.plusDays(1));
        assertThat(comparison.change().netProfit()).isEqualByComparingTo("5.10");
    }

    @Test void emptyComparisonRetainsEveryCategoryAndSupportsDateLimits() {
        var comparison = reports.compareProfit(LocalDate.of(9999, 12, 31), LocalDate.of(9999, 12, 31),
                LocalDate.of(1, 1, 1), LocalDate.of(1, 1, 1));
        assertThat(comparison.change().revenue()).isEqualByComparingTo("0.00");
        assertThat(comparison.change().expenses()).isEqualByComparingTo("0.00");
        assertThat(comparison.change().netProfit()).isEqualByComparingTo("0.00");
        assertThat(comparison.change().accounts()).hasSameSizeAs(comparison.current().profitLoss().accounts());
        assertThat(comparison.change().accounts()).allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("0.00"));
    }

    @Test void comparisonRejectsInvalidOverlappingAndReversedPeriods() {
        LocalDate previousStart = start.minusMonths(1), previousEnd = start.minusDays(1);
        assertThatThrownBy(() -> reports.compareProfit(null, end, previousStart, previousEnd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, null, previousStart, previousEnd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, end, null, previousEnd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, end, previousStart, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(end, start, previousStart, previousEnd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, end, previousEnd, previousStart)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, end, previousStart, start)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, end, end.plusDays(1), end.plusDays(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, LocalDate.of(10000, 1, 1), previousStart, previousEnd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.compareProfit(start, end, LocalDate.of(0, 1, 1), previousEnd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void comparisonEndpointRequiresLoginAllowsAllReadingRolesAndValidatesParameters() throws Exception {
        String path = "/api/reports/profit-comparison?startsOn=2026-10-01&endsOn=2026-10-31"
                + "&previousStartsOn=2026-09-01&previousEndsOn=2026-09-30";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : java.util.List.of("OWNER", "BOOKKEEPER", "REVIEWER"))
            http.perform(get(path).with(user("reader").roles(role)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.previous.endsOn").value("2026-09-30"))
                .andExpect(jsonPath("$.current.startsOn").value("2026-10-01"))
                .andExpect(jsonPath("$.change.netProfit").value("0.00"));
        http.perform(get(path).with(httpBasic("test", "test-only"))).andExpect(status().isOk());
        http.perform(get(path.replace("previousEndsOn=2026-09-30", "previousEndsOn=2026-10-01")).with(user("reader").roles("REVIEWER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("The previous period must end before the current period starts."));
        http.perform(get(path.replace("previousStartsOn=2026-09-01", "previousStartsOn=bad")).with(user("reader").roles("REVIEWER"))).andExpect(status().isBadRequest());
        http.perform(get(path.replace("&previousEndsOn=2026-09-30", "")).with(user("reader").roles("REVIEWER"))).andExpect(status().isBadRequest());
    }
}
