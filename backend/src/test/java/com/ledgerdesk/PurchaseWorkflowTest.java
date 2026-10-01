package com.ledgerdesk;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseWorkflowTest {
    @Autowired PurchaseService purchases;
    @Autowired LedgerService ledger;
    @Autowired ReceiptValidator validator;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate date = LocalDate.of(2026, 10, 1);
    private String vendor;

    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor Supply", "accounts@harbor.example"), "vendor", "test");
    }
    private PurchaseService.Bill bill(String reference, String amount) {
        return new PurchaseService.Bill(vendor, reference, "Office supplies", date, date.plusDays(30), "5000", amount);
    }
    private PurchaseService.Expense expense(String amount) {
        return new PurchaseService.Expense(vendor, "Software subscription", date, "5100", amount);
    }
    private BigDecimal balance(String code) {
        return db.queryForObject("SELECT COALESCE(SUM(debit-credit), 0) FROM journal_lines WHERE account_code = ?", BigDecimal.class, code);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void balanced() {
        assertThat(db.queryForObject("SELECT COALESCE(SUM(debit-credit), 0) FROM journal_lines", BigDecimal.class)).isEqualByComparingTo("0");
    }

    @Test void billAndPartialPaymentDoNotRecordExpenseTwice() {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        assertThat(balance("5000")).isEqualByComparingTo("600");
        assertThat(balance("2000")).isEqualByComparingTo("-600");
        assertThat(balance("1000")).isEqualByComparingTo("0");
        purchases.payBill(id, new LedgerService.Payment(date.plusDays(1), "200"), "payment", "test");
        assertThat(balance("5000")).isEqualByComparingTo("600");
        assertThat(balance("2000")).isEqualByComparingTo("-400");
        assertThat(balance("1000")).isEqualByComparingTo("-200");
        balanced();
    }
    @Test void fullAndDecimalPaymentsClearThePayableExactly() {
        String id = purchases.postBill(bill("SMALL", "0.30"), "bill", "test");
        purchases.payBill(id, new LedgerService.Payment(date, "0.10"), "p1", "test");
        purchases.payBill(id, new LedgerService.Payment(date, "0.20"), "p2", "test");
        assertThat(balance("2000")).isEqualByComparingTo("0");
        assertThat(balance("1000")).isEqualByComparingTo("-0.30");
        assertThatThrownBy(() -> purchases.payBill(id, new LedgerService.Payment(date, "0.01"), "extra", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retriesDoNotDuplicateVendorBillPaymentOrExpense() {
        assertThat(purchases.addVendor(new PurchaseService.Vendor("Harbor Supply", "accounts@harbor.example"), "vendor", "test")).isEqualTo(vendor);
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        assertThat(purchases.postBill(bill("SUP-104", "600"), "bill", "test")).isEqualTo(id);
        var payment = new LedgerService.Payment(date, "200");
        String paid = purchases.payBill(id, payment, "p", "test");
        assertThat(purchases.payBill(id, payment, "p", "test")).isEqualTo(paid);
        String expense = purchases.postExpense(expense("50"), "e", "test");
        assertThat(purchases.postExpense(expense("50"), "e", "test")).isEqualTo(expense);
        assertThat(count("vendors")).isEqualTo(1); assertThat(count("bills")).isEqualTo(1);
        assertThat(count("bill_payments")).isEqualTo(1); assertThat(count("expenses")).isEqualTo(1);
        assertThat(count("journal_entries")).isEqualTo(3);
    }
    @Test void sameReferenceIsRejectedForThisVendorEvenWithAnotherKey() {
        purchases.postBill(bill("Sup-104", "600"), "bill", "test");
        assertThatThrownBy(() -> purchases.postBill(bill("  sup-104  ", "600"), "duplicate", "test")).isInstanceOf(IllegalArgumentException.class);
        String other = purchases.addVendor(new PurchaseService.Vendor("Other vendor", "other@example.com"), "other", "test");
        purchases.postBill(new PurchaseService.Bill(other, "SUP-104", "Work", date, date, "5200", "600"), "otherbill", "test");
        assertThat(count("bills")).isEqualTo(2);
    }
    @Test void reusedRequestKeyWithChangedBillOrPaymentIsRejected() {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        assertThatThrownBy(() -> purchases.postBill(bill("SUP-104", "700"), "bill", "test")).isInstanceOf(IllegalArgumentException.class);
        purchases.payBill(id, new LedgerService.Payment(date, "100"), "p", "test");
        assertThatThrownBy(() -> purchases.payBill(id, new LedgerService.Payment(date, "200"), "p", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("bill_payments")).isEqualTo(1);
    }
    @Test void invalidPurchaseDetailsLeaveNoAccountingRecords() {
        for (String amount : new String[]{"0", "-1", "1.001", "1e3", "1000000000000"}) {
            assertThatThrownBy(() -> purchases.postBill(bill("INVALID", amount), "b", "test")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> purchases.postExpense(expense(amount), "e", "test")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> purchases.postBill(new PurchaseService.Bill(vendor, "INVALID", "Work", date, date.minusDays(1), "5000", "20"), "dates", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.postBill(new PurchaseService.Bill("missing", "INVALID", "Work", date, date, "5000", "20"), "vendorbad", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.postExpense(new PurchaseService.Expense(vendor, "Work", date, "4000", "20"), "category", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.postExpense(new PurchaseService.Expense(vendor, "Work", null, "5000", "20"), "date", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("journal_entries")).isZero(); assertThat(count("bills")).isZero(); assertThat(count("expenses")).isZero();
    }
    @Test void overpaymentAndEarlyPaymentDoNotChangeTheBill() {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        assertThatThrownBy(() -> purchases.payBill(id, new LedgerService.Payment(date, "600.01"), "over", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.payBill(id, new LedgerService.Payment(date.minusDays(1), "20"), "early", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("bill_payments")).isZero(); assertThat(count("journal_entries")).isEqualTo(1);
        assertThat(balance("2000")).isEqualByComparingTo("-600");
    }
    @Test void competingPaymentsCannotOverpayTheBill() throws Exception {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> payAfter(start, id, "a"));
            var b = pool.submit(() -> payAfter(start, id, "b")); start.countDown();
            assertThat(a.get() + b.get()).isEqualTo(1);
            assertThat(balance("2000")).isEqualByComparingTo("-200");
        } finally { pool.shutdownNow(); }
    }
    private int payAfter(CountDownLatch start, String id, String key) throws Exception {
        start.await();
        try { purchases.payBill(id, new LedgerService.Payment(date, "400"), key, "test"); return 1; }
        catch (IllegalArgumentException expected) { return 0; }
    }
    @Test void voidingAnUnpaidBillRetainsItsOriginalEntryAndReference() {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        purchases.voidBill(id, date.plusDays(1), "void", "test"); purchases.voidBill(id, date.plusDays(1), "void", "test");
        assertThat(count("journal_entries")).isEqualTo(2); assertThat(count("bills")).isEqualTo(1);
        assertThat(balance("5000")).isEqualByComparingTo("0"); assertThat(balance("2000")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> purchases.payBill(id, new LedgerService.Payment(date, "1"), "p", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.postBill(bill("SUP-104", "600"), "replacement", "test")).isInstanceOf(IllegalArgumentException.class);
        balanced();
    }
    @Test void aPaidBillCannotBeVoided() {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        purchases.payBill(id, new LedgerService.Payment(date, "1"), "p", "test");
        assertThatThrownBy(() -> purchases.voidBill(id, date, "void", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void directExpensePostsBankAndExpenseAndCanBeReversed() {
        String id = purchases.postExpense(expense("50"), "e", "test");
        assertThat(balance("5100")).isEqualByComparingTo("50"); assertThat(balance("1000")).isEqualByComparingTo("-50");
        assertThat(balance("2000")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> purchases.reverseExpense(id, date.minusDays(1), "early", "test")).isInstanceOf(IllegalArgumentException.class);
        purchases.reverseExpense(id, date, "reverse", "test"); purchases.reverseExpense(id, date, "reverse", "test");
        assertThat(balance("5100")).isEqualByComparingTo("0"); assertThat(balance("1000")).isEqualByComparingTo("0");
        assertThat(count("expenses")).isEqualTo(1); assertThat(count("journal_entries")).isEqualTo(2); balanced();
        assertThatThrownBy(() -> purchases.reverseExpense(id, date, "again", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aLateFailureRollsBackBillsExpensesAndPayments() {
        assertThatThrownBy(() -> purchases.postBill(bill("FAIL", "600"), "fail", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> purchases.postExpense(expense("50"), "efail", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("bills")).isZero(); assertThat(count("expenses")).isZero(); assertThat(count("journal_entries")).isZero();
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        assertThatThrownBy(() -> purchases.payBill(id, new LedgerService.Payment(date, "200"), "pfail", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("bill_payments")).isZero(); assertThat(balance("2000")).isEqualByComparingTo("-600");
    }
    @SuppressWarnings("unchecked")
    @Test void vendorBalancesAgreeWithPayablesAndExcludeVoidsAndDirectExpenses() {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        purchases.payBill(id, new LedgerService.Payment(date, "200"), "p", "test");
        String voided = purchases.postBill(bill("VOID", "100"), "v", "test"); purchases.voidBill(voided, date, "void", "test");
        purchases.postExpense(expense("50"), "e", "test");
        var vendors = (List<Map<String, Object>>) ledger.state().get("vendors"); var row = vendors.get(0);
        assertThat((BigDecimal) row.get("billed")).isEqualByComparingTo("600"); assertThat((BigDecimal) row.get("paid")).isEqualByComparingTo("200");
        assertThat((BigDecimal) row.get("outstanding")).isEqualByComparingTo(balance("2000").negate());
    }
    @Test void longBillDescriptionsDoNotOverflowTheJournalMemo() {
        purchases.postBill(new PurchaseService.Bill(vendor, "R".repeat(80), "D".repeat(240), date, date, "5000", "20"), "long", "test");
        assertThat(count("journal_entries")).isEqualTo(1);
    }

    private byte[] image(int width, int height) throws Exception {
        var picture = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(picture, "png", output); return output.toByteArray();
    }
    private byte[] pdf(boolean active) throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            if (active) document.getDocumentCatalog().setOpenAction(new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('test')"));
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    @Test void imageReceiptsAreValidatedStoredDeduplicatedAndDownloaded() throws Exception {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        byte[] raw = image(10, 10); var checked = validator.validate("../../receipt.png", "image/png", raw);
        String receipt = purchases.attachReceipt("bills", id, checked, "r", "test");
        assertThat(purchases.attachReceipt("bills", id, checked, "r", "test")).isEqualTo(receipt);
        assertThat(purchases.attachReceipt("bills", id, checked, "another", "test")).isEqualTo(receipt);
        assertThat(count("receipts")).isEqualTo(1); assertThat(count("journal_entries")).isEqualTo(1);
        assertThat(purchases.receipt(receipt).get("filename")).isEqualTo("receipt.png");
        http.perform(get("/api/receipts/" + receipt)).andExpect(status().isUnauthorized());
        http.perform(get("/api/receipts/" + receipt).with(httpBasic("test", "test-only"))).andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(content().bytes(checked.bytes()));
        assertThat(ledger.state().get("receipts").toString()).doesNotContain("content_sha", "[B@");
    }
    @Test void staticPdfCanAttachToAnExpenseAndInteractivePdfIsRejected() throws Exception {
        String id = purchases.postExpense(expense("50"), "e", "test");
        String receipt = purchases.attachReceipt("expenses", id, validator.validate("receipt.pdf", "application/pdf", pdf(false)), "r", "test");
        assertThat(purchases.receipt(receipt).get("expense_id")).isEqualTo(id);
        assertThatThrownBy(() -> validator.validate("active.pdf", "application/pdf", pdf(true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("receipts")).isEqualTo(1);
    }
    @Test void encryptedAndTooLongPdfReceiptsAreRejected() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int n = 0; n < 21; n++) document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var output = new ByteArrayOutputStream(); document.save(output);
            assertThatThrownBy(() -> validator.validate("long.pdf", "application/pdf", output.toByteArray())).isInstanceOf(IllegalArgumentException.class);
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "secret", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            output.reset(); document.save(output);
            assertThatThrownBy(() -> validator.validate("encrypted.pdf", "application/pdf", output.toByteArray())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void forgedUnsupportedEmptyLargeAndOverdimensionedReceiptsAreRejected() throws Exception {
        for (String name : new String[]{"receipt.svg", "receipt.html", "receipt.exe", "receipt.pdf"})
            assertThatThrownBy(() -> validator.validate(name, "application/pdf", "not a pdf".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("receipt.png", "image/png", pdf(false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("receipt.jpg", "image/jpeg", image(10,10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("receipt.png", "image/png", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("receipt.png", "image/png", new byte[ReceiptValidator.MAX_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("receipt.png", "image/png", image(4000,3000))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void receiptsEnforceDocumentLimitAndRejectMissingRecords() throws Exception {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        for (int n = 1; n <= 5; n++) purchases.attachReceipt("bills", id, validator.validate("receipt.png", "image/png", image(n,1)), "r" + n, "test");
        assertThatThrownBy(() -> purchases.attachReceipt("bills", id, validator.validate("receipt.png", "image/png", image(6,1)), "sixth", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.attachReceipt("bills", "missing", validator.validate("receipt.png", "image/png", image(1,1)), "missing", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.attachReceipt("other", id, validator.validate("receipt.png", "image/png", image(1,1)), "type", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("receipts")).isEqualTo(5);
    }
    @Test void uploadRequiresCsrfAndReceiptCreationRollsBackOnFailure() throws Exception {
        String id = purchases.postBill(bill("SUP-104", "600"), "bill", "test");
        var file = new MockMultipartFile("file", "receipt.png", "image/png", image(10,10));
        http.perform(multipart("/api/bills/" + id + "/receipts").file(file).with(httpBasic("test", "test-only")).header("Idempotency-Key", "r"))
                .andExpect(status().isForbidden());
        http.perform(multipart("/api/bills/" + id + "/receipts").file(file).with(httpBasic("test", "test-only")).with(csrf()).header("Idempotency-Key", "r"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> purchases.attachReceipt("expenses", purchases.postExpense(expense("50"), "e", "test"), validator.validate("receipt.png", "image/png", image(11,10)), "fail", "x".repeat(101)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("receipts")).isEqualTo(1);
    }
    @Test void newPurchaseWritesRequireAuthenticationAndCsrf() throws Exception {
        String body = "{\"vendorId\":\"" + vendor + "\",\"reference\":\"SUP-104\",\"description\":\"Supplies\",\"issuedOn\":\"2026-10-01\",\"dueOn\":\"2026-10-31\",\"accountCode\":\"5000\",\"amount\":\"600\"}";
        http.perform(post("/api/bills").with(csrf()).header("Idempotency-Key", "bill").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        http.perform(post("/api/bills").with(httpBasic("test", "test-only")).header("Idempotency-Key", "bill").contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/bills").with(httpBasic("test", "test-only")).with(csrf()).header("Idempotency-Key", "bill").contentType("application/json").content(body)).andExpect(status().isOk());
    }
}
