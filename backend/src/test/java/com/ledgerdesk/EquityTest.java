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
class EquityTest {
    @Autowired EquityService equity;
    @Autowired ReportService reports;
    @Autowired LedgerService ledger;
    @Autowired BankService bank;
    @Autowired BankMatching matching;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private EquityService.Transfer transfer(String kind, String amount) {
        return new EquityService.Transfer(kind, start, "Personal savings", amount);
    }
    @Test void fundingAndDrawingsAffectEquityWithoutRecognizingProfit() {
        equity.post(transfer("CONTRIBUTION", "1000.01"), "fund", "test");
        equity.post(transfer("DRAWING", "200.01"), "draw", "test");
        var r = reports.reports(start, end);
        assertThat(r.profitLoss().netProfit()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().totalAssets()).isEqualByComparingTo("800");
        assertThat(r.balanceSheet().postedEquity()).isEqualByComparingTo("800");
        assertThat(r.balanceSheet().accumulatedEarnings()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(r.receivables().items()).isEmpty();
        assertThat(r.payables().items()).isEmpty();
        assertThat(r.trialBalance().debits()).isEqualByComparingTo("1000.01");
        assertThat(r.trialBalance().credits()).isEqualByComparingTo("1000.01");
        assertThat((java.util.List<?>) ledger.state().get("equityTransactions")).hasSize(2);
    }
    @Test void futureDrawingsDoNotChangeAnEarlierBalanceSheet() {
        equity.post(transfer("CONTRIBUTION", "500"), "fund", "test");
        equity.post(new EquityService.Transfer("DRAWING", end.plusDays(1), "Owner transfer", "100"), "draw", "test");
        assertThat(reports.reports(end, end).balanceSheet().totalEquity()).isEqualByComparingTo("500");
        assertThat(reports.reports(end, end.plusDays(1)).balanceSheet().totalEquity()).isEqualByComparingTo("400");
    }
    @Test void retryIsExactlyOnceAndChangedDetailsCannotReuseAKey() {
        var request = transfer("CONTRIBUTION", "100");
        String id = equity.post(request, "same", "test");
        assertThat(equity.post(request, "same", "test")).isEqualTo(id);
        assertThatThrownBy(() -> equity.post(transfer("DRAWING", "100"), "same", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM equity_transactions", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isEqualTo(1);
    }
    @Test void invalidAmountsKindsDatesAndMemosLeaveNoRecords() {
        for (String amount : new String[]{"0", "-10", "1.001", "1e3", "1000000000000"})
            assertThatThrownBy(() -> equity.post(transfer("CONTRIBUTION", amount), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        for (var request : java.util.List.of(
                transfer("REVENUE", "10"), new EquityService.Transfer(null, start, "Memo", "10"),
                new EquityService.Transfer("DRAWING", null, "Memo", "10"),
                new EquityService.Transfer("DRAWING", LocalDate.of(0, 1, 1), "Memo", "10"),
                new EquityService.Transfer("DRAWING", start, " ", "10")))
            assertThatThrownBy(() -> equity.post(request, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM equity_transactions", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class)).isZero();
    }
    @Test void failureAfterJournalPostingRollsBackTransferAndCommand() {
        assertThatThrownBy(() -> equity.post(transfer("CONTRIBUTION", "10"), "bad-actor", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM equity_transactions", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isZero();
    }
    private String match(String id, String external, String amount) {
        bank.importCsv(new BankService.Import("Owner statement", "transaction_id,date,description,amount\n" + external + ",2026-10-01,Owner transfer," + amount), "import-" + external, "test");
        String row = db.queryForObject("SELECT id FROM bank_transactions WHERE external_id = ?", String.class, external);
        var candidates = matching.candidates(row);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("memo").toString()).contains("Owner", "Personal savings");
        String line = db.queryForObject("SELECT l.id FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? AND l.account_code = '1000'", String.class, id);
        matching.match(row, new BankMatching.Match(line), "match-" + external, "test");
        return row;
    }
    @Test void ownerTransfersCanBeMatchedAndReconciledInBothDirections() {
        String fund = equity.post(transfer("CONTRIBUTION", "1000"), "fund", "test");
        String draw = equity.post(transfer("DRAWING", "200"), "draw", "test");
        match(fund, "FUND", "1000");
        match(draw, "DRAW", "-200");
        var statement = new BankReconciliation.Statement(start, end, "0", "800");
        var preview = reconciliation.preview(statement);
        assertThat(preview.bookDifference()).isEqualByComparingTo("0");
        assertThat(preview.outstandingEntries()).isEmpty();
        assertThat(preview.unmatchedTransactions()).isEmpty();
        reconciliation.close(statement, "close", "test");
        assertThatThrownBy(() -> equity.post(transfer("CONTRIBUTION", "50"), "late", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closed period");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM equity_transactions", Integer.class)).isEqualTo(2);
        assertThat(equity.post(transfer("CONTRIBUTION", "1000"), "fund", "test")).isEqualTo(fund);
    }
    @Test void outstandingOwnerFundingExplainsTheBankDifference() {
        equity.post(transfer("CONTRIBUTION", "1000"), "fund", "test");
        var preview = reconciliation.preview(new BankReconciliation.Statement(start, end, "0", "0"));
        assertThat(preview.outstandingDeposits()).isEqualByComparingTo("1000");
        assertThat(preview.bookDifference()).isEqualByComparingTo("0");
        assertThat(preview.outstandingEntries()).hasSize(1);
        assertThat(preview.outstandingEntries().get(0).get("memo").toString()).contains("Owner contribution", "Personal savings");
    }
    @Test void endpointRequiresAuthenticationCsrfAndARequestKey() throws Exception {
        String body = "{\"kind\":\"CONTRIBUTION\",\"postedOn\":\"2026-10-01\",\"memo\":\"Personal savings\",\"amount\":\"100\"}";
        http.perform(post("/api/equity").with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "post")).andExpect(status().isUnauthorized());
        http.perform(post("/api/equity").with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "post")).andExpect(status().isForbidden());
        http.perform(post("/api/equity").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post("/api/equity").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "post")).andExpect(status().isOk());
    }
}
