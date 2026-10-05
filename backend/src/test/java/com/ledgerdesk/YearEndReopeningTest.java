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
class YearEndReopeningTest {
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

    YearEndService.Reopen reason() { return new YearEndService.Reopen(1, "Review missed supplier document"); }

    @Test void reversalRestoresBalancesAndPreservesProfitSnapshotsAndEarlierReports() {
        prepare("100.10", "40.04");
        var financial = reports.reports(start, end);
        var comparison = reports.compareProfit(start, end, start.minusYears(1), end.minusYears(1));
        var earlier = reports.reports(start, end.minusDays(1));
        String id = years.close(request(), "close", "test");
        var original = db.queryForMap("SELECT * FROM year_end_closes WHERE id = ?", id);
        years.reopen(id, reason(), "reopen", "test");
        var row = db.queryForMap("SELECT * FROM year_end_closes WHERE id = ?", id);
        assertThat(row.get("snapshot")).isEqualTo(original.get("snapshot"));
        assertThat(row.get("entry_id")).isEqualTo(original.get("entry_id"));
        assertThat(row.get("status")).isEqualTo("REOPENED");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(2);
        assertThat(row.get("active_year")).isNull();
        assertThat(row.get("reopen_reason")).isEqualTo(reason().reason());
        assertThat(row.get("reopened_by")).isEqualTo("test");
        var offsets = db.queryForList("SELECT account_code, debit, credit FROM journal_lines WHERE entry_id = ? ORDER BY account_code", original.get("entry_id"));
        var reversed = db.queryForList("SELECT account_code, debit, credit FROM journal_lines WHERE entry_id = ? ORDER BY account_code", row.get("reversal_entry_id"));
        assertThat(reversed).hasSameSizeAs(offsets);
        for (int i = 0; i < offsets.size(); i++) {
            assertThat(reversed.get(i).get("account_code")).isEqualTo(offsets.get(i).get("account_code"));
            assertThat(reversed.get(i).get("debit")).isEqualTo(offsets.get(i).get("credit"));
            assertThat(reversed.get(i).get("credit")).isEqualTo(offsets.get(i).get("debit"));
        }
        assertThat(reports.reports(start, end)).isEqualTo(financial);
        assertThat(reports.reports(start, end.minusDays(1))).isEqualTo(earlier);
        assertThat(reports.compareProfit(start, end, start.minusYears(1), end.minusYears(1))).isEqualTo(comparison);
        assertThat(years.preview(2026).ready()).isTrue();
    }

    @Test void correctionsNeedSupportingReviewsReopenedAndReclosingKeepsEveryCycle() {
        prepare("100.10", "40.04"); String original = years.close(request(), "close", "test");
        String snapshot = db.queryForObject("SELECT snapshot FROM year_end_closes WHERE id = ?", String.class, original);
        String bankId = db.queryForObject("SELECT bank_reconciliation_id FROM year_end_closes WHERE id = ?", String.class, original);
        years.reopen(original, reason(), "reopen", "test");
        assertThatThrownBy(() -> books("10.10", "0", end, "blocked")).hasMessageContaining("closed accounting period");
        periods.reopen(periodId, new AccountingPeriodService.Reopen(1, "Review missed document"), "period-reopen", "test");
        assertThat(years.preview(2026).ready()).isFalse();
        bank.reopen(bankId, new BankReconciliation.Reopen(1, "Recheck supporting statement"), "bank-reopen", "test");
        books("10.10", "0", end, "correction");
        reviewed(start, end, "replacement");
        String replacement = years.close(request(), "replacement-close", "test");
        assertThat(replacement).isNotEqualTo(original);
        assertThat(balance("3300", end)).isEqualByComparingTo("-70.16");
        assertThat(db.queryForObject("SELECT snapshot FROM year_end_closes WHERE id = ?", String.class, original)).isEqualTo(snapshot);
        assertThat(years.close(request(), "close", "test")).isEqualTo(original);
        assertThat(years.reopen(original, reason(), "reopen", "test")).isEqualTo(original);
        assertThat(years.preview(2026).ready()).isFalse();
        years.reopen(replacement, reason(), "replacement-reopen", "test");
        years.close(request(), "third-close", "test");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM year_end_closes WHERE status = 'REOPENED'", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM year_end_closes WHERE status = 'CLOSED'", Integer.class)).isEqualTo(1);
        assertThat(reports.reports(start, end).profitLoss().netProfit()).isEqualByComparingTo("70.16");
    }

    @Test void laterPeriodAndLaterEarningsClosesMustBeReopenedInOrder() {
        prepare("100.10", "40.04"); String first = years.close(request(), "close", "test");
        books("20.20", "10.10", start.plusYears(1), "next");
        reviewed(start.plusYears(1), end.plusYears(1), "next");
        String laterPeriod = periodId;
        assertThatThrownBy(() -> years.reopen(first, reason(), "blocked-period", "test")).hasMessageContaining("later accounting period");
        String second = years.close(new YearEndService.Close(2027, "Reviewed next year"), "next-close", "test");
        assertThatThrownBy(() -> years.reopen(first, reason(), "blocked-year", "test")).hasMessageContaining("latest closed earnings");
        years.reopen(second, reason(), "next-reopen", "test");
        assertThatThrownBy(() -> years.reopen(first, reason(), "still-blocked", "test")).hasMessageContaining("later accounting period");
        periods.reopen(laterPeriod, new AccountingPeriodService.Reopen(1, "Review earlier correction"), "later-period-reopen", "test");
        years.reopen(first, reason(), "first-reopen", "test");
        assertThat(balance("3300", end.plusYears(1))).isZero();
    }

    @Test void lossAndInactiveYearsReverseWithoutInventingZeroLines() {
        for (String[] amounts : List.of(new String[]{"50.05", "80.08"}, new String[]{"0", "0"})) {
            DatabaseFixture.reset(db); prepare(amounts[0], amounts[1]);
            var before = reports.reports(start, end);
            String id = years.close(request(), "close", "test");
            years.reopen(id, reason(), "reopen", "test");
            assertThat(reports.reports(start, end)).isEqualTo(before);
            assertThat(balance("3300", end)).isZero();
            Object reversal = db.queryForObject("SELECT reversal_entry_id FROM year_end_closes WHERE id = ?", String.class, id);
            if (amounts[0].equals("0")) assertThat(reversal).isNull(); else assertThat(reversal).isNotNull();
        }
    }

    @Test void versionsReasonsAndExactRetriesDoNotAlterAnAlreadyReopenedRecord() {
        prepare("100.10", "40.04"); String id = years.close(request(), "close", "test");
        assertThatThrownBy(() -> years.reopen(id, new YearEndService.Reopen(2, "Stale version"), "stale", "test")).hasMessageContaining("Reload");
        assertThatThrownBy(() -> years.reopen(id, new YearEndService.Reopen(1, " "), "blank", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> years.reopen(id, null, "missing", "test")).isInstanceOf(IllegalArgumentException.class);
        years.reopen(id, reason(), "reopen", "test"); var before = ledger.state(); var history = years.history();
        assertThat(years.reopen(id, reason(), "reopen", "test")).isEqualTo(id);
        assertThatThrownBy(() -> years.reopen(id, new YearEndService.Reopen(1, "Changed reason"), "reopen", "test")).hasMessageContaining("different details");
        assertThatThrownBy(() -> years.reopen(id, reason(), "another", "test")).hasMessageContaining("latest closed earnings");
        assertThat(ledger.state()).isEqualTo(before); assertThat(years.history()).isEqualTo(history);
    }

    @Test void failedReopeningRollsBackTheReversalHistoryAndRequestKey() {
        prepare("100.10", "40.04"); String id = years.close(request(), "close", "test");
        var before = ledger.state(); var history = years.history();
        assertThatThrownBy(() -> years.reopen(id, reason(), "failed", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(ledger.state()).isEqualTo(before); assertThat(years.history()).isEqualTo(history);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands WHERE command_key = 'failed'", Integer.class)).isZero();
    }

    @Test void concurrentReopeningsRetainOnlyOneReversal() throws Exception {
        prepare("100.10", "40.04"); String id = years.close(request(), "close", "test");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.<java.util.concurrent.Callable<Boolean>>of(
                () -> { try { years.reopen(id, reason(), "one", "test"); return true; } catch (IllegalArgumentException e) { return false; } },
                () -> { try { years.reopen(id, reason(), "two", "test"); return true; } catch (IllegalArgumentException e) { return false; } });
            assertThat(executor.invokeAll(tasks).stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE memo = 'Earnings close reversal for 2026'", Integer.class)).isEqualTo(1);
    }

    @Test void reopeningRequiresOwnerCsrfAndKeyAndHistoryRemainsReadable() throws Exception {
        prepare("100.10", "40.04"); String id = years.close(request(), "close", "test");
        String path = "/api/year-end/" + id + "/reopen", body = "{\"version\":1,\"reason\":\"Review missing document\"}";
        for (String role : new String[]{"REVIEWER", "BOOKKEEPER"})
            http.perform(post(path).with(user("reader").roles(role)).with(csrf()).header("Idempotency-Key", role).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post(path).with(user("owner").roles("OWNER")).header("Idempotency-Key", "owner").contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key", "owner").contentType("application/json").content(body)).andExpect(status().isOk());
        http.perform(get("/api/year-end").with(user("reader").roles("REVIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.yearEndCloses[0].status").value("REOPENED")).andExpect(jsonPath("$.yearEndCloses[0].reopen_reason").value("Review missing document"));
    }
}
