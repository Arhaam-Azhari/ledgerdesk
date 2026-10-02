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
class PrepaidTest {
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
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Software supplier", "accounts@example.test"), "vendor", "test");
        expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Three months of software", start, "5100", "100"), "expense", "test");
    }
    private PrepaidService.Plan plan() { return new PrepaidService.Plan(expense, start, 3, "Software October to December"); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Test void conversionPreservesCashAndSchedulesExactFinalRemainder() {
        var cash = db.queryForList("SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? AND l.account_code = '1000'", expense);
        prepaid.create(plan(), "plan", "test");
        assertThat(db.queryForList("SELECT amount FROM prepaid_periods ORDER BY period_on")).extracting(r -> r.get("amount").toString()).containsExactly("33.33", "33.33", "33.34");
        assertThat(db.queryForList("SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? AND l.account_code = '1000'", expense)).isEqualTo(cash);
        var r = reports.reports(start, end);
        assertThat(r.profitLoss().expenses()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1300"); assertThat(a.amount()).isEqualByComparingTo("100"); });
        assertThat(r.balanceSheet().totalAssets()).isEqualByComparingTo("0");
        assertThat(r.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(r.payables().total()).isEqualByComparingTo("0");
        assertThat((List<?>) ledger.state().get("prepaidPlans")).hasSize(1);
        assertThat((List<?>) ledger.state().get("prepaidPeriods")).hasSize(3);
    }
    @Test void recognitionsReduceAssetExactlyAndPreserveEarlierCutoffs() {
        String id = prepaid.create(plan(), "plan", "test");
        prepaid.recognize(id, new PrepaidService.Recognition(end), "oct", "test");
        var october = reports.reports(start, end);
        assertThat(october.profitLoss().expenses()).isEqualByComparingTo("33.33");
        assertThat(october.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1300"); assertThat(a.amount()).isEqualByComparingTo("66.67"); });
        prepaid.recognize(id, new PrepaidService.Recognition(LocalDate.of(2026, 11, 30)), "nov", "test");
        prepaid.recognize(id, new PrepaidService.Recognition(LocalDate.of(2026, 12, 31)), "dec", "test");
        assertThat(reports.reports(start, end)).isEqualTo(october);
        var finalReport = reports.reports(start, LocalDate.of(2026, 12, 31));
        assertThat(finalReport.profitLoss().expenses()).isEqualByComparingTo("100");
        assertThat(finalReport.balanceSheet().assets()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("1300"); assertThat(a.amount()).isEqualByComparingTo("0"); });
        assertThat(finalReport.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(count("journal_lines")).isEqualTo(10);
    }
    @Test void requestsRetryExactlyOnceAndCannotReuseMonthsOrSources() {
        String id = prepaid.create(plan(), "plan", "test");
        assertThat(prepaid.create(plan(), "plan", "test")).isEqualTo(id);
        assertThatThrownBy(() -> prepaid.create(plan(), "other", "test")).hasMessageContaining("already has");
        assertThatThrownBy(() -> prepaid.create(new PrepaidService.Plan(expense, start, 2, "Changed"), "plan", "test")).hasMessageContaining("different details");
        var recognition = new PrepaidService.Recognition(end);
        String result = prepaid.recognize(id, recognition, "oct", "test");
        assertThat(prepaid.recognize(id, recognition, "oct", "test")).isEqualTo(result);
        assertThatThrownBy(() -> prepaid.recognize(id, recognition, "other", "test")).hasMessageContaining("already recognized");
        assertThatThrownBy(() -> prepaid.recognize(id, new PrepaidService.Recognition(LocalDate.of(2026, 11, 30)), "oct", "test")).hasMessageContaining("different details");
        assertThatThrownBy(() -> purchases.reverseExpense(expense, end, "reverse", "test")).hasMessageContaining("prepaid plan");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThat(prepaid.recognize(id, recognition, "oct", "test")).isEqualTo(result);
        assertThat(count("journal_lines")).isEqualTo(6);
    }
    @Test void closedDatesAndSkippingOrUnknownPeriodsCannotPost() {
        String id = prepaid.create(plan(), "plan", "test");
        assertThatThrownBy(() -> prepaid.recognize(id, new PrepaidService.Recognition(LocalDate.of(2026, 11, 30)), "skip", "test")).hasMessageContaining("earlier");
        assertThatThrownBy(() -> prepaid.recognize(id, new PrepaidService.Recognition(end.minusDays(1)), "bad", "test")).hasMessageContaining("scheduled");
        assertThatThrownBy(() -> prepaid.recognize("missing", new PrepaidService.Recognition(end), "bad", "test")).hasMessageContaining("not found");
        // The ledger's cash payment is still present even though the expense is deferred.
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThat(prepaid.create(plan(), "plan", "test")).isEqualTo(id);
        assertThatThrownBy(() -> prepaid.recognize(id, new PrepaidService.Recognition(end), "oct", "test")).hasMessageContaining("closed period");
        assertThat(count("journal_lines")).isEqualTo(4);
    }
    @Test void malformedSchedulesAndClosedFundingDatesAreRejected() {
        for (var bad : List.of(new PrepaidService.Plan(expense, null, 3, "Memo"),
                new PrepaidService.Plan(expense, start.plusDays(1), 3, "Memo"),
                new PrepaidService.Plan(expense, start.minusMonths(1), 3, "Memo"),
                new PrepaidService.Plan(expense, start, 0, "Memo"), new PrepaidService.Plan(expense, start, 61, "Memo"),
                new PrepaidService.Plan(expense, start, 3, " "), new PrepaidService.Plan(expense, start, 3, "x".repeat(241)),
                new PrepaidService.Plan(expense, LocalDate.of(9999, 12, 1), 2, "Memo"),
                new PrepaidService.Plan("missing", start, 3, "Memo")))
            assertThatThrownBy(() -> prepaid.create(bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> prepaid.create(plan(), "plan", "test")).hasMessageContaining("closed period");
        assertThat(count("prepaid_plans")).isZero();
        assertThat(count("journal_lines")).isEqualTo(2);
    }
    @Test void reversedTinyOrInconsistentExpensesCannotFundAPlan() {
        db.update("UPDATE journal_lines SET credit = 99 WHERE account_code = '1000'");
        assertThatThrownBy(() -> prepaid.create(plan(), "bad", "test")).hasMessageContaining("inconsistent");
        db.update("UPDATE journal_lines SET credit = 100 WHERE account_code = '1000'");
        db.update("UPDATE expenses SET amount = 0.01 WHERE id = ?", expense);
        assertThatThrownBy(() -> prepaid.create(plan(), "bad", "test")).hasMessageContaining("one cent");
        db.update("UPDATE expenses SET amount = 100 WHERE id = ?", expense);
        purchases.reverseExpense(expense, start, "reverse", "test");
        assertThatThrownBy(() -> prepaid.create(plan(), "bad", "test")).hasMessageContaining("unreversed");
        assertThat(count("prepaid_plans")).isZero();
    }
    @Test void auditFailuresRollBackPlanScheduleAndRecognition() {
        assertThatThrownBy(() -> prepaid.create(plan(), "bad", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("prepaid_plans")).isZero();
        assertThat(count("prepaid_periods")).isZero();
        assertThat(count("journal_lines")).isEqualTo(2);
        String id = prepaid.create(plan(), "plan", "test");
        assertThatThrownBy(() -> prepaid.recognize(id, new PrepaidService.Recognition(end), "bad", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(count("journal_lines")).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM prepaid_periods WHERE entry_id IS NOT NULL", Integer.class)).isZero();
        assertThat(count("commands")).isEqualTo(3);
    }
    @Test void endpointsRequireAuthenticationCsrfAndRequestKeys() throws Exception {
        String body = "{\"expenseId\":\"" + expense + "\",\"startsOn\":\"2026-10-01\",\"months\":3,\"memo\":\"Software October to December\"}";
        check("/api/prepaid", body, "plan");
        String id = db.queryForObject("SELECT id FROM prepaid_plans", String.class);
        check("/api/prepaid/" + id + "/recognize", "{\"periodOn\":\"2026-10-31\"}", "oct");
    }
    private void check(String url, String body, String key) throws Exception {
        http.perform(post(url).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isUnauthorized());
        http.perform(post(url).with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isForbidden());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", key)).andExpect(status().isOk());
    }
}
