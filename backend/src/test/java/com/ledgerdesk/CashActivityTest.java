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
class CashActivityTest {
    @Autowired CashActivityService cash;
    @Autowired ReportService reports;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private final LocalDate start = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 10, 31);
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private CashActivityService.Activity report() { return cash.report(start, end); }
    private void entry(String debit, String credit, String amount, LocalDate date) {
        ledger.journal(ledger.id(), date, "Cash report fixture", debit, credit, new java.math.BigDecimal(amount));
    }
    @Test void emptyPeriodHasZeroCashAndStableCategories() {
        var r = report();
        assertThat(r.openingCash()).isEqualByComparingTo("0");
        assertThat(r.closingCash()).isEqualByComparingTo("0");
        assertThat(r.difference()).isEqualByComparingTo("0");
        assertThat(r.movements()).isEmpty(); assertThat(r.categories()).hasSize(5);
    }
    @Test void openingAndClosingUseInclusivePeriodBoundariesAndRetainNegativeCash() {
        entry("5000", "1000", "20", start.minusDays(1));
        entry("1000", "1100", "10.01", start);
        entry("5000", "1000", "3.02", end);
        entry("1000", "3000", "999", end.plusDays(1));
        var r = report();
        assertThat(r.openingCash()).isEqualByComparingTo("-20");
        assertThat(r.receipts()).isEqualByComparingTo("10.01");
        assertThat(r.payments()).isEqualByComparingTo("3.02");
        assertThat(r.netChange()).isEqualByComparingTo("6.99");
        assertThat(r.closingCash()).isEqualByComparingTo("-13.01");
        assertThat(r.difference()).isEqualByComparingTo("0");
        assertThat(r.movements()).extracting(CashActivityService.Movement::postedOn).containsExactly(start, end);
    }
    @Test void actualPaymentsAreSeparateFromUnpaidAccrualProfit() {
        String vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@example.test"), "vendor", "test");
        String invoice = ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", start, end, "1200"), "invoice", "test");
        ledger.recordPayment(invoice, new LedgerService.Payment(start, "700"), "paid", "test");
        String bill = purchases.postBill(new PurchaseService.Bill(vendor, "B1", "Supplies", start, end, "5000", "600"), "bill", "test");
        purchases.payBill(bill, new LedgerService.Payment(end, "200"), "bill-paid", "test");
        purchases.postExpense(new PurchaseService.Expense(vendor, "Software", end, "5100", "50"), "expense", "test");
        var r = report();
        assertThat(r.closingCash()).isEqualByComparingTo("450");
        assertThat(reports.reports(start, end).profitLoss().netProfit()).isEqualByComparingTo("550");
        assertThat(r.movements()).hasSize(3);
        assertThat(r.categories().get(0).net()).isEqualByComparingTo("700");
        assertThat(r.categories().get(1).net()).isEqualByComparingTo("-200");
        assertThat(r.categories().get(2).net()).isEqualByComparingTo("-50");
    }
    @Test void ownerFundingAndReversalsRetainGrossReceiptsAndPayments() {
        entry("1000", "3000", "100", start);
        entry("3100", "1000", "40", start);
        entry("1000", "3100", "40", end);
        entry("3000", "1000", "100", end);
        var r = report();
        assertThat(r.receipts()).isEqualByComparingTo("140");
        assertThat(r.payments()).isEqualByComparingTo("140");
        assertThat(r.categories().get(3).net()).isEqualByComparingTo("0");
        assertThat(r.movements()).allMatch(m -> m.category().equals("OWNER"));
    }
    @Test void noncashReclassificationDoesNotChangeCashActivityOrItsOriginalCategory() {
        entry("5000", "1000", "100", start);
        var before = report();
        entry("1500", "5000", "100", start);
        entry("5600", "1590", "30", end);
        assertThat(report()).isEqualTo(before);
        assertThat(before.movements().get(0).category()).isEqualTo("DIRECT_PURCHASES");
    }
    @Test void unknownAndMixedCounterpartsRemainVisibleWithoutDuplicatingCash() {
        entry("1000", "2100", "12", start);
        String id = ledger.id();
        db.update("INSERT INTO journal_entries VALUES (?,1,?,?,?)", id, start, "Mixed counterpart", ledger.id());
        db.update("INSERT INTO journal_lines VALUES (?,?, '1000',30,0)", ledger.id(), id);
        db.update("INSERT INTO journal_lines VALUES (?,?, '1100',0,10)", ledger.id(), id);
        db.update("INSERT INTO journal_lines VALUES (?,?, '3000',0,20)", ledger.id(), id);
        var r = report();
        assertThat(r.receipts()).isEqualByComparingTo("42");
        assertThat(r.movements()).hasSize(2).allMatch(m -> m.category().equals("OTHER"));
        assertThat(r.categories().get(4).receipts()).isEqualByComparingTo("42");
        assertThat(r.difference()).isEqualByComparingTo("0");
    }
    @Test void otherBusinessCashIsExcludedAndSourceIdsAreRetained() {
        entry("1000", "3000", "7", start);
        var before = report();
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other business','USD')");
        String id = ledger.id();
        db.update("INSERT INTO journal_entries VALUES (?,2,?,?,?)", id, start, "Other cash", ledger.id());
        db.update("INSERT INTO journal_lines VALUES (?,?, '1000',100,0)", ledger.id(), id);
        db.update("INSERT INTO journal_lines VALUES (?,?, '3000',0,100)", ledger.id(), id);
        assertThat(report()).isEqualTo(before);
        var m = before.movements().get(0);
        assertThat(m.lineId()).isNotBlank(); assertThat(m.entryId()).isNotBlank(); assertThat(m.sourceId()).isNotBlank();
    }
    @Test void invalidPeriodsAreRejectedAndAuthenticatedReadDoesNotWrite() throws Exception {
        assertThatThrownBy(() -> cash.report(null,end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cash.report(start,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cash.report(end,start)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cash.report(LocalDate.of(0,1,1),end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cash.report(start,LocalDate.of(10000,1,1))).isInstanceOf(IllegalArgumentException.class);
        String url = "/api/reports/cash-activity?startsOn=2026-10-01&endsOn=2026-10-31";
        http.perform(get(url)).andExpect(status().isUnauthorized());
        http.perform(get(url).with(httpBasic("test-owner","test-owner-password"))).andExpect(status().isUnauthorized());
        http.perform(get(url).with(user("owner"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/reports/cash-activity?startsOn=2026-11-01&endsOn=2026-10-31").with(user("owner"))).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    }
}
