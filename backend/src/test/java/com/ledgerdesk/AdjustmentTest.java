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
class AdjustmentTest {
    @Autowired AdjustmentService adjustments;
    @Autowired PurchaseService purchases;
    @Autowired LedgerService ledger;
    @Autowired ReportService reports;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private AdjustmentService.Adjustment request(LocalDate date) {
        return new AdjustmentService.Adjustment(date, "Split setup purchase into its proper categories", List.of(
                new AdjustmentService.Line("5100", "100.01", "0"),
                new AdjustmentService.Line("5200", "49.99", "0"),
                new AdjustmentService.Line("5000", "0", "150.00")));
    }
    private String expense() {
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Setup supplier", "accounts@example.test"), "vendor", "test");
        return purchases.postExpense(new PurchaseService.Expense(vendor, "Setup purchase", start, "5000", "150"), "expense", "test");
    }
    @Test void balancedMultiLineReclassificationChangesCategoriesWithoutChangingCashOrProfit() {
        String expense = expense();
        var before = reports.reports(start, end);
        adjustments.post(request(start), "adjust", "test");
        var after = reports.reports(start, end);
        assertThat(after.profitLoss().expenses()).isEqualByComparingTo("150");
        assertThat(after.profitLoss().netProfit()).isEqualByComparingTo(before.profitLoss().netProfit());
        assertThat(after.balanceSheet().totalAssets()).isEqualByComparingTo(before.balanceSheet().totalAssets());
        assertThat(after.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(after.receivables()).isEqualTo(before.receivables());
        assertThat(after.payables()).isEqualTo(before.payables());
        assertThat(after.profitLoss().accounts()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("5100"); assertThat(a.amount()).isEqualByComparingTo("100.01"); });
        assertThat(after.profitLoss().accounts()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("5000"); assertThat(a.amount()).isEqualByComparingTo("0"); });
        assertThat(db.queryForObject("SELECT account_code FROM expenses WHERE id = ?", String.class, expense)).isEqualTo("5000");
        assertThat((List<?>) ledger.state().get("adjustments")).hasSize(1);
        assertThat((List<?>) ledger.state().get("adjustmentLines")).hasSize(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(5);
    }
    @Test void laterAdjustmentKeepsEarlierReportsAndAppearsInItsOwnPeriod() {
        expense();
        adjustments.post(request(end.plusDays(1)), "adjust", "test");
        var earlier = reports.reports(start, end);
        assertThat(earlier.profitLoss().accounts()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("5000"); assertThat(a.amount()).isEqualByComparingTo("150"); });
        var later = reports.reports(end.plusDays(1), end.plusDays(1));
        assertThat(later.profitLoss().expenses()).isEqualByComparingTo("0");
        assertThat(later.profitLoss().accounts()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("5000"); assertThat(a.amount()).isEqualByComparingTo("-150"); });
        assertThat(later.balanceSheet().accumulatedEarnings()).isEqualByComparingTo("-150");
    }
    @Test void unbalancedAndInvalidMonetaryLinesCannotPost() {
        for (String value : List.of("99.99", "-1", "1.001", "1e2", "1000000000000")) {
            var bad = new AdjustmentService.Adjustment(start, "Invalid amount", List.of(
                    new AdjustmentService.Line("5000", value, "0"), new AdjustmentService.Line("5100", "0", "100")));
            assertThatThrownBy(() -> adjustments.post(bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        }
        for (var line : List.of(new AdjustmentService.Line("5000", "0", "0"), new AdjustmentService.Line("5000", "1", "1")))
            assertThatThrownBy(() -> adjustments.post(new AdjustmentService.Adjustment(start, "Invalid sides", List.of(line, new AdjustmentService.Line("5100", "0", "1"))), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_adjustments", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isZero();
    }
    @Test void controlAccountsUnknownCodesAndDuplicateCategoriesAreRejected() {
        for (String code : List.of("1000", "1100", "2000", "3000", "3100", "4000", "9999", "5100")) {
            var bad = new AdjustmentService.Adjustment(start, "Unsupported account", List.of(
                    new AdjustmentService.Line(code, "1", "0"), new AdjustmentService.Line("5100", "0", "1")));
            assertThatThrownBy(() -> adjustments.post(bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class)).isZero();
    }
    @Test void malformedDatesMemosAndLineCountsLeaveNoAdjustment() {
        var valid = request(start);
        for (var bad : List.of(new AdjustmentService.Adjustment(null, valid.memo(), valid.lines()),
                new AdjustmentService.Adjustment(LocalDate.of(10000, 1, 1), valid.memo(), valid.lines()),
                new AdjustmentService.Adjustment(start, " ", valid.lines()),
                new AdjustmentService.Adjustment(start, "x".repeat(301), valid.lines()),
                new AdjustmentService.Adjustment(start, valid.memo(), List.of()),
                new AdjustmentService.Adjustment(start, valid.memo(), java.util.Collections.nCopies(21, valid.lines().get(0)))))
            assertThatThrownBy(() -> adjustments.post(bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_adjustments", Integer.class)).isZero();
    }
    @Test void retryIsExactlyOnceAndClosedDatesPreventNewAdjustments() {
        var request = request(start);
        String id = adjustments.post(request, "adjust", "test");
        assertThat(adjustments.post(request, "adjust", "test")).isEqualTo(id);
        assertThatThrownBy(() -> adjustments.post(request(end), "adjust", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different details");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThat(adjustments.post(request, "adjust", "test")).isEqualTo(id);
        assertThatThrownBy(() -> adjustments.post(request(end), "new", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closed period");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_adjustments", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(3);
    }
    @Test void failedActivityWriteRollsBackAllLinesAndHeader() {
        assertThatThrownBy(() -> adjustments.post(request(start), "adjust", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_adjustments", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isZero();
    }
    @Test void endpointRequiresAuthenticationCsrfAndARequestKey() throws Exception {
        String body = "{\"postedOn\":\"2026-10-01\",\"memo\":\"Reclassify software\",\"lines\":[{\"accountCode\":\"5100\",\"debit\":\"10\",\"credit\":\"0\"},{\"accountCode\":\"5000\",\"debit\":\"0\",\"credit\":\"10\"}]}";
        http.perform(post("/api/adjustments").with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "post")).andExpect(status().isUnauthorized());
        http.perform(post("/api/adjustments").with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "post")).andExpect(status().isForbidden());
        http.perform(post("/api/adjustments").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post("/api/adjustments").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "post")).andExpect(status().isOk());
    }
    @Test void reversalSwapsEveryOriginalLineAndRetainsTheAllocationHistory() {
        expense();
        String id = adjustments.post(request(start), "adjust", "test");
        var original = db.queryForList("SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? ORDER BY l.account_code", id);
        String reversal = adjustments.reverse(id, new AdjustmentService.Reversal(start, "Wrong category split"), "reverse", "test");
        var offset = db.queryForList("SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? ORDER BY l.account_code", reversal);
        assertThat(offset).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            assertThat(offset.get(i).get("account_code")).isEqualTo(original.get(i).get("account_code"));
            assertThat(offset.get(i).get("debit")).isEqualTo(original.get(i).get("credit"));
            assertThat(offset.get(i).get("credit")).isEqualTo(original.get(i).get("debit"));
        }
        assertThat(db.queryForList("SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? ORDER BY l.account_code", id)).isEqualTo(original);
        var state = ledger.state();
        @SuppressWarnings("unchecked") var history = (List<java.util.Map<String, Object>>) state.get("adjustmentLines");
        assertThat(history).hasSize(6);
        assertThat(history.stream().filter(l -> l.get("entry_kind").equals("ORIGINAL")).count()).isEqualTo(3);
        assertThat(history.stream().filter(l -> l.get("entry_kind").equals("REVERSAL")).count()).isEqualTo(3);
        assertThat(reports.reports(start, end).profitLoss().accounts()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("5000"); assertThat(a.amount()).isEqualByComparingTo("150"); });
    }
    @Test void laterReversalPreservesEarlierCategoryReportsWithoutChangingProfitOrCash() {
        expense();
        String id = adjustments.post(request(start), "adjust", "test");
        var earlier = reports.reports(start, end);
        adjustments.reverse(id, new AdjustmentService.Reversal(end.plusDays(1), "Mistaken allocation"), "reverse", "test");
        assertThat(reports.reports(start, end)).isEqualTo(earlier);
        var later = reports.reports(start, end.plusDays(1));
        assertThat(later.profitLoss().expenses()).isEqualByComparingTo("150");
        assertThat(later.balanceSheet()).isEqualTo(earlier.balanceSheet());
        assertThat(later.profitLoss().accounts()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("5100"); assertThat(a.amount()).isEqualByComparingTo("0"); });
        assertThat(later.receivables()).isEqualTo(earlier.receivables());
        assertThat(later.payables()).isEqualTo(earlier.payables());
    }
    @Test void reversalRetryIsExactlyOnceAndOtherRequestsCannotReverseItAgain() {
        String id = adjustments.post(request(start), "adjust", "test");
        var reversal = new AdjustmentService.Reversal(start, "Wrong category split");
        String result = adjustments.reverse(id, reversal, "reverse", "test");
        assertThat(adjustments.reverse(id, reversal, "reverse", "test")).isEqualTo(result);
        assertThatThrownBy(() -> adjustments.reverse(id, reversal, "another", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already reversed");
        assertThatThrownBy(() -> adjustments.reverse(id, new AdjustmentService.Reversal(start, "Different reason"), "reverse", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different details");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM adjustment_reversals", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(6);
    }
    @Test void invalidReversalDetailsAndUnknownAdjustmentsDoNotPost() {
        String id = adjustments.post(request(start), "adjust", "test");
        for (var bad : List.of(new AdjustmentService.Reversal(null, "Missing date"),
                new AdjustmentService.Reversal(start.minusDays(1), "Earlier than original"),
                new AdjustmentService.Reversal(start, " "), new AdjustmentService.Reversal(start, "x".repeat(241)),
                new AdjustmentService.Reversal(LocalDate.of(10000, 1, 1), "Invalid year")))
            assertThatThrownBy(() -> adjustments.reverse(id, bad, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adjustments.reverse("missing", new AdjustmentService.Reversal(start, "Unknown adjustment"), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM adjustment_reversals", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(3);
    }
    @Test void closedReversalDatesAreBlockedButLaterCorrectionsPreserveClosedHistory() {
        String id = adjustments.post(request(start), "adjust", "test");
        reconciliation.close(new BankReconciliation.Statement(start, end, "0", "0"), "close", "test");
        assertThatThrownBy(() -> adjustments.reverse(id, new AdjustmentService.Reversal(end, "Closed date"), "bad", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closed period");
        var earlier = reports.reports(start, end);
        var reversal = new AdjustmentService.Reversal(end.plusDays(1), "Correct in next open period");
        String result = adjustments.reverse(id, reversal, "reverse", "test");
        assertThat(reports.reports(start, end)).isEqualTo(earlier);
        reconciliation.close(new BankReconciliation.Statement(end.plusDays(1), end.plusDays(1), "0", "0"), "close-next", "test");
        assertThat(adjustments.reverse(id, reversal, "reverse", "test")).isEqualTo(result);
    }
    @Test void failedReversalActivityRollsBackOffsetAndCommand() {
        String id = adjustments.post(request(start), "adjust", "test");
        assertThatThrownBy(() -> adjustments.reverse(id, new AdjustmentService.Reversal(start, "Wrong category split"), "reverse", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM adjustment_reversals", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isEqualTo(1);
    }
    @Test void inconsistentOriginalLinesCannotBeReversedIntoANewUnbalancedJournal() {
        String id = adjustments.post(request(start), "adjust", "test");
        db.update("UPDATE journal_lines SET credit = 149 WHERE account_code = '5000'");
        assertThatThrownBy(() -> adjustments.reverse(id, new AdjustmentService.Reversal(start, "Diagnostic fixture"), "reverse", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not balanced");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM adjustment_reversals", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(3);
    }
    @Test void reversalEndpointRequiresAuthenticationCsrfAndARequestKey() throws Exception {
        String id = adjustments.post(request(start), "adjust", "test");
        String url = "/api/adjustments/" + id + "/reverse";
        String body = "{\"reversedOn\":\"2026-10-01\",\"reason\":\"Wrong category split\"}";
        http.perform(post(url).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "reverse")).andExpect(status().isUnauthorized());
        http.perform(post(url).with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "reverse")).andExpect(status().isForbidden());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "reverse")).andExpect(status().isOk());
    }

}
