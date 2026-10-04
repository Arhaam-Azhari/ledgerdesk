# Customer account statements

This API milestone reads a customer's receivable activity for an inclusive date range. It connects an opening amount owed, invoice charges, payments and retained invoice reversals to a closing balance. The statement screen and download are the next step; no customer-facing sending service is included.

## Request and response

Use the workspace login with:

```text
GET /api/reports/customers/demo-customer/statement?startsOn=2026-10-01&endsOn=2026-10-31
```

Owners, bookkeepers and reviewers can read it. Responses use `Cache-Control: no-store`, USD and decimal-string monetary amounts. Dates must be within years 1–9999 with start on or before end. Missing/invalid dates or an unknown customer return HTTP 400; an account outside business 1 gives the same customer-not-found response. This scoped endpoint does not complete multi-business isolation elsewhere in the application.

The response includes the customer's current ID/name/email, selected dates, `openingBalance`, `charges`, `payments`, `reversals`, `closingBalance` and `movements`. Contact details are current information, not historical contact snapshots.

Each movement includes its posted date, kind (`INVOICE`, `PAYMENT` or `INVOICE_REVERSAL`), invoice number/description, charge, reduction and running balance. Entry, line, source, invoice and optional payment IDs trace it back to the books. A payment is linked to its invoice rather than treated as another invoice charge.

Rows sort by date. Within the same date, invoice charges come first, then payments, then reversals; invoice number and ledger IDs make ties stable. This is a presentation order for date-only accounting, not a claim about the time those actions happened.

## Accounting example

Suppose a September invoice of $300.30 received a September payment of $100.10. The October statement starts with $200.20 owed. In October:

- A new invoice adds $200.20.
- A payment against the older invoice reduces the balance by $50.05.
- Another invoice adds $40; its retained reversal reduces the balance by $40.
- A payment against the new invoice reduces the balance by $25.25.

October charges total $240.20, payments total $75.30 and reversals total $40. The closing balance is **$325.10**:

`opening + charges − payments − reversals = closing`

Opening uses attributable receivable journal lines strictly before the start; movements include both endpoints. A payment or reversal after the end cannot change that statement's figures, even though today's invoice paid/status fields have changed. Drafts have no postings and are excluded. An empty period still carries any earlier balance.

## Implementation and verification

The read uses one repeatable-read transaction. Customer, invoice and entry queries restrict their business scope. Journal lines for account 1100 provide the money; invoice/payment links supply the customer and document evidence. Java `BigDecimal` preserves cents and running balances. Reading creates no journal lines, activity entries or request keys.

Six integration tests cover known opening/movement/closing amounts, receipt-payment linkage, inclusive boundaries, agreement with dated receivable aging, unchanged accounting/activity/commands, later settlements and reversals, same-day ordering, exact split payments, stable line IDs, empty/date-limit cases, drafts/future invoices, other customers/businesses, authentication/reading roles, cache headers and invalid parameters. Run `mvn -f backend/pom.xml -Dtest=CustomerStatementTest test` against a disposable test database. Full H2/PostgreSQL and regression results are pending at this source checkpoint.

This covers the supported invoice/payment/reversal workflow. Opening unpaid-document migration, credit notes, refunds, arbitrary receivable journals, foreign currencies, historical contact versions and delivery remain outside the scope. It is an internal accounting statement API; screen captures and exports follow with the UI milestone.
