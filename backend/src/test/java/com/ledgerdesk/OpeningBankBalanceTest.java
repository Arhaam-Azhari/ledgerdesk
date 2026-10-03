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
class OpeningBankBalanceTest {
    @Autowired OpeningBankBalance opening;
    @Autowired LedgerService ledger;
    @Autowired EquityService equity;
    @Autowired ReportService reports;
    @Autowired CashActivityService cash;
    @Autowired BankReconciliation reconciliation;
    @Autowired BankService bank;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    final LocalDate asOf = LocalDate.of(2026, 9, 30), start = asOf.plusDays(1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    OpeningBankBalance.Opening request(String balance) { return new OpeningBankBalance.Opening(asOf, balance, "Cleared bank balance from prior books"); }
    BankReconciliation.Statement statement(LocalDate first, String balance) { return new BankReconciliation.Statement(first, end, balance, "1000.25"); }

    @Test void openingUsesEquityWithoutProfitOrCurrentCashReceipts() {
        opening.post(request("1000.25"), "opening", "test");
        var r = reports.reports(start, end);
        assertThat(r.profitLoss().netProfit()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().totalAssets()).isEqualByComparingTo("1000.25");
        assertThat(r.balanceSheet().postedEquity()).isEqualByComparingTo("1000.25");
        assertThat(r.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(reports.reports(asOf.minusDays(1), asOf.minusDays(1)).balanceSheet().totalAssets()).isEqualByComparingTo("0");
        var c = cash.report(start, end);
        assertThat(c.openingCash()).isEqualByComparingTo("1000.25");
        assertThat(c.closingCash()).isEqualByComparingTo("1000.25");
        assertThat(c.receipts()).isEqualByComparingTo("0");
        assertThat(c.movements()).isEmpty();
        assertThatThrownBy(() -> cash.report(asOf, end)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void firstStatementCarriesOpeningWithoutAnOutstandingDeposit() {
        opening.post(request("1000.25"), "opening", "test");
        var p = reconciliation.preview(statement(start, "1000.25"));
        assertThat(p.bookBalance()).isEqualByComparingTo("1000.25");
        assertThat(p.bookDifference()).isEqualByComparingTo("0");
        assertThat(p.outstandingEntries()).isEmpty();
        reconciliation.close(statement(start, "1000.25"), "close", "test");
        reconciliation.close(new BankReconciliation.Statement(end.plusDays(1), LocalDate.of(2026, 11, 30), "1000.25", "1000.25"), "next", "test");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations", Integer.class)).isEqualTo(2);
    }
    @Test void wrongFirstStartOrOpeningCannotClose() {
        opening.post(request("1000.25"), "opening", "test");
        assertThatThrownBy(() -> reconciliation.close(statement(start.plusDays(1), "1000.25"), "skip", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliation.close(statement(start, "0"), "wrong", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations", Integer.class)).isZero();
    }
    @Test void zeroOpeningRetainsCutoffWithoutZeroJournalLines() {
        opening.post(request("0"), "opening", "test");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isZero();
        assertThatThrownBy(() -> equity.post(new EquityService.Transfer("CONTRIBUTION", asOf, "Old funding", "10"), "old", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bank.importCsv(new BankService.Import("Old rows", "transaction_id,date,description,amount\nOLD,2026-09-30,Old funding,10"), "old-bank", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_imports", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM equity_transactions", Integer.class)).isZero();
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
    }
    @Test void exactRetrySurvivesCloseAndChangedDetailsAreRejected() {
        String id = opening.post(request("1000.25"), "opening", "test");
        reconciliation.close(statement(start, "1000.25"), "close", "test");
        assertThat(opening.post(request("1000.25"), "opening", "test")).isEqualTo(id);
        assertThatThrownBy(() -> opening.post(request("1000"), "opening", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opening.post(request("1000.25"), "another", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM opening_bank_balances", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(2);
    }
    @Test void existingPostingImportOrReconciliationPreventsSetup() {
        equity.post(new EquityService.Transfer("CONTRIBUTION", start, "New funding", "10"), "fund", "test");
        assertThatThrownBy(() -> opening.post(request("100"), "opening", "test")).isInstanceOf(IllegalArgumentException.class);
        DatabaseFixture.reset(db);
        bank.importCsv(new BankService.Import("Statement", "transaction_id,date,description,amount\nNEW,2026-10-01,Funding,10"), "bank", "test");
        assertThatThrownBy(() -> opening.post(request("100"), "opening", "test")).isInstanceOf(IllegalArgumentException.class);
        DatabaseFixture.reset(db);
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> opening.post(request("100"), "opening", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM opening_bank_balances", Integer.class)).isZero();
    }
    @Test void invalidInputLeavesNoOpeningOrJournal() {
        for (String amount : new String[]{"-1", "1.001", "1e3", "1000000000000", ""})
            assertThatThrownBy(() -> opening.post(request(amount), "invalid", "test")).isInstanceOf(IllegalArgumentException.class);
        for (var request : List.of(new OpeningBankBalance.Opening(null, "1", "Memo"), new OpeningBankBalance.Opening(LocalDate.of(0, 1, 1), "1", "Memo"), new OpeningBankBalance.Opening(LocalDate.of(9999, 12, 31), "1", "Memo"), new OpeningBankBalance.Opening(asOf, "1", " ")))
            assertThatThrownBy(() -> opening.post(request, "invalid", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM opening_bank_balances", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isZero();
    }
    @Test void failedAuditRollsBackOpeningJournalAndCommand() {
        assertThatThrownBy(() -> opening.post(request("100"), "opening", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        for (String table : List.of("opening_bank_balances", "journal_entries", "journal_lines", "commands", "audit_events"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
    }
    @Test void concurrentSetupRetainsOnlyOneOpening() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.<java.util.concurrent.Callable<Boolean>>of(
                () -> { try { opening.post(request("100"), "one", "test"); return true; } catch (IllegalArgumentException error) { return false; } },
                () -> { try { opening.post(request("200"), "two", "test"); return true; } catch (IllegalArgumentException error) { return false; } });
            var results = executor.invokeAll(tasks);
            assertThat(results.get(0).get() ^ results.get(1).get()).isTrue();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM opening_bank_balances", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }
    @Test void endpointProtectsWritesAndProvidesReadOnlyMetadata() throws Exception {
        String body = "{\"asOf\":\"2026-09-30\",\"balance\":\"100\",\"memo\":\"Prior cleared bank balance\"}";
        http.perform(post("/api/opening-bank-balance").with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "opening")).andExpect(status().isUnauthorized());
        http.perform(post("/api/opening-bank-balance").with(httpBasic("reviewer", "reviewer-test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "opening")).andExpect(status().isForbidden());
        http.perform(post("/api/opening-bank-balance").with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "opening")).andExpect(status().isForbidden());
        http.perform(post("/api/opening-bank-balance").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "opening")).andExpect(status().isOk());
        http.perform(get("/api/opening-bank-balance").with(httpBasic("reviewer", "reviewer-test-only"))).andExpect(status().isOk()).andExpect(jsonPath("$.openingBankBalances[0].balance").value("100.00"));
    }
}
