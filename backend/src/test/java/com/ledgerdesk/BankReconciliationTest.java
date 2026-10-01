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
class BankReconciliationTest {
    @Autowired BankReconciliation reconciliation;
    @Autowired BankService bank;
    @Autowired BankMatching matching;
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
    private BankReconciliation.Preview preview(String opening, String closing) {
        return reconciliation.preview(new BankReconciliation.Statement(start, end, opening, closing));
    }
    private String expense(String amount, String key, LocalDate date) {
        return purchases.postExpense(new PurchaseService.Expense(vendor, "Supplies", date, "5000", amount), key, "test");
    }
    private String payment(String amount, String key, LocalDate date) {
        String invoice = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", date, date, amount), key + "-invoice", "test");
        return ledger.recordPayment(invoice, new LedgerService.Payment(date, amount), key, "test");
    }
    private String row(String key, String amount, LocalDate date) {
        bank.importCsv(new BankService.Import("Statement", "transaction_id,date,description,amount\n" + key + "," + date + ",Statement item," + amount), "import-" + key, "test");
        return db.queryForObject("SELECT id FROM bank_transactions WHERE external_id = ?", String.class, key);
    }
    private void match(String row, String source, String key) {
        String line = db.queryForObject("SELECT l.id FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? AND l.account_code = '1000'", String.class, source);
        matching.match(row, new BankMatching.Match(line), key, "test");
    }
    @Test void clearedAndOutstandingEntriesExplainTheBalanceWithoutPosting() {
        String paid = payment("500", "paid", start);
        match(row("PAID", "500", start.plusDays(1)), paid, "match");
        payment("125", "pending-deposit", end);
        expense("80", "pending-payment", end);
        var before = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        var result = preview("0", "500");
        assertThat(result.statementDifference()).isEqualByComparingTo("0");
        assertThat(result.bookBalance()).isEqualByComparingTo("545");
        assertThat(result.outstandingDeposits()).isEqualByComparingTo("125");
        assertThat(result.outstandingPayments()).isEqualByComparingTo("80");
        assertThat(result.adjustedBankBalance()).isEqualByComparingTo("545");
        assertThat(result.bookDifference()).isEqualByComparingTo("0");
        assertThat(result.outstandingEntries()).hasSize(2);
        assertThat(result.unmatchedTransactions()).isEmpty();
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(before);
    }
    @Test void missingStatementRowsAndUnrecordedChargesAreSeparateDifferences() {
        row("FEE", "-10", start);
        var result = preview("0", "-15");
        assertThat(result.statementDifference()).isEqualByComparingTo("-5");
        assertThat(result.bookDifference()).isEqualByComparingTo("-15");
        assertThat(result.unmatchedTransactions()).hasSize(1);
    }
    @Test void clearingNextMonthDoesNotRemoveThisMonthsOutstandingPayment() {
        String source = expense("60", "expense", end);
        match(row("NEXT", "-60", end.plusDays(1)), source, "match");
        var result = preview("0", "0");
        assertThat(result.importedMovement()).isEqualByComparingTo("0");
        assertThat(result.outstandingPayments()).isEqualByComparingTo("60");
        assertThat(result.bookDifference()).isEqualByComparingTo("0");
    }
    @Test void bankMatchesToFutureBookEntriesAreFlagged() {
        String source = expense("60", "expense", end.plusDays(1));
        match(row("EARLY", "-60", end), source, "match");
        var result = preview("0", "-60");
        assertThat(result.futureDatedMatches()).hasSize(1);
        assertThat(result.bookBalance()).isEqualByComparingTo("0");
        assertThat(result.bookDifference()).isEqualByComparingTo("-60");
    }
    @Test void earlierUnmatchedRowsRemainVisibleAndPeriodEndpointsAreInclusive() {
        row("OLDER", "20", start.minusDays(1));
        row("FIRST", "5", start); row("LAST", "-3", end); row("LATER", "50", end.plusDays(1));
        var result = preview("20", "22");
        assertThat(result.importedMovement()).isEqualByComparingTo("2");
        assertThat(result.statementDifference()).isEqualByComparingTo("0");
        assertThat(result.unmatchedTransactions()).hasSize(3);
    }
    @Test void reversalsRemainInTheBookBalanceAndOutstandingList() {
        String expense = expense("50", "expense", start);
        purchases.reverseExpense(expense, end, "reverse", "test");
        var result = preview("0", "0");
        assertThat(result.bookBalance()).isEqualByComparingTo("0");
        assertThat(result.outstandingDeposits()).isEqualByComparingTo("50");
        assertThat(result.outstandingPayments()).isEqualByComparingTo("50");
        assertThat(result.outstandingEntries()).hasSize(2);
    }
    @Test void invalidPeriodsAndImpreciseOrOversizedBalancesAreRejected() {
        assertThatThrownBy(() -> reconciliation.preview(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliation.preview(new BankReconciliation.Statement(end, start, "0", "0"))).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[]{"1.001", "1e3", "1000000000000", "NaN", "", "+1"})
            assertThatThrownBy(() -> preview(invalid, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(preview("-2.50", "-2.50").statementDifference()).isEqualByComparingTo("0");
    }
    @Test void previewEndpointRequiresAuthenticationAndCsrfAndDoesNotWrite() throws Exception {
        String body = "{\"startsOn\":\"2026-10-01\",\"endsOn\":\"2026-10-31\",\"openingBalance\":\"0\",\"closingBalance\":\"0\"}";
        http.perform(post("/api/bank/reconciliations/preview").with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        http.perform(post("/api/bank/reconciliations/preview").with(httpBasic("test", "test-only")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/bank/reconciliations/preview").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bookDifference").value("0.00"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isEqualTo(1);
    }
    private BankReconciliation.Statement statement(String closing) {
        return new BankReconciliation.Statement(start, end, "0", closing);
    }
    @Test void closingSavesSnapshotAndRetriesWithoutDuplicatingRecords() {
        String source = payment("100", "payment", start);
        match(row("CLEARED", "100", start), source, "match");
        var request = statement("100");
        String id = reconciliation.close(request, "close", "test");
        assertThat(reconciliation.close(request, "close", "test")).isEqualTo(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations", Integer.class)).isEqualTo(1);
        String snapshot = db.queryForObject("SELECT snapshot FROM bank_reconciliations", String.class);
        assertThat(snapshot).contains("bookBalance", "100.00");
        assertThat(ledger.state().get("bankReconciliations")).isInstanceOf(java.util.List.class);
    }
    @Test void closingRejectsUnmatchedRowsDifferencesAndInvalidOpeningHistory() {
        row("UNMATCHED", "10", start);
        assertThatThrownBy(() -> reconciliation.close(statement("10"), "close", "test")).hasMessageContaining("Resolve");
        assertThatThrownBy(() -> reconciliation.close(statement("11"), "difference", "test")).hasMessageContaining("Resolve");
        assertThatThrownBy(() -> reconciliation.close(new BankReconciliation.Statement(start, end, "5", "15"), "opening", "test")).hasMessageContaining("zero");
        assertThatThrownBy(() -> reconciliation.close(new BankReconciliation.Statement(start.plusDays(1), end, "0", "0"), "history", "test")).hasMessageContaining("beginning");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations", Integer.class)).isZero();
    }
    @Test void closedPeriodsBlockBackdatedPostingsAndAtomicMixedImports() {
        reconciliation.close(statement("0"), "close", "test");
        assertThatThrownBy(() -> expense("5", "backdated", end)).hasMessageContaining("closed period");
        assertThatThrownBy(() -> ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Work", start, end, "10"), "invoice", "test")).hasMessageContaining("closed period");
        assertThatThrownBy(() -> bank.importCsv(new BankService.Import("Mixed", "transaction_id,date,description,amount\nNEW,2026-11-01,New,5\nOLD,2026-10-31,Old,5"), "mixed", "test")).hasMessageContaining("closed period");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_imports", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isZero();
        expense("5", "later", end.plusDays(1));
    }
    @Test void closedMatchesAreProtectedButOutstandingPaymentsCanClearNextMonth() {
        String source = expense("50", "expense", start);
        String transaction = row("PAID", "-50", start);
        match(transaction, source, "match");
        String matchId = db.queryForObject("SELECT id FROM bank_matches", String.class);
        expense("20", "outstanding", end);
        reconciliation.close(statement("-50"), "close", "test");
        assertThatThrownBy(() -> matching.unmatch(transaction, new BankMatching.Unmatch(matchId), "undo", "test")).hasMessageContaining("closed period");
        String pending = db.queryForObject("SELECT id FROM expenses WHERE amount = 20", String.class);
        assertThatThrownBy(() -> purchases.reverseExpense(pending, end.plusDays(1), "reverse", "test")).hasMessageContaining("closed period");
        match(row("LATER", "-20", end.plusDays(1)), pending, "later-match");
        assertThat(preview("0", "-50").outstandingPayments()).isEqualByComparingTo("20");
        // An identical export is safe to retry even though its rows are now closed.
        bank.importCsv(new BankService.Import("Duplicate", "transaction_id,date,description,amount\nPAID,2026-10-01,Statement item,-50"), "duplicate", "test");
    }
    @Test void subsequentStatementsCarryBalancesAndOnlyTheLatestCanReopen() {
        String first = reconciliation.close(statement("0"), "first", "test");
        var next = new BankReconciliation.Statement(end.plusDays(1), end.plusMonths(1), "0", "0");
        assertThatThrownBy(() -> reconciliation.close(statement("0"), "overlap", "test")).hasMessageContaining("carry forward");
        assertThatThrownBy(() -> reconciliation.close(new BankReconciliation.Statement(end.plusDays(2), end.plusMonths(1), "0", "0"), "gap", "test")).hasMessageContaining("carry forward");
        assertThatThrownBy(() -> reconciliation.close(new BankReconciliation.Statement(end.plusDays(1), end.plusMonths(1), "1", "1"), "balance", "test")).hasMessageContaining("carry forward");
        String second = reconciliation.close(next, "second", "test");
        assertThatThrownBy(() -> reconciliation.reopen(first, new BankReconciliation.Reopen(1, "Correction"), "old", "test")).hasMessageContaining("latest");
        reconciliation.reopen(second, new BankReconciliation.Reopen(1, "Correction"), "reopen-second", "test");
        assertThatThrownBy(() -> expense("5", "still-closed", end)).hasMessageContaining("closed period");
        reconciliation.reopen(first, new BankReconciliation.Reopen(1, "Review beginning"), "reopen-first", "test");
        expense("5", "now-open", end);
    }
    @Test void reopeningPreservesSnapshotAndStaleRequestsCannotRemoveAReplacementClose() {
        String first = reconciliation.close(statement("0"), "close", "test");
        String snapshot = db.queryForObject("SELECT snapshot FROM bank_reconciliations WHERE id = ?", String.class, first);
        var request = new BankReconciliation.Reopen(1, "Check source statement");
        assertThatThrownBy(() -> reconciliation.reopen(first, new BankReconciliation.Reopen(2, "Wrong version"), "stale-version", "test")).hasMessageContaining("latest");
        assertThatThrownBy(() -> reconciliation.reopen(first, new BankReconciliation.Reopen(1, " "), "blank", "test")).hasMessageContaining("reason");
        reconciliation.reopen(first, request, "reopen", "test");
        assertThat(reconciliation.reopen(first, request, "reopen", "test")).isEqualTo(first);
        String replacement = reconciliation.close(statement("0"), "replacement", "test");
        assertThatThrownBy(() -> reconciliation.reopen(first, request, "stale", "test")).hasMessageContaining("latest");
        assertThat(db.queryForObject("SELECT snapshot FROM bank_reconciliations WHERE id = ?", String.class, first)).isEqualTo(snapshot);
        assertThat(db.queryForObject("SELECT status FROM bank_reconciliations WHERE id = ?", String.class, replacement)).isEqualTo("CLOSED");
    }
    @Test void failedCloseAndReopenRollBackRecordsAndCommands() {
        assertThatThrownBy(() -> reconciliation.close(statement("0"), "bad", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations", Integer.class)).isZero();
        String id = reconciliation.close(statement("0"), "close", "test");
        assertThatThrownBy(() -> reconciliation.reopen(id, new BankReconciliation.Reopen(1, "Review"), "bad-reopen", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT status FROM bank_reconciliations", String.class)).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands WHERE command_key IN ('bad','bad-reopen')", Integer.class)).isZero();
    }
    @Test void competingCloseAndImportCannotChangeAClosedStatement() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var startSignal = new java.util.concurrent.CountDownLatch(1);
        try {
            var close = pool.submit(() -> {
                startSignal.await();
                try { reconciliation.close(statement("0"), "close", "test"); return 1; }
                catch (IllegalArgumentException expected) { return 0; }
            });
            var imported = pool.submit(() -> {
                startSignal.await();
                try { row("RACE", "10", start); return 1; }
                catch (IllegalArgumentException expected) { return 0; }
            });
            startSignal.countDown();
            assertThat(close.get() + imported.get()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void closeAndReopenEndpointsRequireAuthenticationAndCsrf() throws Exception {
        String body = "{\"startsOn\":\"2026-10-01\",\"endsOn\":\"2026-10-31\",\"openingBalance\":\"0\",\"closingBalance\":\"0\"}";
        http.perform(post("/api/bank/reconciliations").with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "http")).andExpect(status().isUnauthorized());
        http.perform(post("/api/bank/reconciliations").with(httpBasic("test", "test-only")).contentType("application/json").content(body).header("Idempotency-Key", "http")).andExpect(status().isForbidden());
        http.perform(post("/api/bank/reconciliations").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(body).header("Idempotency-Key", "http")).andExpect(status().isOk());
        String id = db.queryForObject("SELECT id FROM bank_reconciliations", String.class);
        String reopen = "{\"version\":1,\"reason\":\"Review statement\"}";
        http.perform(post("/api/bank/reconciliations/" + id + "/reopen").with(httpBasic("test", "test-only")).contentType("application/json").content(reopen).header("Idempotency-Key", "undo")).andExpect(status().isForbidden());
        http.perform(post("/api/bank/reconciliations/" + id + "/reopen").with(httpBasic("test", "test-only")).with(csrf()).contentType("application/json").content(reopen).header("Idempotency-Key", "undo")).andExpect(status().isOk());
    }

    @Test void closedDatesRollBackCustomerPaymentsBillPaymentsAndDraftPosting() {
        var invoice = new LedgerService.Invoice("demo-customer", "Design", start, end, "100");
        String invoiceId = ledger.postInvoice(invoice, "invoice", "test");
        String draft = ledger.createDraft(invoice, "draft", "test");
        var bill = new PurchaseService.Bill(vendor, "SUP-1", "Supplies", start, end, "5000", "80");
        String billId = purchases.postBill(bill, "bill", "test");
        reconciliation.close(statement("0"), "close", "test");
        assertThatThrownBy(() -> ledger.recordPayment(invoiceId, new LedgerService.Payment(end, "10"), "customer-pay", "test")).hasMessageContaining("closed period");
        assertThatThrownBy(() -> purchases.payBill(billId, new LedgerService.Payment(end, "10"), "bill-pay", "test")).hasMessageContaining("closed period");
        assertThatThrownBy(() -> ledger.postDraft(draft, 0, "post-draft", "test")).hasMessageContaining("closed period");
        assertThatThrownBy(() -> purchases.voidBill(billId, end, "void-bill", "test")).hasMessageContaining("closed period");
        assertThat(db.queryForObject("SELECT paid FROM invoices WHERE id = ?", java.math.BigDecimal.class, invoiceId)).isEqualByComparingTo("0");
        assertThat(db.queryForObject("SELECT paid FROM bills WHERE id = ?", java.math.BigDecimal.class, billId)).isEqualByComparingTo("0");
        assertThat(db.queryForObject("SELECT posted_invoice_id FROM invoice_drafts WHERE id = ?", String.class, draft)).isNull();
        ledger.recordPayment(invoiceId, new LedgerService.Payment(end.plusDays(1), "10"), "later-pay", "test");
        purchases.payBill(billId, new LedgerService.Payment(end.plusDays(1), "10"), "later-bill-pay", "test");
    }

}
