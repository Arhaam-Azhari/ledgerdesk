# Opening books: review, import and history

The existing [opening bank setup](opening-bank-balance.md) carries one cleared bank amount. A business with unpaid invoices or bills needs more: its carried receivable/payable balances must agree with documents that can later be collected or paid. This preview checks those relationships and the trial balance before importing anything.

The owner can preview and post through the browser or API. Preview is read-only: `ready: true` means the supported draft passes its current checks, not that an import has occurred. Posting rechecks the request under the business lock, then creates the reviewed opening setup, carried documents and journals in one transaction.

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

Use the controlled posting endpoint below. Do not use ordinary invoice/bill posting to imitate an opening import: that would record historical revenue/expenses in the operating books.

## What this demonstrates

The preview separates arithmetic balance from document completeness: an equal debit/credit total is insufficient when control accounts disagree with the records people need to settle. Exact decimal calculations, repeatable-read database access, business-scoped source validation, bounded requests, conservative supported-account rules and no-write tests make those differences reviewable before a transaction is built.

`OpeningBooksTest` covers the worked figures, zero books and a carried loss, all three visible differences, source/control mismatches, existing-data blockers, supported date/amount limits, unsupported accounts and signs, normalized duplicate references, foreign-party rejection and foreign-activity exclusion. The HTTP checks enforce owner/CSRF access, private responses, unknown-field rejection and unchanged state.

## Verification

PR #40 source `89c31aa54a5c6ea6591c83b47d8c86e8ecaad972` passed [run 37284772514](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37284772514): all 324 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production frontend build and all 28 existing Chromium workflows. All nine opening-books tests passed on both databases. Native PostgreSQL and separate H2 earnings recovery, plus the existing receipt/password recovery checks, also passed.

Those browser/recovery checks are regression evidence. The new preview is tested through the service and authenticated HTTP fixtures; no opening-books posting or UI recovery is claimed. No browser screen changed in this milestone, so there are no new opening-books screenshots.

## Post the reviewed opening

After reviewing the preview, submit the same request file to the owner posting endpoint with a stable request key. Use the cookie jar and actual CSRF values obtained above:

```sh
curl --include --user demo:demo-local-only \
  --cookie /tmp/ledgerdesk-opening-cookies \
  --header "$opening_csrf_header: $opening_csrf_token" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: opening-books-2025-reviewed' \
  --data @opening-preview.json \
  http://localhost:8080/api/opening-books
```

The response is `{"id":"..."}`. Posting locks the business, checks the original key first, revalidates the preview, and writes the native documents, cutover journals, bank cutoff, retained snapshot, source references, actor/time and audit/key together. Any failed write rolls them back, including invoice numbering. A second setup with a new key is blocked; an exact original retry returns the original ID, even after documents have been paid. Changed details with the original key are rejected.

The opening books and the earlier bank-only setup are alternatives. Existing bank setup blocks a full import; the full import records its own cleared bank balance and prevents a second bank setup. Zero books retain the reviewed cutoff with a null permanent-entry ID and no zero journal lines.

## Retained sources and posting treatment

The worked draft creates one invoice and one bill with their original issue/due dates and unpaid amounts. The invoice receives a stable internal `INV-...` number; its original external reference stays in `opening_book_invoices`. The bill retains its original reference in the native bill. Both source links retain the exact cutover journal ID. The import snapshot keeps the original reviewed, unpaid figures; current source amounts paid can later change.

The invoice's cutover entry debits receivables $100.10 and credits opening equity $100.10. The bill's cutover entry debits opening equity $40.04 and credits payables $40.04. A separate permanent-account entry carries bank and the reviewed equity balances, with the opposite opening-equity offsets. Final account balances equal the reviewed trial balance; opening-equity document bridges cancel without touching revenue or expenses. Each journal balances individually. Offsets stay as separate supported amount lines so their aggregation cannot overflow one stored monetary line.

This is why the import preserves both journals and source records. Customer statements and aging use those document-specific entries at cutover, so the $100.10 appears as a subsequent statement's opening receivable, rather than a new sale. Reports before cutover do not invent earlier balances from the historical issue dates. The first bank statement carries $1,000.25 without an outstanding deposit. The document exemption uses exact retained cutover journal IDs; an unrelated earlier journal remains blocked even if it shares a document source ID.

For the worked figures, subsequent full collection of $100.10 and payment of $40.04 leave bank $1,060.31, receivables/payables zero, operating profit zero and total equity $1,060.31. Partial settlements work through the ordinary payment endpoints. New operating invoices and bills after cutover still post their normal revenue and expense.

Ordinary invoice/bill void actions are blocked on imported sources. Reversing them as current sales/purchases would create a revenue/expense reversal for profit the import never recorded. Editing/reversing the opening import and correcting imported documents need a separate workflow; they are not supported by this milestone. Review the originals and preview carefully before posting.

## Read the import and settle its documents

All authenticated reading roles can inspect `GET /api/opening-books`; the response is `Cache-Control: no-store`. It contains:

- `openingBooks`: retained import, cutover, review note, original snapshot string, permanent entry, bank-opening reference and actor/time.
- `receivables`: native invoice IDs, original external references, customer IDs, cutover entry IDs and current document amounts/status.
- `payables`: native bill IDs, original references, vendor IDs, cutover entry IDs and current document amounts/status.

Use those native IDs for `POST /api/invoices/{invoiceId}/payments` or `POST /api/bills/{billId}/payments`, with CSRF, a new stable payment key, and `{"paidOn":"2026-01-01","amount":"..."}`. Owner and bookkeeper permissions for these routine payments remain the same. Payment dates must be after the opening cutoff; overpayment is rejected. Receipts can use the existing bill attachment workflow. Snapshot figures remain the record of the original import, not a live unpaid balance.

A populated opening-books backup/restore scenario remains a later checkpoint. The existing general and earnings restoration tests do not populate these new import tables, so they are regression evidence rather than proof of opening-import recovery.

## Posting verification

PR #41 source `a00cd2f59cfa95a7dbd2956c82a02949f646b813` passed [run 37346392467](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37346392467): 333 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production build and all 28 existing Chromium workflows. All nine posting tests passed on both databases. General native PostgreSQL and populated earnings restoration on H2/PostgreSQL also passed as regression checks.

The tests verify exact reviewed account balances, individually balanced journals, original source/entry references, snapshot/actor retention, zero earlier aging, subsequent customer opening statements, ordinary new operating work, partial/full settlement without duplicated profit, bank carry-forward, protected cutover dates, ordinary-void and overpayment denials, original-key retries, changed/duplicate setup denials, rollback including numbering, zero/loss/large bridges, competing imports, multiple parties and owner/CSRF/key permissions. The first run caught the first-bank-review guard treating noncash cutover journals as earlier operating activity. The corrected path retains their exact journal IDs and the test verifies that a different earlier journal sharing a document source ID is still rejected.

There is no new UI or opening-import restoration fixture in this milestone; those remain the next verification stages.


## Browser workflow

1. On fresh books, add the customers and vendors first. Sign in as the owner and open **Opening books**.
2. Enter the last date covered by prior books and a review note. Copy the debit and credit balances for the supported seven accounts; leave unused balances at zero. The bank amount must be cleared at cutover.
3. Use **Add unpaid invoice** and **Add unpaid bill** for fully unpaid originals. Select the party and enter the external reference, description, original issue/due dates and amount. Historical partial payments, scheduled assets and other unsupported balances need a separate migration approach.
4. Click **Preview opening books**. Check the debit/credit totals, control/document differences, operating start date and every displayed source. Resolve any blockers. Editing any field or removing a document clears the preview and requires another review.
5. Click **Import opening books**, then confirm only if the reviewed figures are correct. The server rechecks them before posting. There is no opening-import correction or reversal workflow yet. If a response fails, keep the displayed reviewed request and retry the same action; its request key survives until the import and workspace refresh both succeed.
6. Click **Load opening history**. Expand **Original opening review** for the retained cutover figures, and **Opening evidence references** for native document and journal IDs. Current document settlement shows later payments separately from the immutable original review. A failed history load clears the previous display.
7. Collect/pay the imported documents using the ordinary Invoices and Bills pages. Reload the workspace and history to see current settlement. Owner, bookkeeper and reviewer can read history; only the owner sees preparation, preview and import controls.

An already recorded opening hides the editor, including a prior bank-only setup. Bank-only setup has no opening-books import record. Other existing accounting activity is rejected by server preview checks. This page does not quietly turn old operating activity into fresh books.

### Browser fixture awaiting execution

The fictional browser fixture uses a 2039-12-31 cutover: bank $1,000.25, receivables $100.10, payables $40.04, owner capital $1,000.25 and retained earnings $60.06. Both trial-balance sides total $1,100.35. The unpaid sources are OLD-INV-7 and OLD-BILL-9, with January 2040 due dates. The later full settlements leave both unpaid balances at zero while the original review retains its import figures.

The Chromium fixture is prepared to capture desktop and 390-pixel preview/history images. These captures are not yet published: the corrected browser check is waiting for a GitHub runner. Prepared tests and screenshots are not passing evidence.
