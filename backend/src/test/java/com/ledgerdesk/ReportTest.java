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
}
