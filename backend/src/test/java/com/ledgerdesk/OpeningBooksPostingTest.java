package com.ledgerdesk;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
class OpeningBooksPostingTest {
    @Autowired OpeningBooksService books;
    @Autowired OpeningBankBalance opening;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired ReportService reports;
    @Autowired CashActivityService cash;
    @Autowired CustomerStatementService statements;
    @Autowired BankReconciliation bank;
    @Autowired BankService imports;
    @Autowired AccountActivityService activity;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    final LocalDate cutoff = LocalDate.of(2025, 12, 31), start = cutoff.plusDays(1), end = LocalDate.of(2026, 1, 31);
    String vendor;
    @BeforeEach void clean() { reset(); }
    void reset() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
    }
    OpeningBooksService.Balance line(String code, String debit, String credit) {
        return new OpeningBooksService.Balance(code, debit, credit);
    }
    OpeningBooksService.Receivable invoice(String customer, String ref, String amount) {
        return new OpeningBooksService.Receivable(customer, ref, "Carried invoice", cutoff.minusDays(10), start.plusDays(14), amount);
    }
    OpeningBooksService.Payable bill(String supplier, String ref, String amount) {
        return new OpeningBooksService.Payable(supplier, ref, "Carried bill", cutoff.minusDays(5), start.plusDays(9), amount);
    }
    OpeningBooksService.Request request() {
        return new OpeningBooksService.Request(cutoff, "Reviewed prior books and unpaid originals",
                List.of(line("1000", "1000.25", "0"), line("1100", "100.10", "0"), line("2000", "0", "40.04"),
                        line("3000", "0", "1000.25"), line("3300", "0", "60.06")),
                List.of(invoice("demo-customer", "OLD-SALE", "100.10")), List.of(bill(vendor, "OLD-BILL", "40.04")));
    }
    String invoiceId() { return db.queryForObject("SELECT invoice_id FROM opening_book_invoices", String.class); }
    String billId() { return db.queryForObject("SELECT bill_id FROM opening_book_bills", String.class); }
    void balancedEntries() {
        assertThat(db.queryForList("SELECT entry_id FROM journal_lines GROUP BY entry_id HAVING SUM(debit) <> SUM(credit)")).isEmpty();
    }
    void unchanged(Runnable work) {
        var state = ledger.state(); var history = books.history();
        int keys = db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class);
        work.run();
        assertThat(ledger.state()).isEqualTo(state); assertThat(books.history()).isEqualTo(history);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isEqualTo(keys);
    }

    @Test void postsExactReviewedBalancesWithCutoverSourcesAndRetainedEvidence() throws Exception {
        var preview = books.preview(request());
        String id = books.post(request(), "opening", "owner");
        var record = db.queryForMap("SELECT * FROM opening_book_imports WHERE id = ?", id);
        assertThat(json.readValue(record.get("snapshot").toString(), OpeningBooksService.Preview.class)).isEqualTo(preview);
        assertThat(record.get("created_by")).isEqualTo("owner"); assertThat(record.get("created_at")).isNotNull();
        assertThat(record.get("bank_opening_id")).isEqualTo(id); assertThat(record.get("entry_id")).isNotNull();
        for (var row : preview.lines())
            assertThat(activity.activity(row.code(), start, end).openingBalance()).isEqualByComparingTo(row.debit().subtract(row.credit()));
        assertThat(activity.activity("3200", start, end).openingBalance()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isEqualTo(9);
        assertThat(db.queryForList("SELECT entry_date FROM journal_entries")).allSatisfy(row ->
                assertThat(((java.sql.Date) row.get("entry_date")).toLocalDate()).isEqualTo(cutoff));
        var original = db.queryForMap("SELECT * FROM opening_book_invoices WHERE invoice_id = ?", invoiceId());
        assertThat(original.get("original_reference")).isEqualTo("OLD-SALE"); assertThat(original.get("opening_books_id")).isEqualTo(id);
        assertThat(db.queryForObject("SELECT source_id FROM journal_entries WHERE id = ?", String.class, original.get("entry_id"))).isEqualTo(invoiceId());
        assertThat(db.queryForObject("SELECT next_invoice_number FROM businesses WHERE id = 1", Long.class)).isEqualTo(2);
        var current = reports.reports(start, end);
        assertThat(current.profitLoss().netProfit()).isZero(); assertThat(current.balanceSheet().difference()).isZero();
        assertThat(current.balanceSheet().totalAssets()).isEqualByComparingTo("1100.35");
        assertThat(current.balanceSheet().totalLiabilities()).isEqualByComparingTo("40.04");
        assertThat(current.balanceSheet().totalEquity()).isEqualByComparingTo("1060.31");
        assertThat(current.receivables().total()).isEqualByComparingTo("100.10");
        assertThat(current.payables().total()).isEqualByComparingTo("40.04");
        var earlier = reports.reports(cutoff.minusDays(20), cutoff.minusDays(1));
        assertThat(earlier.balanceSheet().totalAssets()).isZero(); assertThat(earlier.receivables().total()).isZero();
        assertThat(earlier.payables().total()).isZero(); assertThat(earlier.profitLoss().netProfit()).isZero();
        var customer = statements.statement("demo-customer", start, end);
        assertThat(customer.openingBalance()).isEqualByComparingTo("100.10"); assertThat(customer.charges()).isZero();
        assertThat(customer.closingBalance()).isEqualByComparingTo("100.10");
        assertThat(books.preview(request()).ready()).isFalse();
        ledger.postInvoice(new LedgerService.Invoice("demo-customer", "New operating sale", start, start, "20.02"), "new-sale", "owner");
        purchases.postBill(new PurchaseService.Bill(vendor, "NEW-BILL", "New operating supplies", start, start, "5000", "10.01"), "new-bill", "owner");
        var operating = reports.reports(start, end);
        assertThat(operating.profitLoss().netProfit()).isEqualByComparingTo("10.01");
        assertThat(operating.receivables().total()).isEqualByComparingTo("120.12");
        assertThat(operating.payables().total()).isEqualByComparingTo("50.05");
        assertThat(operating.balanceSheet().difference()).isZero(); balancedEntries();
    }

    @Test void importedDocumentsSettlePartiallyAndFullyWithoutDuplicatingOperatingProfit() {
        String id = books.post(request(), "opening", "owner");
        String snapshot = db.queryForObject("SELECT snapshot FROM opening_book_imports WHERE id = ?", String.class, id);
        ledger.recordPayment(invoiceId(), new LedgerService.Payment(start, "40.04"), "customer-first", "bookkeeper");
        purchases.payBill(billId(), new LedgerService.Payment(start, "10.01"), "supplier-first", "bookkeeper");
        var partial = reports.reports(start, end);
        assertThat(partial.receivables().total()).isEqualByComparingTo("60.06");
        assertThat(partial.payables().total()).isEqualByComparingTo("30.03"); assertThat(partial.profitLoss().netProfit()).isZero();
        ledger.recordPayment(invoiceId(), new LedgerService.Payment(start.plusDays(1), "60.06"), "customer-final", "bookkeeper");
        purchases.payBill(billId(), new LedgerService.Payment(start.plusDays(1), "30.03"), "supplier-final", "bookkeeper");
        var settled = reports.reports(start, end);
        assertThat(settled.profitLoss().netProfit()).isZero(); assertThat(settled.receivables().total()).isZero(); assertThat(settled.payables().total()).isZero();
        assertThat(settled.balanceSheet().difference()).isZero(); assertThat(settled.balanceSheet().totalAssets()).isEqualByComparingTo("1060.31");
        assertThat(settled.balanceSheet().totalEquity()).isEqualByComparingTo("1060.31");
        var statement = statements.statement("demo-customer", start, end);
        assertThat(statement.openingBalance()).isEqualByComparingTo("100.10"); assertThat(statement.charges()).isZero();
        assertThat(statement.payments()).isEqualByComparingTo("100.10"); assertThat(statement.closingBalance()).isZero();
        var movement = cash.report(start, end);
        assertThat(movement.openingCash()).isEqualByComparingTo("1000.25");
        assertThat(movement.receipts()).isEqualByComparingTo("100.10"); assertThat(movement.payments()).isEqualByComparingTo("40.04");
        assertThat(movement.closingCash()).isEqualByComparingTo("1060.31"); assertThat(movement.difference()).isZero();
        assertThat(db.queryForObject("SELECT snapshot FROM opening_book_imports WHERE id = ?", String.class, id)).isEqualTo(snapshot);
        balancedEntries();
    }

    @Test void firstBankReviewUsesClearedOpeningRatherThanTreatingItAsAnOutstandingDeposit() {
        books.post(request(), "opening", "owner");
        var statement = new BankReconciliation.Statement(start, end, "1000.25", "1000.25");
        var preview = bank.preview(statement);
        assertThat(preview.outstandingEntries()).isEmpty(); assertThat(preview.bookDifference()).isZero();
        assertThat(preview.bookBalance()).isEqualByComparingTo("1000.25");
        // Sharing an imported document's source ID must not exempt an unrelated earlier journal.
        db.update("INSERT INTO journal_entries VALUES ('unrelated', 1, ?, 'Unrelated earlier entry', ?)", cutoff, invoiceId());
        db.update("INSERT INTO journal_lines VALUES ('unrelated-debit', 'unrelated', '3200', 1, 0)");
        db.update("INSERT INTO journal_lines VALUES ('unrelated-credit', 'unrelated', '1100', 0, 1)");
        assertThatThrownBy(() -> bank.close(statement, "unrelated-bank", "owner")).hasMessageContaining("beginning of the recorded books");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_reconciliations", Integer.class)).isZero();
        db.update("DELETE FROM journal_lines WHERE entry_id = 'unrelated'");
        db.update("DELETE FROM journal_entries WHERE id = 'unrelated'");
        bank.close(statement, "bank", "owner");
        assertThat(cash.report(start, end).receipts()).isZero();
        assertThat(cash.report(start, end).payments()).isZero();
        assertThatThrownBy(() -> cash.report(cutoff, end)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cutoverPaymentsImportsAndOrdinaryVoidsAreRejectedWithoutChangingBooks() {
        books.post(request(), "opening", "owner");
        unchanged(() -> {
            assertThatThrownBy(() -> ledger.recordPayment(invoiceId(), new LedgerService.Payment(cutoff, "1"), "old-customer", "test"))
                    .hasMessageContaining("after the opening");
            assertThatThrownBy(() -> purchases.payBill(billId(), new LedgerService.Payment(cutoff, "1"), "old-supplier", "test"))
                    .hasMessageContaining("after the opening");
            assertThatThrownBy(() -> imports.importCsv(new BankService.Import("Old bank", "transaction_id,date,description,amount\nOLD,2025-12-31,Deposit,1"), "old-bank", "test"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ledger.voidInvoice(invoiceId(), start, "void-invoice", "test")).hasMessageContaining("cannot be voided");
            assertThatThrownBy(() -> purchases.voidBill(billId(), start, "void-bill", "test")).hasMessageContaining("cannot be voided");
            assertThatThrownBy(() -> ledger.recordPayment(invoiceId(), new LedgerService.Payment(start, "100.11"), "over-customer", "test"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> purchases.payBill(billId(), new LedgerService.Payment(start, "40.05"), "over-supplier", "test"))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test void exactImportRetrySurvivesSettlementAndDuplicateOrChangedSetupDoesNotPost() {
        String id = books.post(request(), "opening", "owner");
        ledger.recordPayment(invoiceId(), new LedgerService.Payment(start, "100.10"), "payment", "test");
        unchanged(() -> {
            assertThat(books.post(request(), "opening", "owner")).isEqualTo(id);
            assertThatThrownBy(() -> books.post(request(), "duplicate", "owner")).hasMessageContaining("blockers");
            var changed = new OpeningBooksService.Request(cutoff, "Changed note", request().balances(), request().receivables(), request().payables());
            assertThatThrownBy(() -> books.post(changed, "opening", "owner")).hasMessageContaining("different details");
            assertThatThrownBy(() -> opening.post(new OpeningBankBalance.Opening(cutoff, "1000.25", "Second setup"), "bank-duplicate", "owner"))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test void failedWritesAndInvalidPreviewsRollBackDocumentsNumbersJournalAndRequestKeys() {
        unchanged(() -> {
            assertThatThrownBy(() -> books.post(request(), "failed", "x".repeat(101))).isInstanceOf(RuntimeException.class);
            var wrong = new OpeningBooksService.Request(cutoff, "Wrong trial", List.of(line("1000", "1", "0")), List.of(), List.of());
            assertThatThrownBy(() -> books.post(wrong, "wrong", "owner")).hasMessageContaining("blockers");
            assertThatThrownBy(() -> books.post(null, "missing", "owner")).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(db.queryForObject("SELECT next_invoice_number FROM businesses WHERE id = 1", Long.class)).isEqualTo(1);
        for (String table : List.of("opening_book_imports", "opening_book_invoices", "opening_book_bills", "opening_bank_balances", "journal_entries", "journal_lines", "invoices", "invoice_numbers", "bills"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        books.post(request(), "failed", "owner"); balancedEntries();
    }

    @Test void zeroLossAndLargeEquityBridgesPreserveAmountsWithoutZeroOrOverflowLines() {
        var empty = new OpeningBooksService.Request(cutoff, "Zero carried books", List.of(line("1000", "0", "0")), List.of(), List.of());
        String id = books.post(empty, "empty", "owner");
        assertThat(db.queryForObject("SELECT entry_id FROM opening_book_imports WHERE id = ?", String.class, id)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_lines", Integer.class)).isZero();
        reset();
        books.post(new OpeningBooksService.Request(cutoff, "Carried loss", List.of(line("1000", "70", "0"),
                line("3300", "30", "0"), line("3000", "0", "100")), List.of(), List.of()), "loss", "owner");
        assertThat(reports.reports(start, end).profitLoss().netProfit()).isZero();
        assertThat(reports.reports(start, end).balanceSheet().totalEquity()).isEqualByComparingTo("70");
        reset();
        String maximum = "999999999999.99";
        books.post(new OpeningBooksService.Request(cutoff, "Large supported balances", List.of(line("1000", "0", "0"),
                line("1100", maximum, "0"), line("3200", maximum, "0"), line("3000", "0", maximum), line("3300", "0", maximum)),
                List.of(invoice("demo-customer", "MAX", maximum)), List.of()), "large", "owner");
        assertThat(activity.activity("3200", start, end).openingBalance()).isEqualByComparingTo(maximum);
        assertThat(reports.reports(start, end).balanceSheet().difference()).isZero(); balancedEntries();
    }

    @Test void concurrentImportsRetainOneSetupAndMultiplePartiesKeepSeparateDocumentSources() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.<java.util.concurrent.Callable<Boolean>>of(
                    () -> { try { books.post(request(), "one", "owner"); return true; } catch (IllegalArgumentException error) { return false; } },
                    () -> { try { books.post(request(), "two", "owner"); return true; } catch (IllegalArgumentException error) { return false; } });
            assertThat(executor.invokeAll(tasks).stream().map(f -> { try { return f.get(); } catch (Exception error) { throw new RuntimeException(error); } }))
                    .containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM opening_book_imports", Integer.class)).isEqualTo(1);
        reset();
        String customer = ledger.addCustomer(new LedgerService.Customer("Second", "second@example.test"), "customer", "test");
        String supplier = purchases.addVendor(new PurchaseService.Vendor("Second", "second@example.test"), "supplier", "test");
        books.post(new OpeningBooksService.Request(cutoff, "Separate source documents", List.of(line("1000", "1000.25", "0"),
                line("1100", "150.15", "0"), line("2000", "0", "60.06"), line("3200", "0", "1090.34")),
                List.of(invoice("demo-customer", "SAME", "100.10"), invoice(customer, "SAME", "50.05")),
                List.of(bill(vendor, "SAME", "40.04"), bill(supplier, "SAME", "20.02"))), "multiple", "owner");
        assertThat(statements.statement("demo-customer", start, end).openingBalance()).isEqualByComparingTo("100.10");
        assertThat(statements.statement(customer, start, end).openingBalance()).isEqualByComparingTo("50.05");
        assertThat(reports.reports(start, end).receivables().total()).isEqualByComparingTo("150.15");
        assertThat(reports.reports(start, end).payables().total()).isEqualByComparingTo("60.06");
        balancedEntries();
    }

    @Test void ownerImportRequiresCsrfAndKeyWhilePopulatedHistoryIsReadableByAllRoles() throws Exception {
        String body = json.writeValueAsString(request());
        for (String role : List.of("REVIEWER", "BOOKKEEPER"))
            http.perform(post("/api/opening-books").with(user("reader").roles(role)).with(csrf()).header("Idempotency-Key", role)
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/opening-books").with(user("owner").roles("OWNER")).header("Idempotency-Key", "owner")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/opening-books").with(user("owner").roles("OWNER")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(post("/api/opening-books").with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key", "owner")
                .contentType("application/json").content(body)).andExpect(status().isOk());
        var before = ledger.state(); var history = books.history();
        for (String role : List.of("OWNER", "REVIEWER", "BOOKKEEPER"))
            http.perform(get("/api/opening-books").with(user("reader").roles(role))).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.openingBooks.length()").value(1))
                    .andExpect(jsonPath("$.receivables[0].original_reference").value("OLD-SALE"))
                    .andExpect(jsonPath("$.payables[0].reference").value("OLD-BILL"));
        assertThat(ledger.state()).isEqualTo(before); assertThat(books.history()).isEqualTo(history);
    }
}
