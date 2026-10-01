package com.ledgerdesk;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
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
class BankMatchingTest {
    @Autowired BankMatching matching;
    @Autowired BankService bank;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate date = LocalDate.of(2026, 10, 1);
    private String vendor;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
    }
    private String statement(String externalId, String amount, LocalDate posted) {
        bank.importCsv(new BankService.Import("Statement", "transaction_id,date,description,amount\n" + externalId + "," + posted + ",Bank description," + amount), "import-" + externalId, "test");
        return db.queryForObject("SELECT id FROM bank_transactions WHERE external_id = ?", String.class, externalId);
    }
    private String line(String source) { return db.queryForObject("SELECT l.id FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? AND l.account_code = '1000'", String.class, source); }
    private String expense(String amount, String key, LocalDate spent) { return purchases.postExpense(new PurchaseService.Expense(vendor, "Software", spent, "5100", amount), key, "test"); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void customerBillAndExpenseMatchesLeaveTheJournalUnchanged() {
        String invoice = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", date, date, "700"), "invoice", "test");
        String payment = ledger.recordPayment(invoice, new LedgerService.Payment(date, "700"), "customer-pay", "test");
        String bill = purchases.postBill(new PurchaseService.Bill(vendor, "SUP-1", "Supplies", date, date, "5000", "600"), "bill", "test");
        String billPayment = purchases.payBill(bill, new LedgerService.Payment(date, "200"), "bill-pay", "test");
        String expense = expense("50", "expense", date);
        var before = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        String incoming = statement("IN", "700", date.plusDays(1));
        String outgoing = statement("OUT", "-200", date.plusDays(1));
        String software = statement("SOFTWARE", "-50", date);
        assertThat(matching.candidates(incoming).get(0).get("kind")).isEqualTo("Customer payment");
        assertThat(matching.candidates(outgoing).get(0).get("kind")).isEqualTo("Bill payment");
        matching.match(incoming, new BankMatching.Match(line(payment)), "m1", "test");
        matching.match(outgoing, new BankMatching.Match(line(billPayment)), "m2", "test");
        matching.match(software, new BankMatching.Match(line(expense)), "m3", "test");
        assertThat(count("bank_matches")).isEqualTo(3);
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(before);
        assertThat(matching.candidates(incoming)).isEmpty();
        assertThat(ledger.state().get("bankMatches")).isInstanceOf(java.util.List.class);
    }
    @Test void wrongSignAmountNonBankLineAndMissingRecordsAreRejected() {
        String expense = expense("50", "e", date);
        String bank = statement("OUT", "-51", date);
        assertThatThrownBy(() -> matching.match(bank, new BankMatching.Match(line(expense)), "wrong", "test")).hasMessageContaining("same signed amount");
        String incoming = statement("IN", "50", date);
        assertThatThrownBy(() -> matching.match(incoming, new BankMatching.Match(line(expense)), "sign", "test")).hasMessageContaining("same signed amount");
        String nonBank = db.queryForObject("SELECT id FROM journal_lines WHERE account_code = '5100'", String.class);
        assertThatThrownBy(() -> matching.match(bank, new BankMatching.Match(nonBank), "nonbank", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matching.match("missing", new BankMatching.Match(line(expense)), "missing", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("bank_matches")).isZero();
    }
    @Test void candidatesSortByDateAndKeepEqualAmountAlternativesForReview() {
        String older = expense("50", "older", date.minusDays(30));
        String close = expense("50", "close", date.minusDays(1));
        String bank = statement("OUT", "-50", date);
        var candidates = matching.candidates(bank);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).get("line_id")).isEqualTo(line(close));
        assertThat(candidates.get(0).get("near_date")).isEqualTo(true);
        assertThat(candidates.get(1).get("near_date")).isEqualTo(false);
        matching.match(bank, new BankMatching.Match(line(older)), "reviewed", "test");
    }
    @Test void aLedgerLineAndStatementRowCannotBeMatchedTwice() {
        String first = expense("50", "e1", date), second = expense("50", "e2", date);
        String a = statement("A", "-50", date), b = statement("B", "-50", date);
        matching.match(a, new BankMatching.Match(line(first)), "match", "test");
        assertThatThrownBy(() -> matching.match(a, new BankMatching.Match(line(second)), "rowtwice", "test")).hasMessageContaining("already matched");
        assertThatThrownBy(() -> matching.match(b, new BankMatching.Match(line(first)), "linetwice", "test")).hasMessageContaining("already matched");
        assertThat(matching.candidates(b)).hasSize(1);
    }
    @Test void matchAndUnmatchRetriesDoNotDuplicateHistory() {
        String expense = expense("50", "e", date), bank = statement("A", "-50", date);
        var request = new BankMatching.Match(line(expense));
        String id = matching.match(bank, request, "match", "test");
        assertThat(matching.match(bank, request, "match", "test")).isEqualTo(id);
        var unmatch = new BankMatching.Unmatch(id);
        matching.unmatch(bank, unmatch, "unmatch", "test");
        assertThat(matching.unmatch(bank, unmatch, "unmatch", "test")).isEqualTo(id);
        assertThat(count("bank_matches")).isZero(); assertThat(count("bank_match_events")).isEqualTo(2);
        assertThat(matching.candidates(bank)).hasSize(1);
    }
    @Test void staleUnmatchCannotRemoveANewerMatchEvenToTheSameLine() {
        String expense = expense("50", "e", date), bank = statement("A", "-50", date);
        String old = matching.match(bank, new BankMatching.Match(line(expense)), "old", "test");
        matching.unmatch(bank, new BankMatching.Unmatch(old), "undo", "test");
        String current = matching.match(bank, new BankMatching.Match(line(expense)), "current", "test");
        assertThatThrownBy(() -> matching.unmatch(bank, new BankMatching.Unmatch(old), "stale", "test")).hasMessageContaining("changed");
        assertThat(db.queryForObject("SELECT id FROM bank_matches", String.class)).isEqualTo(current);
    }
    @Test void correctedExpensesAreExcludedAndMatchedExpensesRequireUnmatchingFirst() {
        String expense = expense("50", "e", date), bank = statement("A", "-50", date);
        String id = matching.match(bank, new BankMatching.Match(line(expense)), "match", "test");
        assertThatThrownBy(() -> purchases.reverseExpense(expense, date, "reverse", "test")).hasMessageContaining("Unmatch");
        matching.unmatch(bank, new BankMatching.Unmatch(id), "undo", "test");
        purchases.reverseExpense(expense, date, "reverse", "test");
        assertThat(matching.candidates(bank)).isEmpty();
        assertThatThrownBy(() -> matching.match(bank, new BankMatching.Match(db.queryForObject("SELECT l.id FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_id = ? AND l.account_code = '1000' AND l.credit > 0", String.class, expense)), "bad", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedMatchAndUnmatchRollBackAssociationHistoryAndCommands() {
        String expense = expense("50", "e", date), bank = statement("A", "-50", date);
        assertThatThrownBy(() -> matching.match(bank, new BankMatching.Match(line(expense)), "bad", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("bank_matches")).isZero(); assertThat(count("bank_match_events")).isZero();
        String id = matching.match(bank, new BankMatching.Match(line(expense)), "good", "test");
        assertThatThrownBy(() -> matching.unmatch(bank, new BankMatching.Unmatch(id), "badundo", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("bank_matches")).isEqualTo(1); assertThat(count("bank_match_events")).isEqualTo(1);
    }
    @Test void competingMatchesCannotUseTheSameRecordedEntry() throws Exception {
        String expense = expense("50", "e", date), a = statement("A", "-50", date), b = statement("B", "-50", date);
        String line = line(expense);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> matchAfter(start, a, line, "a"));
            var second = pool.submit(() -> matchAfter(start, b, line, "b")); start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1); assertThat(count("bank_matches")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    private int matchAfter(CountDownLatch start, String transaction, String line, String key) throws Exception {
        start.await();
        try { matching.match(transaction, new BankMatching.Match(line), key, "test"); return 1; }
        catch (IllegalArgumentException expected) { return 0; }
    }
    @Test void matchingEndpointsRequireAuthenticationAndCsrf() throws Exception {
        String expense = expense("50", "e", date), bank = statement("A", "-50", date);
        http.perform(get("/api/bank/transactions/" + bank + "/candidates")).andExpect(status().isUnauthorized());
        http.perform(get("/api/bank/transactions/" + bank + "/candidates").with(httpBasic("test", "test-only"))).andExpect(status().isOk());
        String body = "{\"lineId\":\"" + line(expense) + "\"}";
        http.perform(post("/api/bank/transactions/" + bank + "/match").with(httpBasic("test", "test-only"))
            .contentType("application/json").content(body).header("Idempotency-Key", "http")).andExpect(status().isForbidden());
        http.perform(post("/api/bank/transactions/" + bank + "/match").with(httpBasic("test", "test-only")).with(csrf())
            .contentType("application/json").content(body).header("Idempotency-Key", "http")).andExpect(status().isOk());
    }
}
