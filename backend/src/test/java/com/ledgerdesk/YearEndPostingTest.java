package com.ledgerdesk;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class YearEndPostingTest {
    @Autowired YearEndService years;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired OpeningBankBalance opening;
    @Autowired BankReconciliation bank;
    @Autowired AccountingPeriodService periods;
    @Autowired ReportService reports;
    @Autowired AccountActivityService activity;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    final LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 12, 31);
    String vendor, periodId;
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    void prepare(String revenue, String expenses) {
        opening.post(new OpeningBankBalance.Opening(start.minusDays(1), "1000.25", "Cleared opening"), "opening", "test");
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
        books(revenue, expenses, start, "first");
        reviewed(start, end, "first");
    }
    void books(String revenue, String expenses, LocalDate date, String key) {
        if (!revenue.equals("0")) ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", date, date, revenue), key + "-sale", "test");
        if (!expenses.equals("0")) purchases.postBill(new PurchaseService.Bill(vendor, key, "Supplies", date, date, "5000", expenses), key + "-bill", "test");
    }
    void reviewed(LocalDate first, LocalDate last, String key) {
        bank.close(new BankReconciliation.Statement(first, last, "1000.25", "1000.25"), key + "-bank", "test");
        periodId = periods.close(new AccountingPeriodService.Close(last, "Reviewed annual books"), key + "-period", "test");
    }
    YearEndService.Close request() { return new YearEndService.Close(2026, "Reviewed annual earnings and closing proposal"); }
    java.math.BigDecimal balance(String code, LocalDate cutoff) { return activity.activity(code, start, cutoff).closingBalance(); }

    @Test void postingClearsTemporaryBalancesAndPreservesProfitAndTotalEquity() {
        prepare("100.10", "40.04");
        var before = reports.reports(start, end);
        var comparison = reports.compareProfit(start, end, start.minusYears(1), end.minusYears(1));
        var earlier = reports.reports(start, end.minusDays(1));
        String id = years.close(request(), "close", "test");
        var row = db.queryForMap("SELECT * FROM year_end_closes WHERE id = ?", id);
        assertThat(row.get("snapshot").toString()).contains("60.06", "100.10", periodId);
        assertThat(row.get("review_note")).isEqualTo(request().reviewNote());
        assertThat(row.get("closed_by")).isEqualTo("test");
        assertThat(db.queryForObject("SELECT source_id FROM journal_entries WHERE id = ?", String.class, row.get("entry_id"))).isEqualTo(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines WHERE entry_id = ?", Integer.class, row.get("entry_id"))).isEqualTo(3);
        assertThat(balance("4000", end)).isZero(); assertThat(balance("5000", end)).isZero();
        assertThat(balance("3300", end)).isEqualByComparingTo("-60.06");
        var after = reports.reports(start, end);
        assertThat(after.profitLoss()).isEqualTo(before.profitLoss());
        assertThat(reports.compareProfit(start, end, start.minusYears(1), end.minusYears(1))).isEqualTo(comparison);
        assertThat(reports.reports(start, end.minusDays(1))).isEqualTo(earlier);
        assertThat(after.balanceSheet().totalEquity()).isEqualByComparingTo(before.balanceSheet().totalEquity());
        assertThat(after.balanceSheet().accumulatedEarnings()).isZero();
        assertThat(after.balanceSheet().difference()).isZero();
        assertThat(after.trialBalance().debits()).isEqualByComparingTo(after.trialBalance().credits());
        assertThat(years.preview(2026).blockers()).anyMatch(b -> b.contains("already closed"));
        assertThat(years.preview(2026).retainedEarningsChange()).isEqualByComparingTo("60.06");
        assertThat(years.preview(2026).ready()).isFalse();
    }

    @Test void lossZeroProfitAndEmptyYearCloseWithoutInventingZeroLines() {
        for (String[] amounts : List.of(new String[]{"50.05", "80.08"}, new String[]{"25.25", "25.25"}, new String[]{"0", "0"})) {
            DatabaseFixture.reset(db); prepare(amounts[0], amounts[1]);
            var review = years.preview(2026);
            String id = years.close(request(), "close", "test");
            Object entry = db.queryForObject("SELECT entry_id FROM year_end_closes WHERE id = ?", String.class, id);
            if (amounts[0].equals("0")) assertThat(entry).isNull();
            else assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines WHERE entry_id = ?", Integer.class, entry)).isEqualTo(review.proposedLines().size());
            assertThat(balance("4000", end)).isZero(); assertThat(balance("5000", end)).isZero();
            assertThat(balance("3300", end)).isEqualByComparingTo(review.retainedEarningsChange().negate());
            assertThat(reports.reports(start, end).profitLoss()).isEqualTo(review.profitLoss());
        }
    }

    @Test void nextYearStartsWithClearedAccountsAndRetainedEarningsAccumulate() {
        prepare("100.10", "40.04"); years.close(request(), "close", "test");
        books("20.20", "10.10", start.plusYears(1), "next");
        reviewed(start.plusYears(1), end.plusYears(1), "next");
        var next = years.preview(2027);
        assertThat(next.ready()).isTrue();
        assertThat(next.temporaryAccounts()).allSatisfy(a -> assertThat(a.openingBalance()).isZero());
        years.close(new YearEndService.Close(2027, "Reviewed next year"), "next-close", "test");
        assertThat(balance("3300", end.plusYears(1))).isEqualByComparingTo("-70.16");
        assertThat((List<?>) years.history().get("yearEndCloses")).hasSize(2);
        assertThat(reports.reports(start.plusYears(1), end.plusYears(1)).profitLoss().netProfit()).isEqualByComparingTo("10.10");
        assertThat(reports.reports(start, end.plusYears(1)).profitLoss().netProfit()).isEqualByComparingTo("70.16");
    }

    @Test void retriesAreExactAndDifferentKeysCannotCloseTheYearTwice() {
        prepare("100.10", "40.04");
        String id = years.close(request(), "close", "test");
        var before = ledger.state(); var history = years.history();
        assertThat(years.close(request(), "close", "test")).isEqualTo(id);
        assertThatThrownBy(() -> years.close(new YearEndService.Close(2026, "Different note"), "close", "test")).hasMessageContaining("different details");
        assertThatThrownBy(() -> years.close(request(), "another-close", "test")).hasMessageContaining("blockers");
        assertThat(ledger.state()).isEqualTo(before); assertThat(years.history()).isEqualTo(history);
    }

    @Test void closedEarningsProtectTheirSupportingPeriodAndBackdatedPostings() {
        prepare("100.10", "40.04"); years.close(request(), "close", "test");
        assertThatThrownBy(() -> periods.reopen(periodId, new AccountingPeriodService.Reopen(1, "Change review"), "reopen", "test")).hasMessageContaining("closed earnings year");
        assertThatThrownBy(() -> books("10", "0", end, "old")).hasMessageContaining("closed earnings year");
        books("10", "0", end.plusDays(1), "next");
        assertThat(db.queryForObject("SELECT status FROM accounting_period_closes WHERE id = ?", String.class, periodId)).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands WHERE command_key = 'reopen'", Integer.class)).isZero();
    }

    @Test void invalidOrUnreviewedRequestsAndAuditFailureLeaveNoPartialClose() {
        assertThatThrownBy(() -> years.close(request(), "missing", "test")).hasMessageContaining("blockers");
        assertThatThrownBy(() -> years.close(new YearEndService.Close(0, "Review"), "invalid", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> years.close(new YearEndService.Close(2026, " "), "blank", "test")).isInstanceOf(IllegalArgumentException.class);
        prepare("100.10", "40.04"); var before = ledger.state();
        assertThatThrownBy(() -> years.close(request(), "failed", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(ledger.state()).isEqualTo(before);
        assertThat((List<?>) years.history().get("yearEndCloses")).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands WHERE command_key = 'failed'", Integer.class)).isZero();
    }

    @Test void concurrentRequestsProduceOneCloseAndOneClosingEntry() throws Exception {
        prepare("100.10", "40.04");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.<java.util.concurrent.Callable<Boolean>>of(
                () -> { try { years.close(request(), "one", "test"); return true; } catch (IllegalArgumentException e) { return false; } },
                () -> { try { years.close(request(), "two", "test"); return true; } catch (IllegalArgumentException e) { return false; } });
            assertThat(executor.invokeAll(tasks).stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM year_end_closes", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE memo = 'Earnings close for 2026'", Integer.class)).isEqualTo(1);
    }

    @Test void historyIsSharedButPostingRequiresOwnerCsrfAndARequestKey() throws Exception {
        prepare("100.10", "40.04");
        String body = "{\"year\":2026,\"reviewNote\":\"Reviewed annual earnings\"}";
        http.perform(get("/api/year-end")).andExpect(status().isUnauthorized());
        for (String role : new String[]{"REVIEWER", "BOOKKEEPER"}) {
            http.perform(get("/api/year-end").with(user("reader").roles(role))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            http.perform(post("/api/year-end").with(user("reader").roles(role)).with(csrf()).header("Idempotency-Key", role).contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        http.perform(post("/api/year-end").with(user("owner").roles("OWNER")).header("Idempotency-Key", "owner").contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/year-end").with(user("owner").roles("OWNER")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post("/api/year-end").with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key", "owner").contentType("application/json").content(body)).andExpect(status().isOk());
        http.perform(get("/api/year-end").with(user("reviewer").roles("REVIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.yearEndCloses[0].calendar_year").value(2026)).andExpect(jsonPath("$.yearEndCloses[0].snapshot").isString());
    }
}
