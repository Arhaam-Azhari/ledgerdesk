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
class YearEndTest {
    @Autowired YearEndService years;
    @Autowired LedgerService ledger;
    @Autowired PurchaseService purchases;
    @Autowired OpeningBankBalance opening;
    @Autowired BankReconciliation bank;
    @Autowired AccountingPeriodService periods;
    @Autowired PrepaidService prepaid;
    @Autowired FixedAssetService assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    final LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 12, 31);
    String vendor;
    @BeforeEach void clean() {
        DatabaseFixture.reset(db);
        vendor = purchases.addVendor(new PurchaseService.Vendor("Harbor", "accounts@harbor.example"), "vendor", "test");
    }
    void invoice(String amount, LocalDate date, String key) {
        ledger.postInvoice(new LedgerService.Invoice("demo-customer", "Design", date, date, amount), key, "test");
    }
    void bill(String amount, LocalDate date, String key) {
        purchases.postBill(new PurchaseService.Bill(vendor, key, "Supplies", date, date, "5000", amount), key, "test");
    }
    YearEndService.ClosingLine line(YearEndService.Preview p, String code) {
        return p.proposedLines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
    }

    @Test void reviewedAccrualProfitProducesBalancedOffsetsWithoutPosting() {
        opening.post(new OpeningBankBalance.Opening(start.minusDays(1), "1000.25", "Cleared opening"), "opening", "test");
        invoice("100.10", start, "sale"); bill("40.04", start, "purchase");
        String bankId = bank.close(new BankReconciliation.Statement(start, end, "1000.25", "1000.25"), "bank", "test");
        String periodId = periods.close(new AccountingPeriodService.Close(end, "Reviewed annual books"), "period", "test");
        var before = ledger.state();
        var audit = db.queryForList("SELECT * FROM audit_events ORDER BY id");
        var commands = db.queryForList("SELECT * FROM commands ORDER BY command_key");
        var p = years.preview(2026);
        assertThat(p.ready()).isTrue(); assertThat(p.blockers()).isEmpty();
        assertThat(p.accountingPeriodId()).isEqualTo(periodId);
        assertThat(p.bankReconciliationId()).isEqualTo(bankId);
        assertThat(p.retainedEarningsChange()).isEqualByComparingTo("60.06");
        assertThat(line(p, "4000").debit()).isEqualByComparingTo("100.10");
        assertThat(line(p, "5000").credit()).isEqualByComparingTo("40.04");
        assertThat(line(p, "3300").credit()).isEqualByComparingTo("60.06");
        assertThat(p.proposedDebits()).isEqualByComparingTo(p.proposedCredits()).isEqualByComparingTo("100.10");
        assertThat(p.proposedLines()).extracting(YearEndService.ClosingLine::code).containsExactly("4000", "5000", "3300");
        assertThat(ledger.state()).isEqualTo(before);
        assertThat(db.queryForList("SELECT * FROM audit_events ORDER BY id")).isEqualTo(audit);
        assertThat(db.queryForList("SELECT * FROM commands ORDER BY command_key")).isEqualTo(commands);
        periods.reopen(periodId, new AccountingPeriodService.Reopen(1, "Recheck annual review"), "reopen", "test");
        assertThat(years.preview(2026).accountingPeriodId()).isNull();
        assertThat(years.preview(2026).ready()).isFalse();
    }

    @Test void lossZeroAndContraBalancesKeepTheirCorrectSides() {
        invoice("50.05", start, "sale"); bill("80.08", start, "purchase");
        var loss = years.preview(2026);
        assertThat(loss.retainedEarningsChange()).isEqualByComparingTo("-30.03");
        assertThat(line(loss, "3300").debit()).isEqualByComparingTo("30.03");
        assertThat(loss.proposedDebits()).isEqualByComparingTo(loss.proposedCredits());
        DatabaseFixture.reset(db);
        assertThat(years.preview(2026).proposedLines()).isEmpty();
        // A debit revenue and credit expense are possible after corrections.
        db.update("INSERT INTO journal_entries VALUES ('contra',1,?,'Correction proof','contra-source')", start);
        db.update("INSERT INTO journal_lines VALUES ('contra-r','contra','4000',10.10,0), ('contra-e','contra','5000',0,10.10)");
        var contra = years.preview(2026);
        assertThat(line(contra, "4000").credit()).isEqualByComparingTo("10.10");
        assertThat(line(contra, "5000").debit()).isEqualByComparingTo("10.10");
        assertThat(contra.proposedLines()).hasSize(2);
        assertThat(contra.retainedEarningsChange()).isZero();
    }

    @Test void earlierIndividualBalancesBlockEvenWhenEarlierProfitIsZero() {
        invoice("25.25", start.minusDays(1), "old-sale"); bill("25.25", start.minusDays(1), "old-bill");
        invoice("10.10", start, "sale");
        var p = years.preview(2026);
        assertThat(p.blockers()).anyMatch(b -> b.contains("before this year"));
        assertThat(line(p, "4000").debit()).isEqualByComparingTo("10.10");
        assertThat(p.temporaryAccounts()).filteredOn(a -> a.code().equals("4000")).singleElement()
                .satisfies(a -> { assertThat(a.openingBalance()).isEqualByComparingTo("-25.25"); assertThat(a.closingBalance()).isEqualByComparingTo("-35.35"); });
    }

    @Test void dueMonthsAndUnbalancedBooksAreExplained() {
        LocalDate december = end.withDayOfMonth(1);
        String expense = purchases.postExpense(new PurchaseService.Expense(vendor, "Software", december, "5100", "60"), "expense", "test");
        prepaid.create(new PrepaidService.Plan(expense, december, 2, "Two months"), "plan", "test");
        String equipment = purchases.postExpense(new PurchaseService.Expense(vendor, "Equipment", december, "5000", "100"), "equipment", "test");
        assets.create(new FixedAssetService.Asset(equipment, december, 2, "Equipment", "0"), "asset", "test");
        var p = years.preview(2026);
        assertThat(p.pendingPrepaidMonths()).isEqualTo(1); assertThat(p.pendingDepreciationMonths()).isEqualTo(1);
        assertThat(p.blockers()).anyMatch(b -> b.contains("prepaid")).anyMatch(b -> b.contains("depreciation"));
        db.update("UPDATE journal_lines SET debit = debit + 1 WHERE account_code = '5000' AND debit > 0");
        assertThat(years.preview(2026).blockers()).anyMatch(b -> b.contains("trial balance"));
    }

    @Test void calendarBoundsFuturePostingsAndForeignBusinessAreHandled() {
        assertThat(years.preview(1).startsOn()).isEqualTo(LocalDate.of(1, 1, 1));
        assertThat(years.preview(9999).endsOn()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThatThrownBy(() -> years.preview(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> years.preview(10000)).isInstanceOf(IllegalArgumentException.class);
        invoice("10.10", start, "sale");
        var before = years.preview(2026);
        invoice("90.90", end.plusDays(1), "future-sale");
        db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other business','USD')");
        db.update("INSERT INTO journal_entries VALUES ('foreign',2,?,'Other books','foreign-source')", start);
        db.update("INSERT INTO journal_lines VALUES ('foreign-r','foreign','4000',0,80), ('foreign-a','foreign','1100',80,0)");
        assertThat(years.preview(2026)).isEqualTo(before);
    }

    @Test void previewRequiresAuthenticationAndIsSharedWithoutCaching() throws Exception {
        invoice("10.10", start, "sale");
        http.perform(get("/api/year-end/preview?year=2026")).andExpect(status().isUnauthorized());
        for (String role : new String[] {"OWNER", "BOOKKEEPER", "REVIEWER"})
            http.perform(get("/api/year-end/preview?year=2026").with(user("reader").roles(role)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.retainedEarningsChange").value("10.10"))
                    .andExpect(jsonPath("$.ready").value(false));
        http.perform(get("/api/year-end/preview?year=0").with(user("owner").roles("OWNER"))).andExpect(status().isBadRequest());
        http.perform(get("/api/year-end/preview?year=abc").with(user("owner").roles("OWNER"))).andExpect(status().isBadRequest());
        http.perform(get("/api/year-end/preview").with(user("owner").roles("OWNER"))).andExpect(status().isBadRequest());
    }
}
