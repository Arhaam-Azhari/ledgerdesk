# Opening-books preview

The existing [opening bank setup](opening-bank-balance.md) carries one cleared bank amount. A business with unpaid invoices or bills needs more: its carried receivable/payable balances must agree with documents that can later be collected or paid. This preview checks those relationships and the trial balance before importing anything.

This milestone implements an API preview only. It does not post opening books, create documents or add a browser form. `ready: true` means the supported draft passes the current preview checks; it is not evidence that an import has occurred. Posting will recheck the draft while holding the business lock in a later milestone.

## Supported draft

Provide a cutover date, review note, explicit debit/credit balances and lists of fully unpaid customer/supplier documents. Operating activity starts the day after cutover. Dates support years 1–9999; cutover must be before December 31, 9999.

| Code | Supported opening balance |
| --- | --- |
| 1000 | Cleared bank, nonnegative debit; include zero if there is no balance |
| 1100 | Receivables, nonnegative debit, reconciled with unpaid customer documents |
| 2000 | Payables, nonnegative credit, reconciled with unpaid supplier documents |
| 3000 | Owner contributions equity |
| 3100 | Owner drawings equity |
| 3200 | Opening balance equity |
| 3300 | Retained earnings; a carried loss can have a debit balance |

Each account appears once, with at most seven account rows. Supply both `debit` and `credit` as nonnegative amount strings with at most two decimal places and twelve whole digits. A row cannot have positive amounts on both sides. Equity rows allow either side; the preview does not infer their provenance or automatically insert a balancing equity amount. Zero balances are allowed without implying future zero journal lines.

Each document supplies a party ID belonging to this business, original reference, description, issue/due dates and positive unpaid amount. Issue must be on or before cutover; due must be on or after issue. Future due dates are valid. References are trimmed, limited to 80 characters, and checked per party ignoring case; descriptions are limited to 240 characters. The normalized reference must also fit 80 characters. Each document list is required and limited to 100 items; supply `[]` when there are none.

The document model is for fully unpaid invoices and bills. It does not import historical partial payments, credit balances, tax breakdowns or payment histories. Do not label the remaining portion of a partly paid invoice as a fully unpaid original document. Those cases need a separate supported model. Revenue/expense balances, prepaid schedules, equipment/depreciation histories, accrued expenses and bank overdrafts are rejected by this first preview model.

Customer and vendor setup can happen before preview. Existing opening-bank setup, journals, invoices, bills, direct expenses, bank imports/reviews or active invoice drafts block fresh opening setup. A discarded draft alone does not block it. This prevents mixing a carried bank balance with a second full import or treating already posted work as opening records.

## Worked draft

At fictional cutover December 31, 2025:

- Debit bank $1,000.25 and receivables $100.10: total debits $1,100.35.
- Credit payables $40.04, owner contributions $1,000.25 and retained earnings $60.06: total credits $1,100.35.
- One fully unpaid customer document supports $100.10 receivables; one supplier document supports $40.04 payables.

Trial balance difference and both document differences are zero. The preview returns the normalized account names and party names, original document details, control/document totals, blockers and `ready`. It creates no opening setup, journals, invoices, bills, audit entries or request keys.

Changing retained earnings to $50.05 leaves a $10.01 trial difference. Changing customer documents to $90.00 leaves a $10.10 receivable difference. Changing supplier documents to $30.00 leaves a $10.04 payable difference. All three remain visible; the preview does not silently fill the gaps. A balanced draft with missing control-account support is also blocked.

## API instructions

Use a fresh local demo installation with the [normal backend instructions](../README.md#run-locally). Prepare the customer/vendor records first. The seeded `demo-customer` is available in the demo; obtain the supplier ID from a created vendor or authenticated `GET /api/state`.

Copy [the example request](examples/opening-books-preview.json) to `opening-preview.json` outside the repository and replace `REPLACE_WITH_VENDOR_ID` with that supplier's ID. Preserve the arrays and explicit zero sides. The example dates are fictional accounting dates, not the time of the API call.

This endpoint accepts `POST /api/opening-books/preview`. It is owner-only and CSRF-protected, although the service is read-only. No idempotency header is required because no command is recorded. Fetch `/api/csrf` with a cookie jar and use its actual `headerName` and `token` with the same cookie jar:

```sh
curl --silent --show-error --cookie-jar /tmp/ledgerdesk-opening-cookies \
  http://localhost:8080/api/csrf > /tmp/ledgerdesk-opening-csrf.json

opening_csrf_header=$(python3 -c 'import json; print(json.load(open("/tmp/ledgerdesk-opening-csrf.json"))["headerName"])')
opening_csrf_token=$(python3 -c 'import json; print(json.load(open("/tmp/ledgerdesk-opening-csrf.json"))["token"])')

curl --include --user demo:demo-local-only \
  --cookie /tmp/ledgerdesk-opening-cookies \
  --header "$opening_csrf_header: $opening_csrf_token" \
  --header 'Content-Type: application/json' \
  --data @opening-preview.json \
  http://localhost:8080/api/opening-books/preview
```

These published credentials are only for the local demo profile. Use your configured owner login in other local installations. Successful responses have `Cache-Control: no-store`; returned decimal totals are strings. Valid drafts with differences or existing activity return HTTP 200 with blockers and `ready: false`. Malformed amounts, dates, account sides, references, source parties or missing/oversized lists return HTTP 400. Unauthorized roles and missing CSRF tokens cannot run the preview.

There is no `POST /api/opening-books` posting implementation yet. Do not use ordinary invoice/bill posting to imitate this import: that would record historical revenue/expenses in the operating books. The later controlled posting path must preserve source references and settle imported documents without duplicating profit.

## What this demonstrates

The preview separates arithmetic balance from document completeness: an equal debit/credit total is insufficient when control accounts disagree with the records people need to settle. Exact decimal calculations, repeatable-read database access, business-scoped source validation, bounded requests, conservative supported-account rules and no-write tests make those differences reviewable before a transaction is built.

`OpeningBooksTest` covers the worked figures, zero books and a carried loss, all three visible differences, source/control mismatches, existing-data blockers, supported date/amount limits, unsupported accounts and signs, normalized duplicate references, foreign-party rejection and foreign-activity exclusion. The HTTP checks enforce owner/CSRF access, private responses, unknown-field rejection and unchanged state.

## Verification

PR #40 source `89c31aa54a5c6ea6591c83b47d8c86e8ecaad972` passed [run 37284772514](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37284772514): all 324 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production frontend build and all 28 existing Chromium workflows. All nine opening-books tests passed on both databases. Native PostgreSQL and separate H2 earnings recovery, plus the existing receipt/password recovery checks, also passed.

Those browser/recovery checks are regression evidence. The new preview is tested through the service and authenticated HTTP fixtures; no opening-books posting or UI recovery is claimed. No browser screen changed in this milestone, so there are no new opening-books screenshots.
