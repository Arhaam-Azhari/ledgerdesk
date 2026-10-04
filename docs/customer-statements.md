# Customer account statements

Customer statements connect an opening amount owed, invoice charges, payments and retained invoice reversals to a closing balance for an inclusive period. Open **Reports**, choose **Customer statements**, select a customer and dates, then choose **Run statement**. Owners, bookkeepers and reviewers can use it.

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

Six integration tests cover known opening/movement/closing amounts, payment-to-invoice linkage, inclusive boundaries, agreement with dated receivable aging, unchanged accounting/activity/commands, later settlements and reversals, same-day ordering, exact split payments, stable line IDs, empty/date-limit cases, drafts/future invoices, other customers/businesses, authentication/reading roles, cache headers and invalid parameters. Run `mvn -f backend/pom.xml -Dtest=CustomerStatementTest test` against a disposable test database. Source `f6e64af527261dcb29d5dd5a72f40762535f04b1` passed all three jobs in [run 37230201532](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37230201532): 281 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production frontend build, all 24 existing Chromium workflows and native PostgreSQL restoration. The initial test annotation import was corrected before this passing checkpoint. At that API checkpoint, browser checks covered existing screens. Statement-screen verification follows below.

This covers the supported invoice/payment/reversal workflow. Opening unpaid-document migration, credit notes, refunds, arbitrary receivable journals, foreign currencies, historical contact versions and delivery remain outside the scope. The screen and CSV are internal accounting tools. The PDF download is described below. Customer delivery remains outside the current scope.


## Screen and CSV

The summary shows opening, charges, payments, reversals and closing. Activity rows show the invoice number/description, date, charge/reduction and running balance. Expand **Ledger references** to inspect the invoice, payment and journal IDs. On a phone, scroll the activity table horizontally; summary amounts remain visible above it. The screen explains same-day presentation order and current contact details.

**Export statement CSV** downloads the displayed customer/date context, totals, activity and ledger IDs. The filename uses the customer ID and both dates. Files are UTF-8 with a byte-order mark, quoted fields, CRLF rows and validated decimal money. Formula-looking contact/description text is prefixed with an apostrophe. Customer/date edits, workspace reloads, mode changes and failed reads clear old results before another export. Retry **Run statement** after a failed read; it does not change the books.

The screen builds locally. A new reports browser scenario checks the known $325.10 statement after later settlements, payment linkage, ledger references, CSV content, formula-looking names, a carried balance in an empty period, date errors, failed-read retry, cleared results, unchanged records and mobile horizontal scrolling. Reviewer/bookkeeper scenarios also run statements. Run `npm run test:reports`, `npm run test:reviewer` and `npm run test:bookkeeper` in `frontend` after packaging the backend and installing Chromium. Screen source `bd1358fe8504693277c6881c471e99e1d02fc944` passed all three jobs in [run 37231613956](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37231613956): 281 integration tests on each of H2 and PostgreSQL 17 with zero failures/errors/skips, 14 backup-tool tests, production frontend build, all 25 Chromium workflows and native PostgreSQL restoration. The first browser attempt stopped at an exact-label dropdown lookup; the corrected test uses its accessible role/name. Both original statement captures were downloaded and visually reviewed.


## Screen evidence

Cedar Design Partners uses the worked amounts above with fictional September–November 2030 dates. Its October statement still closes at $325.10 after the November payments settle the invoices. The desktop capture expands the $50.05 payment's ledger references. The mobile capture shows the visible summary and the activity table scrolled to its evidence column.

![Customer statement with running balances and expanded payment evidence](screenshots/customer-statement.png)

[Mobile statement summary and horizontally scrolled ledger evidence](screenshots/mobile-customer-statement.png)


## PDF download API

Use the workspace login with:

```text
GET /api/reports/customers/demo-customer/statement/pdf?startsOn=2030-10-01&endsOn=2030-10-31
```

This endpoint downloads `ledgerdesk-customer-statement-2030-10-01-2030-10-31.pdf`. Owners, bookkeepers and reviewers can read it. Anonymous requests return 401; invalid dates and unknown/out-of-scope customers return 400 before rendering. Successful responses use `application/pdf`, attachment disposition, `Cache-Control: no-store` and the security filter's `nosniff` header. The filename uses validated dates rather than contact text.

The PDF carries current customer details, both dates, the five summary amounts, dated invoice/payment/reversal activity, descriptions and running balances. It reads a fresh statement in one repeatable-read transaction. Later-dated payments leave earlier figures intact; a new backdated posting can change a subsequent download. CSV exports the displayed snapshot, while this API reads the books again. Reading or downloading creates no accounting, audit or request-key rows.

The invoice and statement renderers share embedded-font wrapping and pagination. Page footers repeat the selected period and page number. Long names, emails and descriptions wrap; unsupported font characters appear as `[U+XXXX]` with an explanatory note. The PDF is an internal accounting copy: ledger IDs remain available in the screen/CSV, and payment instructions and delivery are not configured. The screen uses this endpoint through **Download statement PDF**.

Run `mvn -f backend/pom.xml -Dtest=CustomerStatementPdfTest,LedgerWorkflowTest test` against a disposable test database. Five new PDF tests cover the worked $325.10 historical closing after later settlement, unchanged journal/activity/request keys, empty carried balances, date limits, all reading roles, invalid requests, Unicode/control text and multipage content. The multipage check also measures every glyph against page margins. Existing invoice PDF tests cover number, payment balance, void status, pagination and character fallback after the shared-layout extraction. CI retains `statement-pdf-proof` with a worked statement and a deliberately long multipage fixture for visual review.


PDF source `976e5097a068a58d6e1cd0de623cc4fe98afc8a3` passed 286 integration tests on each of H2 and PostgreSQL 17, with zero failures/errors/skips, and 14 backup-tool tests in [run 37233147797](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37233147797). The production frontend build, all 25 existing Chromium workflows and native PostgreSQL restoration also passed. At that API checkpoint, browser checks were regression evidence and the PDF button was not yet present. The first fixture used a name above the database's 120-character limit; the corrected checkpoint changes only that test name. Original generated PDF artifacts were downloaded, and the worked page plus all six long-text pages were rendered and visually reviewed. There was no clipping or overlap; selected periods and page numbers remained readable throughout.

### Rendered PDF proof

The [downloadable sample](examples/customer-statement.pdf) is the original PDF from the passing test, using fictional Cedar Design Partners activity in October 2030. Its closing $325.10 remains after November settlement. The preview below is rendered from that PDF, rather than a mockup. The six-page fixture is retained in the run's `statement-pdf-proof` artifact.

![Rendered customer statement PDF with dated activity and a $325.10 closing balance](screenshots/customer-statement-pdf.png)


## Downloading from Reports

After **Run statement**, choose **Download statement PDF** beside the CSV button. The download uses the displayed customer and dates and names the file with both dates. Owners, bookkeepers and reviewers can download. A missing or cleared statement has no download button; customer/date changes, reload and report-mode changes clear the old result.

PDF reads fresh books. If someone posts another entry dated within or before the period, rerun the statement to compare the latest figures before downloading. CSV keeps the displayed snapshot. Neither action emails the customer or changes the books.

While a download is pending, the date/customer editor and exports are disabled. If it fails, the statement stays visible and an error appears; choose the PDF button again to retry. The browser checks HTTP success, PDF content type and the opening `%PDF-` signature before saving, so a typical error page or wrong response is not downloaded as a document. These format checks are not a general validator for arbitrary uploaded PDFs.

The extended reports scenario checks authenticated PDF response bytes, filename, customer/date parameters, no-store/nosniff headers, disabled controls during a held request, 503 and malformed-response recovery, a successful retry, an empty period with carried balance, result clearing and unchanged books. Bookkeeper and reviewer scenarios also download statements. Use `npm run test:reports`, `npm run test:bookkeeper` and `npm run test:reviewer` after packaging the backend and installing Chromium.


Screen source `f8bd8c032e49d6984aff0d8fb3c7213a0e190d8c` passed all three jobs in [run 37234033962](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37234033962): 286 integration tests on each of H2 and PostgreSQL 17, zero failures/errors/skips, 14 backup-tool tests, production build, all 25 Chromium workflows and native PostgreSQL restoration. The statement scenario downloaded the actual PDF, verified its customer/dates/headers/signature, held a request to check disabled controls, rejected 503/HTML/malformed-PDF responses, retried successfully and checked an empty carried period. Bookkeeper and reviewer scenarios also downloaded statements.

Updated desktop/mobile captures were downloaded and visually reviewed, replacing the earlier versions at the same paths. Buttons have spacing on desktop and stack on the 390-pixel screen; the page fits while the movement table scrolls independently. The PDF saved by Chromium was extracted and rendered for review and shows the same $325.10 closing, dated movements and contact as the screen. It is retained as `report-results/customer-statement-download.pdf` in the run's `browser-results` artifact. The separate API sample and browser scenario use their own fictional fixtures with the same balances.
