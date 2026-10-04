# Dated ledger account activity

Account activity explains one account's closing balance from an opening balance and the journal lines posted during an inclusive period. It works across bank, receivable, payable, revenue, expense and equity accounts rather than relying on a document's current paid/status fields. This milestone provides the accounting API; the account selector, movement screen and CSV export follow separately.

## Request and response

Use the workspace login with:

```text
GET /api/reports/accounts/1000/activity?startsOn=2026-10-01&endsOn=2026-10-31
```

Owners, bookkeepers and reviewers can read it. Anonymous requests return 401. Missing/invalid dates, start after end, dates outside years 1–9999 and unknown account codes return 400. Successful responses use `Cache-Control: no-store`, USD and decimal-string monetary values. Account codes are SQL parameters, not concatenated query text.

The response contains `account` (code, current name and kind), selected dates, `openingBalance`, period `debits` and `credits`, `closingBalance` and `movements`. Each movement carries its date, memo, debit, credit, running balance and journal entry/line/source IDs. Source IDs identify the original posting source; they are not always invoice IDs. Current account names are not historical name snapshots.

## Reading the balances

Balances consistently use **debits minus credits**:

`opening + period debits - period credits = closing`

A positive number is a debit balance, a negative number is a credit balance, and zero has neither side. Thus revenue/payables normally have negative balances, while bank/receivables normally have positive balances. An unusual credit bank balance is retained, not forced positive or hidden. Activity is the ledger's actual debit/credit movement; it is not the same sign convention as the profit report's revenue and expense presentation.

For a worked bank example, an earlier payment leaves an opening $60.06 debit balance. During October, a customer payment adds a $25.25 debit; a $10.10 direct expense and $5.05 bill payment add $15.15 of credits. Bank closes at **$70.16 debit**. The ledger's dated trial-balance row must have that same debit-minus-credit amount. A later $100 expense changes the closing to **$29.84 credit**, represented by `-29.84`.

The related test books have receivables `214.99`, revenue `-300.30`, payables `-34.99` and software expense `10.10`. These are individual account balances, not a claim that the bank example alone comprises the entire trial balance.

## Cutoffs and evidence

Opening includes postings strictly before the start. Activity includes both dates. A later payment/reversal cannot change an earlier period; a new backdated posting can change a new read. Draft documents have no journal lines. An empty period still carries its earlier balance.

Rows sort by posting date, entry ID and line ID. This is a stable display order for date-only accounting, not the time transactions were entered. Each journal line appears once, including multiple lines for the same account in one entry. Running balances follow that display order. Reversal lines remain visible rather than removing the original posting.

Opening and movements share one repeatable-read transaction. Both queries restrict journal entries to business 1, and Java `BigDecimal` preserves exact cents. The chart of accounts is currently shared by the local application. This scoped read does not establish complete multi-business isolation elsewhere. It creates no journal, audit or request-key rows.

## Reproduction

Run `mvn -f backend/pom.xml -Dtest=AccountActivityTest test` against a disposable test database. Six integration tests cover the worked bank amounts and source evidence, dated trial-balance agreement across account kinds, unusual credit balances, exact split lines and stable order, future payments/reversals/invoices and drafts, foreign business entries, carried empty periods, date limits and leap day, authenticated reading roles, decimal-string responses, cache headers and invalid requests.

The existing general ledger remains available in the workspace. This API supplies the accounting calculation for a separate dated account activity screen; browser checks in this milestone are regression evidence for existing screens.


Source `5c8d3a14a59a028fc061352f484439d33776febd` passed 292 integration tests on each of H2 and PostgreSQL 17, with zero failures/errors/skips, plus 14 backup-tool tests in [run 37235848702](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37235848702). The production frontend build, all 25 existing Chromium workflows and native PostgreSQL restoration also passed. Existing browser checks are regression evidence; there is no account activity screen at this checkpoint. The six account activity tests exercise the actual service and HTTP endpoint on both databases.
