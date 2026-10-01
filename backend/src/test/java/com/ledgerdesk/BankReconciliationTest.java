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
}
