package com.ledgerdesk;

import org.springframework.jdbc.core.JdbcTemplate;

final class DatabaseFixture {
    private DatabaseFixture() {}
    static void reset(JdbcTemplate db) {
        for (String table : new String[]{"year_end_closes", "accounting_period_closes", "opening_bank_balances", "account_recoveries", "business_memberships", "app_users", "asset_retirements", "asset_corrections", "asset_periods", "fixed_assets", "prepaid_corrections", "prepaid_cancellations", "prepaid_periods", "prepaid_plans", "accrual_bills", "accrual_reversals", "expense_accruals", "adjustment_reversals", "journal_adjustments", "equity_reversals", "equity_transactions", "bank_reconciliations", "bank_match_events", "bank_matches", "bank_transactions", "bank_imports", "receipts", "bill_payments", "bills", "expenses", "vendors", "invoice_drafts", "invoice_numbers", "payments", "journal_lines", "journal_entries", "invoices", "commands", "audit_events"})
            db.update("DELETE FROM " + table);
        db.update("DELETE FROM customers WHERE id <> 'demo-customer'");
        db.update("DELETE FROM businesses WHERE id <> 1");
        db.update("UPDATE businesses SET next_invoice_number = 1 WHERE id = 1");
    }
}
