package com.ledgerdesk;

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
class BankImportTest {
    @Autowired BankCsv parser;
    @Autowired BankService bank;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    private static final String HEADER = "transaction_id,date,description,amount\n";
    private static final String CSV = HEADER + "BANK-1,2026-10-02,Customer payment,700.00\nBANK-2,2026-10-03,Vendor payment,-200.00\n";
    private BankService.Import request(String csv) { return new BankService.Import("October statement", csv); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @BeforeEach void clean() { DatabaseFixture.reset(db); }

    @Test void previewShowsSignedAmountsWithoutSavingAnything() {
        var preview = bank.preview(request(CSV));
        assertThat(preview.added()).isEqualTo(2); assertThat(preview.duplicates()).isZero();
        assertThat(preview.rows().get(0).transaction().amount()).isEqualByComparingTo("700");
        assertThat(preview.rows().get(1).transaction().amount()).isEqualByComparingTo("-200");
        assertThat(count("bank_transactions")).isZero(); assertThat(count("audit_events")).isZero();
    }
    @Test void importCreatesStatementRecordsButNoAccountingEntries() {
        bank.importCsv(request(CSV), "import", "test");
        assertThat(count("bank_transactions")).isEqualTo(2); assertThat(count("bank_imports")).isEqualTo(1);
        assertThat(count("journal_entries")).isZero(); assertThat(count("journal_lines")).isZero();
        assertThat(db.queryForObject("SELECT action FROM audit_events", String.class)).isEqualTo("BANK_CSV_IMPORTED");
    }
    @Test void retryReturnsTheOriginalImport() {
        String id = bank.importCsv(request(CSV), "import", "test");
        assertThat(bank.importCsv(request(CSV), "import", "test")).isEqualTo(id);
        assertThat(count("bank_imports")).isEqualTo(1); assertThat(count("bank_transactions")).isEqualTo(2);
        assertThatThrownBy(() -> bank.importCsv(request(CSV.replace("700.00", "701.00")), "import", "test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void overlappingImportsSkipUnchangedRowsAndAddNewOnes() {
        bank.importCsv(request(CSV), "first", "test");
        String overlap = HEADER + "BANK-2,2026-10-03,Vendor payment,-200\nBANK-3,2026-10-04,Software,-50\n";
        var preview = bank.preview(request(overlap));
        assertThat(preview.added()).isEqualTo(1); assertThat(preview.duplicates()).isEqualTo(1);
        String id = bank.importCsv(request(overlap), "second", "test");
        assertThat(count("bank_transactions")).isEqualTo(3);
        assertThat(db.queryForObject("SELECT duplicate_rows FROM bank_imports WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }
    @Test void sameDateDescriptionAndAmountWithDifferentIdsRemainSeparate() {
        bank.importCsv(request(HEADER + "A,2026-10-01,Coffee,-5\nB,2026-10-01,Coffee,-5\n"), "two", "test");
        assertThat(count("bank_transactions")).isEqualTo(2);
    }
    @Test void changedExistingIdRejectsTheWholeBatch() {
        bank.importCsv(request(CSV), "first", "test");
        String csv = HEADER + "NEW,2026-10-01,New transaction,20\nBANK-1,2026-10-02,Customer payment,701\n";
        assertThatThrownBy(() -> bank.importCsv(request(csv), "conflict", "test")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different details");
        assertThat(count("bank_transactions")).isEqualTo(2); assertThat(count("bank_imports")).isEqualTo(1);
    }
    @Test void duplicateOnlyImportRecordsItsOutcomeWithoutDuplicatingTransactions() {
        bank.importCsv(request(CSV), "first", "test");
        String id = bank.importCsv(request(CSV), "again", "test");
        assertThat(count("bank_transactions")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT added_rows FROM bank_imports WHERE id = ?", Integer.class, id)).isZero();
        assertThat(db.queryForObject("SELECT duplicate_rows FROM bank_imports WHERE id = ?", Integer.class, id)).isEqualTo(2);
    }
    @Test void quotedCommasEscapedQuotesBomAndCrLfAreSupported() {
        var rows = parser.parse("\uFEFF" + HEADER.replace("\n", "\r\n") + "A,2026-10-01,\"Design, \"\"launch\"\"\",0.10\r\n");
        assertThat(rows.get(0).description()).isEqualTo("Design, \"launch\"");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("0.10");
    }
    @Test void invalidRowsAndQuotingAreRejectedWithoutPartialImports() {
        for (String row : new String[]{"A,2026-02-30,Work,10", "A,0000-01-01,Work,10", "A,10/01/2026,Work,10", "A,2026-10-01,Work,0", "A,2026-10-01,Work,1.001", "A,2026-10-01,Work,1e3", "A,2026-10-01,Work,-1000000000000", "A,2026-10-01,Work,10,extra", "A,2026-10-01,\"unclosed,10", "A,2026-10-01,\"closed\"oops,10", "A,2026-10-01,\"line\nbreak\",10", "A,2026-10-01,Work,10\n A ,2026-10-02,Work,20"})
            assertThatThrownBy(() -> bank.importCsv(request(HEADER + row), "invalid", "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("bank_imports")).isZero(); assertThat(count("bank_transactions")).isZero();
    }
    @Test void emptyWrongHeaderOversizedAndExcessiveRowsAreRejected() {
        for (String csv : new String[]{"", HEADER, "date,description,amount\n2026-10-01,Work,10", HEADER + "x".repeat(262145), HEADER + "é".repeat(140000)})
            assertThatThrownBy(() -> parser.parse(csv)).isInstanceOf(IllegalArgumentException.class);
        var csv = new StringBuilder(HEADER);
        for (int i = 0; i < 501; i++) csv.append(i).append(",2026-10-01,Work,10\n");
        assertThatThrownBy(() -> parser.parse(csv.toString())).hasMessageContaining("500");
    }
    @Test void lateFailureRollsBackRowsImportAndCommand() {
        assertThatThrownBy(() -> bank.importCsv(request(CSV), "rollback", "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("bank_imports")).isZero(); assertThat(count("bank_transactions")).isZero(); assertThat(count("commands")).isZero();
    }
    @Test void concurrentImportsKeepOneCopyOfEachTransaction() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return bank.importCsv(request(CSV), "a", "test"); });
            var b = pool.submit(() -> { start.await(); return bank.importCsv(request(CSV), "b", "test"); });
            start.countDown(); a.get(); b.get();
            assertThat(count("bank_transactions")).isEqualTo(2); assertThat(count("bank_imports")).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    @Test void apiRequiresAuthenticationAndCsrfAndCanPreviewWithoutSaving() throws Exception {
        String body = "{\"label\":\"October\",\"csv\":\"transaction_id,date,description,amount\\nA,2026-10-01,Work,10\"}";
        http.perform(get("/api/state")).andExpect(status().isUnauthorized());
        http.perform(post("/api/bank/imports").with(httpBasic("test", "test-only"))
                .contentType("application/json").content(body).header("Idempotency-Key", "api")).andExpect(status().isForbidden());
        http.perform(post("/api/bank/imports/preview").with(httpBasic("test", "test-only")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.added").value("1"));
        assertThat(count("bank_imports")).isZero();
        http.perform(post("/api/bank/imports").with(httpBasic("test", "test-only")).with(csrf())
                .contentType("application/json").content(body).header("Idempotency-Key", "api")).andExpect(status().isOk());
        http.perform(get("/api/state").with(httpBasic("test", "test-only")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bankTransactions[0].external_id").value("A"));
    }
}
