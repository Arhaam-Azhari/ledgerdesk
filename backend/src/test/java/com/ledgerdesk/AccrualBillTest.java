package com.ledgerdesk;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
class AccrualBillTest {
    @Autowired AccrualService accruals;
    @Autowired PurchaseService purchases;
    @Autowired ReportService reports;
    @Autowired LedgerService ledger;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate october = LocalDate.of(2026, 10, 31), november = LocalDate.of(2026, 11, 1);
    private String vendor, accrual;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Professional services supplier", "accounts@example.test"), "vendor", "test");
        accrual = accruals.post(new AccrualService.Accrual(october, "October fees pending bill", "5200", "125.37"), "accrual", "test");
    }
    private AccrualService.BillArrival bill(String amount) {
        return new AccrualService.BillArrival(vendor, "FEES-100", "Actual October professional fees", november, november.plusDays(30), amount);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void noHandoff() {
        assertThat(count("accrual_bills")).isZero();
        assertThat(count("accrual_reversals")).isZero();
        assertThat(count("journal_lines")).isEqualTo(2);
    }
    @Test void equalBillMovesLiabilityWithoutRecognizingTheExpenseTwice() {
        var earlier = reports.reports(october, october);
        String id = accruals.receiveBill(accrual, bill("125.37"), "handoff", "test");
        assertThat(reports.reports(october, october)).isEqualTo(earlier);
        var current = reports.reports(november, november);
        assertThat(current.profitLoss().expenses()).isEqualByComparingTo("0");
        assertThat(current.balanceSheet().totalLiabilities()).isEqualByComparingTo("125.37");
        assertThat(current.balanceSheet().assets()).allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("0"));
        assertThat(current.balanceSheet().liabilities()).anySatisfy(a -> { assertThat(a.code()).isEqualTo("2100"); assertThat(a.amount()).isEqualByComparingTo("0"); });
        assertThat(current.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThat(current.payables().items()).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(id));
        assertThat(current.payables().total()).isEqualByComparingTo("125.37");
        assertThat(count("journal_lines")).isEqualTo(6);
        assertThat(count("commands")).isEqualTo(3);
        @SuppressWarnings("unchecked") var history = (List<Map<String,Object>>) ledger.state().get("accruals");
        assertThat(history).singleElement().satisfies(a -> {
            assertThat(a.get("bill_id")).isEqualTo(id);
            assertThat(a.get("bill_reference")).isEqualTo("FEES-100");
            assertThat(a.get("bill_status")).isEqualTo("POSTED");
            assertThat(a.get("reversal_id")).isNotNull();
        });
        assertThat(db.queryForObject("SELECT account_code FROM bills WHERE id = ?", String.class, id)).isEqualTo("5200");
    }
    @Test void largerActualBillRecognizesOnlyTheDifferenceInTheLaterPeriod() {
        accruals.receiveBill(accrual, bill("140"), "handoff", "test");
        assertThat(reports.reports(november, november).profitLoss().expenses()).isEqualByComparingTo("14.63");
        assertThat(reports.reports(october, november).profitLoss().expenses()).isEqualByComparingTo("140");
        assertThat(reports.reports(october, november).balanceSheet().difference()).isEqualByComparingTo("0");
    }
    @Test void smallerActualBillReducesExpenseByTheDifference() {
        accruals.receiveBill(accrual, bill("100"), "handoff", "test");
        assertThat(reports.reports(november, november).profitLoss().expenses()).isEqualByComparingTo("-25.37");
        assertThat(reports.reports(october, november).profitLoss().expenses()).isEqualByComparingTo("100");
    }
    @Test void retryReturnsTheSameBillAfterClosingAndRejectsOtherDetails() {
        String id = accruals.receiveBill(accrual, bill("140"), "handoff", "test");
        reconciliation.close(new BankReconciliation.Statement(october, november, "0", "0"), "close", "test");
        assertThat(accruals.receiveBill(accrual, bill("140"), "handoff", "test")).isEqualTo(id);
        assertThatThrownBy(() -> accruals.receiveBill(accrual, bill("141"), "handoff", "test")).hasMessageContaining("different details");
        assertThatThrownBy(() -> accruals.receiveBill(accrual, bill("140"), "other", "test")).hasMessageContaining("already reversed");
        assertThat(count("bills")).isEqualTo(1);
        assertThat(count("accrual_bills")).isEqualTo(1);
        assertThat(count("journal_lines")).isEqualTo(6);
    }
    @Test void earlierAndClosedBillDatesAreRejectedButClosedOriginalIsPreserved() {
        var earlier = new AccrualService.BillArrival(vendor, "EARLY", "Fees", october.minusDays(1), november, "140");
        assertThatThrownBy(() -> accruals.receiveBill(accrual, earlier, "early", "test")).hasMessageContaining("precede");
        reconciliation.close(new BankReconciliation.Statement(october, october, "0", "0"), "close", "test");
        var closed = new AccrualService.BillArrival(vendor, "CLOSED", "Fees", october, november, "140");
        assertThatThrownBy(() -> accruals.receiveBill(accrual, closed, "closed", "test")).hasMessageContaining("closed period");
        noHandoff();
        var snapshot = reports.reports(october, october);
        accruals.receiveBill(accrual, bill("140"), "handoff", "test");
        assertThat(reports.reports(october, october)).isEqualTo(snapshot);
    }
    @Test void invalidBillDetailsRollBackTheEstimateReversal() {
        for (String amount : List.of("0", "-1", "1.001", "1e2", "1000000000000"))
            assertThatThrownBy(() -> accruals.receiveBill(accrual, bill(amount), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        for (var invalid : List.of(new AccrualService.BillArrival("missing", "REF", "Fees", november, november, "1"),
                new AccrualService.BillArrival(vendor, "REF", " ", november, november, "1"),
                new AccrualService.BillArrival(vendor, "REF", "x".repeat(241), november, november, "1"),
                new AccrualService.BillArrival(vendor, " ", "Fees", november, november, "1"),
                new AccrualService.BillArrival(vendor, "x".repeat(81), "Fees", november, november, "1"),
                new AccrualService.BillArrival(vendor, "REF", "Fees", november, october, "1"),
                new AccrualService.BillArrival(vendor, "REF", "Fees", null, november, "1"),
                new AccrualService.BillArrival(vendor, "REF", "Fees", november, LocalDate.of(10000, 1, 1), "1")))
            assertThatThrownBy(() -> accruals.receiveBill(accrual, invalid, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accruals.receiveBill(accrual, null, "bad", "test")).isInstanceOf(IllegalArgumentException.class);
        noHandoff();
        assertThat(count("bills")).isZero();
        assertThat(count("commands")).isEqualTo(2);
    }
    @Test void duplicateVendorReferenceCannotLeaveAnUnpairedReversal() {
        var request = bill("140");
        purchases.postBill(new PurchaseService.Bill(vendor, "fees-100", "Already entered", november, november, "5200", "140"), "existing", "test");
        assertThatThrownBy(() -> accruals.receiveBill(accrual, request, "handoff", "test")).hasMessageContaining("already recorded");
        assertThat(count("accrual_reversals")).isZero();
        assertThat(count("accrual_bills")).isZero();
        assertThat(count("bills")).isEqualTo(1);
        assertThat(count("journal_lines")).isEqualTo(4);
    }
    @Test void manualReversalsUnknownAccrualsAndDamagedJournalsCannotBeHandedOff() {
        assertThatThrownBy(() -> accruals.receiveBill("missing", bill("140"), "unknown", "test")).hasMessageContaining("not found");
        db.update("UPDATE journal_lines SET credit = 125 WHERE account_code = '2100'");
        assertThatThrownBy(() -> accruals.receiveBill(accrual, bill("140"), "bad", "test")).hasMessageContaining("inconsistent");
        noHandoff();
        db.update("UPDATE journal_lines SET credit = 125.37 WHERE account_code = '2100'");
        accruals.reverse(accrual, new AccrualService.Reversal(november, "Already handled manually"), "manual", "test");
        assertThatThrownBy(() -> accruals.receiveBill(accrual, bill("140"), "handoff", "test")).hasMessageContaining("already reversed");
        assertThat(count("bills")).isZero();
    }
    @Test void failedActivityWriteRollsBackBillReversalLinkAndCommand() {
        assertThatThrownBy(() -> accruals.receiveBill(accrual, bill("140"), "handoff", "x".repeat(101))).isInstanceOf(RuntimeException.class);
        noHandoff();
        assertThat(count("bills")).isZero();
        assertThat(count("commands")).isEqualTo(2);
        assertThat(count("audit_events")).isEqualTo(2);
    }
    @Test void linkedBillUsesNormalPaymentsAndVoidingWithoutRestoringTheEstimate() {
        String id = accruals.receiveBill(accrual, bill("140"), "handoff", "test");
        purchases.payBill(id, new LedgerService.Payment(november.plusDays(1), "40"), "payment", "test");
        var paid = reports.reports(october, november.plusDays(1));
        assertThat(paid.payables().total()).isEqualByComparingTo("100");
        assertThat(paid.profitLoss().expenses()).isEqualByComparingTo("140");
        assertThat(paid.balanceSheet().totalAssets()).isEqualByComparingTo("-40");
        assertThat(paid.balanceSheet().difference()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> purchases.voidBill(id, november.plusDays(2), "void", "test")).hasMessageContaining("unpaid");
        String second = accruals.post(new AccrualService.Accrual(october, "Other estimate", "5100", "20"), "second", "test");
        String other = accruals.receiveBill(second, new AccrualService.BillArrival(vendor, "OTHER", "Other fees", november, november, "25"), "other", "test");
        purchases.voidBill(other, november.plusDays(2), "void", "test");
        assertThat(db.queryForObject("SELECT b.status FROM accrual_bills l JOIN bills b ON b.id = l.bill_id WHERE l.accrual_id = ?", String.class, second)).isEqualTo("VOID");
        assertThat(count("accrual_reversals")).isEqualTo(2);
        assertThat(reports.reports(october, november.plusDays(2)).profitLoss().expenses()).isEqualByComparingTo("140");
    }
    @Test void endpointRequiresAuthenticationCsrfAndRequestKey() throws Exception {
        String url = "/api/accruals/" + accrual + "/bill";
        String body = "{\"vendorId\":\"" + vendor + "\",\"reference\":\"FEES-100\",\"description\":\"Actual fees\",\"issuedOn\":\"2026-11-01\",\"dueOn\":\"2026-12-01\",\"amount\":\"140\"}";
        http.perform(post(url).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "handoff")).andExpect(status().isUnauthorized());
        http.perform(post(url).with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "handoff")).andExpect(status().isForbidden());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post(url).with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "handoff")).andExpect(status().isOk());
    }
    @Test void competingBillSubmissionsProduceOnlyOneLinkedBill() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String key : List.of("first", "second")) tasks.add(executor.submit(() -> {
                start.await();
                try { accruals.receiveBill(accrual, bill("140"), key, "test"); return true; }
                catch (IllegalArgumentException expected) { assertThat(expected).hasMessageContaining("already reversed"); return false; }
            }));
            start.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(count("bills")).isEqualTo(1);
            assertThat(count("accrual_bills")).isEqualTo(1);
            assertThat(count("accrual_reversals")).isEqualTo(1);
            assertThat(count("journal_lines")).isEqualTo(6);
        } finally { executor.shutdownNow(); }
    }
}
