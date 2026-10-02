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
class AccrualTest {
    @Autowired AccrualService accruals;
    @Autowired LedgerService ledger;
    @Autowired ReportService reports;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private AccrualService.Accrual request(LocalDate date) {
        return new AccrualService.Accrual(date, "October professional fees; supplier bill pending", "5200", "125.37");
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void accrualRecognizesExpenseAndLiabilityWithoutCashOrVendorAging() {
        var before = reports.reports(start, end);
        String id = accruals.post(request(end), "post", "test");
        var after = reports.reports(start, end);
        assertThat(after.profitLoss().expenses()).isEqualByComparingTo("125.37");
        assertThat(after.profitLoss().netProfit()).isEqualByComparingTo("-125.37");
        assertThat(after.balanceSheet().totalAssets()).isEqualByComparingTo("0");
        assertThat(after.balanceSheet().totalLiabilities()).isEqualByComparingTo("125.37");
        assertThat(after.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(after.trialBalance().debits()).isEqualByComparingTo("125.37");
        assertThat(after.trialBalance().credits()).isEqualByComparingTo("125.37");
        assertThat(after.payables()).isEqualTo(before.payables());
        assertThat(after.receivables()).isEqualTo(before.receivables());
        assertThat((List<?>) ledger.state().get("accruals")).hasSize(1);
        assertThat(db.queryForObject("SELECT source_id FROM journal_entries", String.class)).isEqualTo(id);
        assertThat(count("bills")).isZero();
        assertThat(count("journal_lines")).isEqualTo(2);
    }
    @Test void laterReversalPreservesClosedOctoberAndOffsetsNovemberExpense() {
        String id = accruals.post(request(end), "post", "test");
        var october = reports.reports(start, end);
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        accruals.reverse(id, new AccrualService.Reversal(end.plusDays(1), "Reverse estimate before entering supplier bill"), "reverse", "test");
        assertThat(reports.reports(start, end)).isEqualTo(october);
        var november = reports.reports(end.plusDays(1), end.plusDays(1));
        assertThat(november.profitLoss().expenses()).isEqualByComparingTo("-125.37");
        assertThat(november.balanceSheet().totalLiabilities()).isEqualByComparingTo("0");
        assertThat(november.balanceSheet().accumulatedEarnings()).isEqualByComparingTo("0");
        assertThat(november.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(count("expense_accruals")).isEqualTo(1);
        assertThat(count("accrual_reversals")).isEqualTo(1);
        assertThat(count("journal_lines")).isEqualTo(4);
    }
    @Test void postRetriesAreExactlyOnceEvenAfterThePeriodCloses() {
        String id = accruals.post(request(end), "post", "test");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThat(accruals.post(request(end), "post", "test")).isEqualTo(id);
        assertThatThrownBy(() -> accruals.post(request(start), "post", "test")).hasMessageContaining("different details");
        assertThatThrownBy(() -> accruals.post(request(end), "new", "test")).hasMessageContaining("closed period");
        assertThat(count("expense_accruals")).isEqualTo(1);
    }
    @Test void reversalRetriesRetainOneCorrectionAndRejectChangedDetails() {
        String id = accruals.post(request(end), "post", "test");
        var request = new AccrualService.Reversal(end.plusDays(1), "Estimate replaced by bill");
        String result = accruals.reverse(id, request, "reverse", "test");
        reconciliation.close(new BankReconciliation.Statement(start, end.plusDays(1), "0", "0"), "close", "test");
        assertThat(accruals.reverse(id, request, "reverse", "test")).isEqualTo(result);
        assertThatThrownBy(() -> accruals.reverse(id, request, "another", "test")).hasMessageContaining("already reversed");
        assertThatThrownBy(() -> accruals.reverse(id, new AccrualService.Reversal(request.reversedOn(), "Changed"), "reverse", "test")).hasMessageContaining("different details");
        assertThat(count("journal_lines")).isEqualTo(4);
    }
    @Test void invalidAmountsAndControlAccountsLeaveNoJournal() {
        for (String value : List.of("0", "-1", "1.001", "1e2", "1000000000000"))
            assertThatThrownBy(() -> accruals.post(new AccrualService.Accrual(end, "Fees", "5200", value), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        for (String account : List.of("1000", "1100", "2000", "2100", "3000", "4000", "9999", " 5200"))
            assertThatThrownBy(() -> accruals.post(new AccrualService.Accrual(end, "Fees", account, "1"), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("expense_accruals")).isZero();
        assertThat(count("journal_entries")).isZero();
    }
    @Test void malformedDatesAndMemosCannotPost() {
        for (var bad : List.of(new AccrualService.Accrual(null, "Fees", "5200", "1"),
                new AccrualService.Accrual(LocalDate.of(10000, 1, 1), "Fees", "5200", "1"),
                new AccrualService.Accrual(end, " ", "5200", "1"), new AccrualService.Accrual(end, "x".repeat(241), "5200", "1")))
            assertThatThrownBy(() -> accruals.post(bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accruals.post(null, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("expense_accruals")).isZero();
    }
    @Test void reversalRejectsEarlierClosedUnknownOrMalformedRequests() {
        String id = accruals.post(request(end), "post", "test");
        for (var bad : List.of(new AccrualService.Reversal(start, "Too early"), new AccrualService.Reversal(null, "Missing date"),
                new AccrualService.Reversal(end, " "), new AccrualService.Reversal(end, "x".repeat(241)),
                new AccrualService.Reversal(LocalDate.of(10000, 1, 1), "Invalid year")))
            assertThatThrownBy(() -> accruals.reverse(id, bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accruals.reverse("missing", new AccrualService.Reversal(end, "Unknown"), "bad", "test")).hasMessageContaining("not found");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> accruals.reverse(id, new AccrualService.Reversal(end, "Closed"), "bad", "test")).hasMessageContaining("closed period");
        assertThat(count("accrual_reversals")).isZero();
    }
    @Test void auditFailureRollsBackBothPostingAndReversal() {
        assertThatThrownBy(() -> accruals.post(request(end), "fail", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("expense_accruals")).isZero();
        assertThat(count("journal_lines")).isZero();
        assertThat(count("commands")).isZero();
        String id = accruals.post(request(end), "post", "test");
        assertThatThrownBy(() -> accruals.reverse(id, new AccrualService.Reversal(end, "Wrong estimate"), "fail", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("accrual_reversals")).isZero();
        assertThat(count("journal_lines")).isEqualTo(2);
        assertThat(count("commands")).isEqualTo(1);
    }
    @Test void damagedOriginalJournalCannotProduceAnInvalidReversal() {
        String id = accruals.post(request(end), "post", "test");
        db.update("UPDATE journal_lines SET credit = 125 WHERE account_code = '2100'");
        assertThatThrownBy(() -> accruals.reverse(id, new AccrualService.Reversal(end, "Fixture correction"), "reverse", "test")).hasMessageContaining("inconsistent");
        assertThat(count("accrual_reversals")).isZero();
    }
    @Test void bothEndpointsRequireAuthenticationCsrfAndARequestKey() throws Exception {
        String body = "{\"postedOn\":\"2026-10-31\",\"memo\":\"Fees pending bill\",\"accountCode\":\"5200\",\"amount\":\"125.37\"}";
        checkEndpoint("/api/accruals", body, "post");
        String id = db.queryForObject("SELECT id FROM expense_accruals", String.class);
        checkEndpoint("/api/accruals/" + id + "/reverse", "{\"reversedOn\":\"2026-11-01\",\"reason\":\"Replace estimate\"}", "reverse");
    }
    private void checkEndpoint(String url, String body, String key) throws Exception {
        http.perform(post(url).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isUnauthorized());
        http.perform(post(url).with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isForbidden());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isOk());
    }
}
