package com.ledgerdesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpeningBooksService {
    public record Balance(String code, String debit, String credit) {}
    public record Receivable(String customerId, String reference, String description,
            LocalDate issuedOn, LocalDate dueOn, String amount) {}
    public record Payable(String vendorId, String reference, String description,
            LocalDate issuedOn, LocalDate dueOn, String amount) {}
    public record Request(LocalDate asOf, String reviewNote, List<Balance> balances,
            List<Receivable> receivables, List<Payable> payables) {}
    public record Line(String code, String name, String kind, BigDecimal debit, BigDecimal credit) {}
    public record Document(String partyId, String partyName, String reference, String description,
            LocalDate issuedOn, LocalDate dueOn, BigDecimal amount) {}
    public record Preview(LocalDate asOf, LocalDate operatingStartsOn, String currency, String reviewNote,
            List<Line> lines, List<Document> receivables, List<Document> payables,
            BigDecimal debits, BigDecimal credits, BigDecimal difference, BigDecimal bankBalance,
            BigDecimal receivableBalance, BigDecimal receivableDocuments, BigDecimal receivableDifference,
            BigDecimal payableBalance, BigDecimal payableDocuments, BigDecimal payableDifference,
            List<String> blockers, boolean ready) {}

    // Scheduled assets and accruals need their own carried records before they can be imported.
    private static final Map<String, String> SUPPORTED = Map.of(
            "1000", "ASSET", "1100", "ASSET", "2000", "LIABILITY", "3000", "EQUITY",
            "3100", "EQUITY", "3200", "EQUITY", "3300", "EQUITY");
    private final JdbcTemplate db;
    public OpeningBooksService(JdbcTemplate db) { this.db = db; }
    private static BigDecimal zero() { return new BigDecimal("0.00"); }
    private static BigDecimal amount(String value) {
        if (value == null || !value.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Enter nonnegative debit and credit amounts with at most two decimal places.");
        return new BigDecimal(value).setScale(2);
    }
    private static void documents(List<?> rows) {
        if (rows == null || rows.size() > 100)
            throw new IllegalArgumentException("Provide each document list with at most 100 items; use an empty list when there are none.");
    }
    private Document document(String table, String partyId, String reference, String description,
            LocalDate issued, LocalDate due, String value, LocalDate cutoff, Set<List<String>> seen) {
        String id = LedgerService.text(partyId, 36, "Document party ID");
        String ref = LedgerService.text(reference, 80, "Original document reference");
        String referenceKey = LedgerService.text(ref.toUpperCase(java.util.Locale.ROOT), 80, "Normalized original reference");
        String note = LedgerService.text(description, 240, "Document description");
        if (issued == null || due == null || issued.getYear() < 1 || due.getYear() < 1
                || issued.getYear() > 9999 || due.getYear() > 9999 || issued.isAfter(cutoff) || due.isBefore(issued))
            throw new IllegalArgumentException("Opening documents must be issued on or before cutover, with a supported due date on or after issue.");
        BigDecimal total = LedgerService.money(value);
        // Table names come only from the two internal callers, never from a request.
        var parties = db.queryForList("SELECT name FROM " + table + " WHERE id = ? AND business_id = 1", id);
        if (parties.size() != 1) throw new IllegalArgumentException("Opening document party not found in this business.");
        if (!seen.add(List.of(id, referenceKey))) throw new IllegalArgumentException("Use each original document reference once per party, ignoring case.");
        return new Document(id, parties.get(0).get("name").toString(), ref, note, issued, due, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Request request) {
        if (request == null || request.asOf() == null || request.asOf().getYear() < 1
                || request.asOf().isAfter(LocalDate.of(9999, 12, 30)))
            throw new IllegalArgumentException("Choose a supported cutover date before the final supported day.");
        String note = LedgerService.text(request.reviewNote(), 240, "Opening review note");
        if (request.balances() == null || request.balances().isEmpty() || request.balances().size() > SUPPORTED.size())
            throw new IllegalArgumentException("Provide one to seven unique supported account balances, including the bank.");
        documents(request.receivables()); documents(request.payables());
        var lines = new ArrayList<Line>(); var codes = new HashSet<String>();
        BigDecimal debits = zero(), credits = zero(), bank = zero(), receivable = zero(), payable = zero();
        for (Balance balance : request.balances()) {
            if (balance == null) throw new IllegalArgumentException("Each opening balance is required.");
            String code = LedgerService.text(balance.code(), 4, "Account code");
            if (!SUPPORTED.containsKey(code))
                throw new IllegalArgumentException("Unsupported opening account. Use bank, receivables, payables or owner/opening/retained equity; scheduled balances need a separate import model.");
            if (!codes.add(code)) throw new IllegalArgumentException("Provide each opening account once.");
            var accounts = db.queryForList("SELECT name, kind FROM accounts WHERE code = ?", code);
            if (accounts.size() != 1 || !SUPPORTED.get(code).equals(accounts.get(0).get("kind")))
                throw new IllegalArgumentException("Supported opening account is missing or has a different account kind.");
            BigDecimal debit = amount(balance.debit()), credit = amount(balance.credit());
            if (debit.signum() > 0 && credit.signum() > 0)
                throw new IllegalArgumentException("An opening account has one debit or credit balance, not both.");
            if ((code.equals("1000") || code.equals("1100")) && credit.signum() > 0)
                throw new IllegalArgumentException("Opening bank and receivables must be nonnegative debit balances.");
            if (code.equals("2000") && debit.signum() > 0)
                throw new IllegalArgumentException("Opening payables must be a nonnegative credit balance.");
            lines.add(new Line(code, accounts.get(0).get("name").toString(), SUPPORTED.get(code), debit, credit));
            debits = debits.add(debit); credits = credits.add(credit);
            if (code.equals("1000")) bank = debit;
            if (code.equals("1100")) receivable = debit;
            if (code.equals("2000")) payable = credit;
        }
        if (!codes.contains("1000")) throw new IllegalArgumentException("Include the cleared bank balance, using zero when there is none.");
        lines.sort(java.util.Comparator.comparing(Line::code));
        var receivables = new ArrayList<Document>(); var payables = new ArrayList<Document>();
        var customers = new HashSet<List<String>>(); var vendors = new HashSet<List<String>>();
        BigDecimal customerTotal = zero(), supplierTotal = zero();
        for (Receivable row : request.receivables()) {
            if (row == null) throw new IllegalArgumentException("Each receivable document is required.");
            Document result = document("customers", row.customerId(), row.reference(), row.description(),
                    row.issuedOn(), row.dueOn(), row.amount(), request.asOf(), customers);
            receivables.add(result); customerTotal = customerTotal.add(result.amount());
        }
        for (Payable row : request.payables()) {
            if (row == null) throw new IllegalArgumentException("Each payable document is required.");
            Document result = document("vendors", row.vendorId(), row.reference(), row.description(),
                    row.issuedOn(), row.dueOn(), row.amount(), request.asOf(), vendors);
            payables.add(result); supplierTotal = supplierTotal.add(result.amount());
        }
        BigDecimal difference = debits.subtract(credits), customerDifference = receivable.subtract(customerTotal),
                supplierDifference = payable.subtract(supplierTotal);
        var blockers = new ArrayList<String>();
        if (difference.signum() != 0) blockers.add("Opening debits and credits must balance.");
        if (customerDifference.signum() != 0) blockers.add("Receivable balance must equal the supported unpaid customer documents.");
        if (supplierDifference.signum() != 0) blockers.add("Payable balance must equal the supported unpaid supplier documents.");
        int existing = db.queryForObject("""
            SELECT (SELECT COUNT(*) FROM opening_bank_balances WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM journal_entries WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM bank_imports WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM bank_reconciliations WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM invoices WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM bills WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM expenses WHERE business_id = 1)
                 + (SELECT COUNT(*) FROM invoice_drafts WHERE business_id = 1 AND cancelled = FALSE AND posted_invoice_id IS NULL)
            """, Integer.class);
        if (existing != 0) blockers.add("Opening books require fresh accounting data. Existing bank setup, documents, active drafts, postings or bank reviews must not be imported twice.");
        return new Preview(request.asOf(), request.asOf().plusDays(1), "USD", note, List.copyOf(lines),
                List.copyOf(receivables), List.copyOf(payables), debits, credits, difference, bank,
                receivable, customerTotal, customerDifference, payable, supplierTotal, supplierDifference,
                List.copyOf(blockers), blockers.isEmpty());
    }
}
