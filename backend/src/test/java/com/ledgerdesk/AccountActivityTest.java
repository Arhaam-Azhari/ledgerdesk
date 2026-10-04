package com.ledgerdesk;

import java.time.LocalDate;
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
class AccountActivityTest {
    @Autowired AccountActivityService accounts;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired ReportService reports;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    private String vendor;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
    }
    private String invoice(String amount, LocalDate date, String key) {
        return ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design " + key, date, date, amount), key, "test");
    }
    private AccountActivityService.Activity activity(String code) { return accounts.activity(code, start, end); }
    private void mixedBooks() {
        String old = invoice("100.10", start.minusDays(1), "old");
        ledger.recordPayment(old, new LedgerService.Payment(start.minusDays(1), "60.06"), "opening-payment", "test");
        String current = invoice("200.20", start, "current");
        ledger.recordPayment(current, new LedgerService.Payment(end, "25.25"), "payment", "test");
        purchases.postExpense(new PurchaseService.Expense(vendor, "Software", start.plusDays(1), "5100", "10.10"), "expense", "test");
        String bill = purchases.postBill(new PurchaseService.Bill(vendor, "H1", "Supplies", start, end, "5000", "40.04"), "bill", "test");
        purchases.payBill(bill, new LedgerService.Payment(end, "5.05"), "bill-payment", "test");
    }

    @Test void bankActivityExplainsOpeningExactMovementsAndClosingWithoutWriting() {
        mixedBooks();
        var lines = db.queryForList("SELECT * FROM journal_lines ORDER BY id");
        var audit = db.queryForList("SELECT * FROM audit_events ORDER BY id");
        var commands = db.queryForList("SELECT * FROM commands ORDER BY command_key");
        var a = activity("1000");
        assertThat(a.account().name()).isEqualTo("Business bank");
        assertThat(a.account().kind()).isEqualTo("ASSET");
        assertThat(a.currency()).isEqualTo("USD");
        assertThat(a.openingBalance()).isEqualByComparingTo("60.06");
        assertThat(a.debits()).isEqualByComparingTo("25.25");
        assertThat(a.credits()).isEqualByComparingTo("15.15");
        assertThat(a.closingBalance()).isEqualByComparingTo("70.16");
        assertThat(a.movements()).hasSize(3);
        assertThat(a.movements().get(0).postedOn()).isEqualTo(start.plusDays(1));
        assertThat(a.movements().get(0).balance()).isEqualByComparingTo("49.96");
        for (var movement : a.movements()) {
            var line = db.queryForMap("SELECT * FROM journal_lines WHERE id = ?", movement.lineId());
            assertThat(line.get("entry_id")).isEqualTo(movement.entryId());
            assertThat(line.get("debit")).isEqualTo(movement.debit());
            assertThat(line.get("credit")).isEqualTo(movement.credit());
            assertThat(db.queryForObject("SELECT source_id FROM journal_entries WHERE id = ?", String.class, movement.entryId()))
                    .isEqualTo(movement.sourceId());
        }
        assertThat(db.queryForList("SELECT * FROM journal_lines ORDER BY id")).isEqualTo(lines);
        assertThat(db.queryForList("SELECT * FROM audit_events ORDER BY id")).isEqualTo(audit);
        assertThat(db.queryForList("SELECT * FROM commands ORDER BY command_key")).isEqualTo(commands);
    }

    @Test void debitMinusCreditConventionAgreesWithDatedTrialBalanceAcrossAccountKinds() {
        mixedBooks();
        assertThat(activity("1100").closingBalance()).isEqualByComparingTo("214.99");
        assertThat(activity("4000").openingBalance()).isEqualByComparingTo("-100.10");
        assertThat(activity("4000").closingBalance()).isEqualByComparingTo("-300.30");
        assertThat(activity("2000").closingBalance()).isEqualByComparingTo("-34.99");
        assertThat(activity("5100").closingBalance()).isEqualByComparingTo("10.10");
        for (var row : reports.reports(start, end).trialBalance().accounts()) {
            var a = activity(row.code());
            assertThat(a.closingBalance()).isEqualByComparingTo(row.debit().subtract(row.credit()));
            assertThat(a.openingBalance().add(a.debits()).subtract(a.credits())).isEqualByComparingTo(a.closingBalance());
            if (!a.movements().isEmpty()) assertThat(a.movements().get(a.movements().size() - 1).balance()).isEqualByComparingTo(a.closingBalance());
        }
        purchases.postExpense(new PurchaseService.Expense(vendor, "Extra software", end, "5100", "100"), "extra", "test");
        assertThat(activity("1000").closingBalance()).isEqualByComparingTo("-29.84");
    }

    @Test void laterPaymentsAndReversalsDoNotRewriteEarlierActivityAndDraftsHaveNoLines() {
        String paid = invoice("100", start, "paid");
        String voided = invoice("80", end, "voided");
        ledger.createDraft(new LedgerService.Invoice("demo-customer", "Draft", start, end, "900"), "draft", "test");
        var receivables = activity("1100"); var revenue = activity("4000"); var bank = activity("1000");
        ledger.recordPayment(paid, new LedgerService.Payment(end.plusDays(1), "100"), "future-payment", "test");
        ledger.voidInvoice(voided, end.plusDays(1), "future-void", "test");
        invoice("500", end.plusDays(1), "future-invoice");
        assertThat(activity("1100")).isEqualTo(receivables);
        assertThat(activity("4000")).isEqualTo(revenue);
        assertThat(activity("1000")).isEqualTo(bank);
        var later = accounts.activity("4000", end.plusDays(1), end.plusDays(1));
        assertThat(later.openingBalance()).isEqualByComparingTo("-180");
        assertThat(later.debits()).isEqualByComparingTo("80");
        assertThat(later.credits()).isEqualByComparingTo("500");
        assertThat(later.closingBalance()).isEqualByComparingTo("-600");
    }

    @Test void splitLinesAppearOnceInStableOrderAndForeignBusinessEntriesAreExcluded() {
        db.update("INSERT INTO journal_entries VALUES ('split-entry',1,?,'Split proof','split-source')", start);
        db.update("INSERT INTO journal_lines VALUES ('split-b','split-entry','1000',0.20,0), ('split-a','split-entry','1000',0.10,0), ('split-offset','split-entry','4000',0,0.30)");
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other business','USD')");
        db.update("INSERT INTO journal_entries VALUES ('foreign-entry',2,?,'Private proof','foreign-source')", start.minusDays(1));
        db.update("INSERT INTO journal_lines VALUES ('foreign-bank','foreign-entry','1000',900,0), ('foreign-offset','foreign-entry','4000',0,900)");
        var a = activity("1000");
        assertThat(a.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(a.movements()).extracting(AccountActivityService.Movement::lineId).containsExactly("split-a", "split-b");
        assertThat(a.movements().get(0).balance()).isEqualByComparingTo("0.10");
        assertThat(a.movements().get(1).balance()).isEqualByComparingTo("0.30");
        assertThat(a.debits()).isEqualByComparingTo("0.30");
        assertThat(activity("1000")).isEqualTo(a);
        db.update("UPDATE journal_entries SET entry_date = ? WHERE id = 'foreign-entry'", end);
        assertThat(activity("1000")).isEqualTo(a);
    }

    @Test void emptyPeriodsCarryBalancesAndDateLimitsAndLeapDaysWork() {
        assertThat(accounts.activity("1000", LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)).movements()).isEmpty();
        String old = invoice("0.30", start.minusDays(1), "old");
        ledger.recordPayment(old, new LedgerService.Payment(start.minusDays(1), "0.30"), "old-payment", "test");
        var a = activity("1000");
        assertThat(a.movements()).isEmpty();
        assertThat(a.openingBalance()).isEqualByComparingTo("0.30");
        assertThat(a.closingBalance()).isEqualByComparingTo("0.30");
        LocalDate leap = LocalDate.of(2028, 2, 29);
        invoice("10", leap, "leap");
        assertThat(accounts.activity("4000", leap, leap).credits()).isEqualByComparingTo("10");
        for (String code : java.util.List.of("missing", "1000' OR '1'='1"))
            assertThatThrownBy(() -> activity(code)).isInstanceOf(IllegalArgumentException.class).hasMessage("Account not found.");
        assertThatThrownBy(() -> accounts.activity("1000", null, end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.activity("1000", start, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.activity("1000", LocalDate.of(0, 1, 1), end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.activity("1000", start, LocalDate.of(10000, 1, 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void endpointRequiresLoginAllowsReadingRolesAndRejectsBadRequests() throws Exception {
        mixedBooks();
        String path = "/api/reports/accounts/1000/activity?startsOn=2026-10-01&endsOn=2026-10-31";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : java.util.List.of("OWNER", "BOOKKEEPER", "REVIEWER"))
            http.perform(get(path).with(user("reader").roles(role))).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.account.code").value("1000"))
                    .andExpect(jsonPath("$.openingBalance").value("60.06"))
                    .andExpect(jsonPath("$.closingBalance").value("70.16"))
                    .andExpect(jsonPath("$.movements[0].credit").value("10.10"));
        http.perform(get(path).with(httpBasic("test", "test-only"))).andExpect(status().isOk());
        for (String invalid : java.util.List.of(path.replace("2026-10-01", "2026-11-01"), path.replace("2026-10-01", "bad"),
                path.replace("&endsOn=2026-10-31", ""), path.replace("1000", "missing")))
            http.perform(get(invalid).with(user("reader").roles("REVIEWER"))).andExpect(status().isBadRequest());
    }
}
