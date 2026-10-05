package com.ledgerdesk;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
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
class OpeningBooksTest {
    @Autowired OpeningBooksService books;
    @Autowired OpeningBankBalance opening;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired BankService bank;
    @Autowired BankReconciliation reconciliation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    final LocalDate cutoff = LocalDate.of(2025, 12, 31);
    String vendor;
    @BeforeEach void clean() { reset(); }
    void reset() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
    }
    OpeningBooksService.Balance line(String code, String debit, String credit) {
        return new OpeningBooksService.Balance(code, debit, credit);
    }
    OpeningBooksService.Receivable sale(String id, String ref, String amount) {
        return new OpeningBooksService.Receivable(id, ref, "Unpaid design invoice", cutoff.minusDays(10), cutoff.plusDays(15), amount);
    }
    OpeningBooksService.Payable bill(String id, String ref, String amount) {
        return new OpeningBooksService.Payable(id, ref, "Unpaid supplies bill", cutoff.minusDays(5), cutoff.plusDays(10), amount);
    }
    OpeningBooksService.Request request(List<OpeningBooksService.Balance> balances,
            List<OpeningBooksService.Receivable> receivables, List<OpeningBooksService.Payable> payables) {
        return new OpeningBooksService.Request(cutoff, " Reviewed prior books ", balances, receivables, payables);
    }
    OpeningBooksService.Request valid() {
        return request(List.of(line("3300", "0", "60.06"), line("1000", "1000.25", "0"),
                line("1100", "100.10", "0"), line("2000", "0", "40.04"), line("3000", "0", "1000.25")),
                List.of(sale("demo-customer", " OLD-SALE ", "100.10")), List.of(bill(vendor, "OLD-BILL", "40.04")));
    }
    void unchanged(Runnable action) {
        var before = ledger.state();
        int keys = db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class);
        action.run();
        assertThat(ledger.state()).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isEqualTo(keys);
    }

    @Test void balancedBooksMatchDocumentsWithoutCreatingAnyAccountingRecords() {
        unchanged(() -> {
            var result = books.preview(valid());
            assertThat(result.ready()).isTrue(); assertThat(result.blockers()).isEmpty();
            assertThat(result.operatingStartsOn()).isEqualTo(cutoff.plusDays(1));
            assertThat(result.currency()).isEqualTo("USD"); assertThat(result.reviewNote()).isEqualTo("Reviewed prior books");
            assertThat(result.debits()).isEqualByComparingTo("1100.35");
            assertThat(result.credits()).isEqualByComparingTo("1100.35"); assertThat(result.difference()).isZero();
            assertThat(result.bankBalance()).isEqualByComparingTo("1000.25");
            assertThat(result.receivableBalance()).isEqualByComparingTo("100.10");
            assertThat(result.receivableDocuments()).isEqualByComparingTo("100.10");
            assertThat(result.payableBalance()).isEqualByComparingTo("40.04");
            assertThat(result.payableDocuments()).isEqualByComparingTo("40.04");
            assertThat(result.receivableDifference()).isZero(); assertThat(result.payableDifference()).isZero();
            assertThat(result.lines()).extracting(OpeningBooksService.Line::code).containsExactly("1000", "1100", "2000", "3000", "3300");
            assertThat(result.receivables().get(0).reference()).isEqualTo("OLD-SALE");
            assertThat(result.receivables().get(0).partyName()).isEqualTo("Maple Coffee Co.");
            assertThat(result.payables().get(0).partyName()).isEqualTo("Harbor");
        });
        for (String table : List.of("opening_bank_balances", "journal_entries", "journal_lines", "invoices", "bills"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
    }

    @Test void zeroBooksAndCarriedLossDoNotCreateArtificialLines() {
        unchanged(() -> {
            var zero = books.preview(request(List.of(line("1000", "0", "0")), List.of(), List.of()));
            assertThat(zero.ready()).isTrue(); assertThat(zero.debits()).isZero(); assertThat(zero.credits()).isZero();
            var loss = books.preview(request(List.of(line("1000", "70", "0"), line("3300", "30", "0"),
                    line("3000", "0", "100")), List.of(), List.of()));
            assertThat(loss.ready()).isTrue(); assertThat(loss.debits()).isEqualByComparingTo("100");
            assertThat(loss.receivableDocuments()).isZero(); assertThat(loss.payableDocuments()).isZero();
        });
    }

    @Test void trialAndDocumentDifferencesRemainVisibleInsteadOfBeingAutoBalanced() {
        unchanged(() -> {
            var result = books.preview(request(List.of(line("1000", "1000.25", "0"), line("1100", "100.10", "0"),
                    line("2000", "0", "40.04"), line("3300", "0", "50.05"), line("3000", "0", "1000.25")),
                    List.of(sale("demo-customer", "SALE", "90.00")), List.of(bill(vendor, "BILL", "30.00"))));
            assertThat(result.ready()).isFalse(); assertThat(result.blockers()).hasSize(3);
            assertThat(result.difference()).isEqualByComparingTo("10.01");
            assertThat(result.receivableDifference()).isEqualByComparingTo("10.10");
            assertThat(result.payableDifference()).isEqualByComparingTo("10.04");
            var noControl = books.preview(request(List.of(line("1000", "0", "0")),
                    List.of(sale("demo-customer", "SALE", "1")), List.of()));
            assertThat(noControl.receivableDifference()).isEqualByComparingTo("-1"); assertThat(noControl.ready()).isFalse();
        });
    }

    @Test void existingSetupPostingsImportsReviewsAndActiveDraftsBlockAnotherOpening() {
        List<Runnable> existing = List.of(
                () -> opening.post(new OpeningBankBalance.Opening(cutoff, "0", "Existing opening"), "opening", "test"),
                () -> ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Sale", cutoff, cutoff, "10"), "sale", "test"),
                () -> bank.importCsv(new BankService.Import("Statement", "transaction_id,date,description,amount\nOLD,2025-12-31,Deposit,10"), "import", "test"),
                () -> reconciliation.close(new BankReconciliation.Statement(cutoff.withDayOfMonth(1), cutoff, "0", "0"), "bank", "test"),
                () -> ledger.createDraft(new LedgerService.Invoice("demo-customer", "Draft", cutoff, cutoff, "10"), "draft", "test"));
        for (Runnable setup : existing) {
            reset(); setup.run();
            unchanged(() -> {
                var result = books.preview(valid());
                assertThat(result.ready()).isFalse();
                assertThat(result.blockers()).anyMatch(message -> message.contains("fresh accounting data"));
            });
        }
        db.update("UPDATE invoice_drafts SET cancelled = TRUE");
        assertThat(books.preview(valid()).ready()).isTrue();
    }

    @Test void unsupportedDuplicateMalformedAndWrongSideBalancesAreRejected() {
        unchanged(() -> {
            for (String code : List.of("4000", "5000", "1300", "1500", "1590", "2100", "9999"))
                assertThatThrownBy(() -> books.preview(request(List.of(line("1000", "0", "0"), line(code, "1", "0")), List.of(), List.of())))
                        .isInstanceOf(IllegalArgumentException.class);
            for (String value : List.of("-1", "1.001", "1e3", "1000000000000", "NaN", ""))
                assertThatThrownBy(() -> books.preview(request(List.of(line("1000", value, "0")), List.of(), List.of())))
                        .isInstanceOf(IllegalArgumentException.class);
            for (var lines : List.of(List.of(line("1000", "1", "1")), List.of(line("1000", "0", "1")),
                    List.of(line("1000", "0", "0"), line("1100", "0", "1")),
                    List.of(line("1000", "0", "0"), line("2000", "1", "0")),
                    List.of(line("1000", "0", "0"), line("1000", "0", "0")), List.of(line("3000", "0", "0"))))
                assertThatThrownBy(() -> books.preview(request(lines, List.of(), List.of()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(request(Arrays.asList((OpeningBooksService.Balance) null), List.of(), List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test void sourceDocumentsRequireBusinessPartiesPositiveAmountsAndUniqueReferences() {
        db.update("INSERT INTO businesses (id, name, currency) VALUES (2, 'Other business', 'USD')");
        db.update("INSERT INTO customers VALUES ('foreign-customer', 2, 'Foreign', 'foreign@example.test')");
        db.update("INSERT INTO vendors VALUES ('foreign-vendor', 2, 'Foreign', 'foreign@example.test')");
        unchanged(() -> {
            for (String id : List.of("missing", "foreign-customer"))
                assertThatThrownBy(() -> books.preview(request(valid().balances(), List.of(sale(id, "REF", "100.10")), valid().payables())))
                        .isInstanceOf(IllegalArgumentException.class);
            for (String id : List.of("missing", "foreign-vendor"))
                assertThatThrownBy(() -> books.preview(request(valid().balances(), valid().receivables(), List.of(bill(id, "REF", "40.04")))))
                        .isInstanceOf(IllegalArgumentException.class);
            for (String value : List.of("0", "-1", "1.001", "1e3"))
                assertThatThrownBy(() -> books.preview(request(valid().balances(), List.of(sale("demo-customer", "REF", value)), List.of())))
                        .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(request(valid().balances(),
                    List.of(sale("demo-customer", "ref", "50"), sale("demo-customer", " REF ", "50.10")), valid().payables())))
                    .hasMessageContaining("once per party");
            assertThatThrownBy(() -> books.preview(request(valid().balances(), valid().receivables(),
                    List.of(bill(vendor, "bill", "20"), bill(vendor, " BILL ", "20.04"))))).hasMessageContaining("once per party");
            assertThatThrownBy(() -> books.preview(request(valid().balances(), Arrays.asList((OpeningBooksService.Receivable) null), List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(request(valid().balances(), List.of(), Arrays.asList((OpeningBooksService.Payable) null))))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test void cutoverDatesNotesDocumentDatesAndListLimitsAreValidated() {
        unchanged(() -> {
            for (LocalDate date : Arrays.asList(null, LocalDate.of(0, 12, 31), LocalDate.of(9999, 12, 31)))
                assertThatThrownBy(() -> books.preview(new OpeningBooksService.Request(date, "Note", valid().balances(), List.of(), List.of())))
                        .isInstanceOf(IllegalArgumentException.class);
            for (String note : Arrays.asList(null, " ", "x".repeat(241)))
                assertThatThrownBy(() -> books.preview(new OpeningBooksService.Request(cutoff, note, valid().balances(), List.of(), List.of())))
                        .isInstanceOf(IllegalArgumentException.class);
            for (List<OpeningBooksService.Balance> lines : Arrays.<List<OpeningBooksService.Balance>>asList(null, List.of(), java.util.Collections.nCopies(8, line("1000", "0", "0"))))
                assertThatThrownBy(() -> books.preview(request(lines, List.of(), List.of()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(request(valid().balances(), null, List.of()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(request(valid().balances(), List.of(), null))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> books.preview(request(valid().balances(), java.util.Collections.nCopies(101, sale("demo-customer", "REF", "1")), List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            for (var row : List.of(
                    new OpeningBooksService.Receivable("demo-customer", "REF", "Description", cutoff.plusDays(1), cutoff.plusDays(2), "100.10"),
                    new OpeningBooksService.Receivable("demo-customer", "REF", "Description", cutoff, cutoff.minusDays(1), "100.10"),
                    new OpeningBooksService.Receivable("demo-customer", "REF", "Description", null, cutoff, "100.10"),
                    new OpeningBooksService.Receivable("demo-customer", " ", "Description", cutoff, cutoff, "100.10"),
                    new OpeningBooksService.Receivable("demo-customer", "REF", " ", cutoff, cutoff, "100.10"),
                    new OpeningBooksService.Receivable("demo-customer", "x".repeat(81), "Description", cutoff, cutoff, "100.10")))
                assertThatThrownBy(() -> books.preview(request(valid().balances(), List.of(row), valid().payables())))
                        .isInstanceOf(IllegalArgumentException.class);
            var maximum = books.preview(new OpeningBooksService.Request(LocalDate.of(9999, 12, 30), "Note",
                    List.of(line("1000", "999999999999.99", "0"), line("3200", "0", "999999999999.99")), List.of(), List.of()));
            assertThat(maximum.ready()).isTrue(); assertThat(maximum.operatingStartsOn()).isEqualTo(LocalDate.of(9999, 12, 31));
        });
    }

    @Test void otherBusinessActivityDoesNotBlockThisBusinessPreview() {
        db.update("INSERT INTO businesses (id, name, currency) VALUES (2, 'Other business', 'USD')");
        db.update("INSERT INTO journal_entries VALUES ('foreign-entry', 2, ?, 'Other books', 'foreign-source')", cutoff);
        db.update("INSERT INTO journal_lines VALUES ('foreign-debit', 'foreign-entry', '1000', 10, 0)");
        db.update("INSERT INTO journal_lines VALUES ('foreign-credit', 'foreign-entry', '3200', 0, 10)");
        unchanged(() -> assertThat(books.preview(valid()).ready()).isTrue());
    }

    @Test void previewRequiresOwnerAndCsrfAndReturnsPrivateReadOnlyEvidence() throws Exception {
        String path = "/api/opening-books/preview", body = json.writeValueAsString(valid());
        var before = ledger.state();
        for (String role : List.of("REVIEWER", "BOOKKEEPER"))
            http.perform(post(path).with(user("reader").roles(role)).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
        http.perform(post(path).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        http.perform(post(path).with(user("owner").roles("OWNER")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        http.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.difference").value("0.00"))
                .andExpect(jsonPath("$.operatingStartsOn").value("2026-01-01"));
        http.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).contentType("application/json").content("null"))
                .andExpect(status().isBadRequest());
        http.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).contentType("application/json")
                .content(body.substring(0, body.length() - 1) + ",\"paid\":\"10\"}"))
                .andExpect(status().isBadRequest());
        assertThat(ledger.state()).isEqualTo(before);
    }
}
