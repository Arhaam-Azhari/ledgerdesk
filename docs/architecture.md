# Architecture

The browser calls `/api` through Vite's local proxy. Spring Security authenticates requests and checks CSRF tokens on writes. Controllers translate requests into accounting commands. `LedgerService` handles invoicing; `PurchaseService` handles bills and expenses. `BankService` parses/imports statement evidence, `BankMatching` manages associations, and `BankReconciliation` checks and closes statements. They share journal and retry helpers and use Spring JDBC inside a transaction. SQL is explicit so the accounting relationships and write order are easy to inspect.

## Data model

- `businesses`: the one configured demo business and currency.
- `accounts`: bank, receivables, payables, revenue, and six operating expense categories.
- `customers`: the parties being invoiced.
- `invoices`: posted invoice details and a paid-total cache.
- `invoice_numbers`: permanent sequential numbers; the next number lives on the locked business row.
- `invoice_drafts`: saved details, a version counter, and the posted/discarded state.
- `payments`: individual payments against an invoice.
- `vendors`: the parties supplying operating purchases.
- `bills` and `bill_payments`: posted liabilities and the payments allocated against them.
- `expenses`: purchases paid immediately, separate from bills.
- `receipts`: file bytes, content hashes, and metadata attached to exactly one bill or expense.
- `journal_entries` and `journal_lines`: the accounting record.
- `bank_imports` and `bank_transactions`: import summaries and deduplicated statement rows.
- `bank_matches` and `bank_match_events`: current one-to-one associations and retained match/undo events.
- `bank_reconciliations`: closed/reopened statement records, saved calculations, and owner/reason metadata.
- `commands`: successful request keys and payload fingerprints.
- `audit_events`: actor, action, time, and affected record.

The ledger is the source for account balances. Invoice and bill paid-total caches are updated together with their payment records and journal lines. There are no separate dashboard financial totals to keep synchronized.

## Concurrency and retries

All writes lock business row 1. For this single-business version, this makes concurrent posting simple and prevents two payments from spending the same outstanding balance. It also serializes the check for a previously used request key. This approach prioritizes correctness over write throughput; it should be revisited before supporting larger workloads or multiple businesses.

Successful commands retain a fingerprint and record ID. The frontend keeps a request key after a network failure, allowing a retry of the same payload. Editing the payload creates a new request; users should refresh and inspect records after an uncertain result before submitting different details.

The state endpoint reads in a repeatable-read transaction so documents, payments, and ledger totals come from a consistent snapshot. It currently returns all records, without pagination; large data volumes are outside this milestone's scope.

## Security boundaries

The default development servers bind to loopback. The demo has public local credentials and fictional data. The other profile requires credentials from the environment. SQL values use bound parameters, and React renders user text as text rather than HTML. CSRF protection stays enabled. Credentials are held in component memory and cleared by Lock workspace, which is disabled while requests are in progress; this is not a full server-session logout implementation.

The prototype has one configured owner. Persistent accounts, role separation, business-scoped authorization, rate limits, reviewed HTTPS hosting, backups, dependency review, and tamper-resistant audit storage remain release requirements.

## Invoice lifecycle and documents

Drafts live separately from posted invoices, so draft queries cannot accidentally inflate ledger balances. Editing checks the saved version after taking the business lock. Posting retains the draft and links it to its posted invoice; discarding retains the record but hides it from the active draft list. Both transitions prevent further editing.

The version 2 Flyway migration adds draft storage and backfills stable invoice numbers for existing invoices. Version 1 is unchanged so existing migration checksums remain valid. Upgrade testing starts from version 1 with an existing invoice and ledger entry, applies all current migrations, and checks that accounting records survive.

`InvoicePdf` receives a repeatable-read snapshot of the invoice, customer, and payments. PDFBox writes plain text into an embedded font; it does not fetch remote URLs or interpret customer text as HTML. Downloads require authentication and use `Cache-Control: no-store`. The filename is derived from the generated number, never from customer input. The bundled font license is in `backend/src/main/resources/fonts/LICENSE.txt`.

The frontend retains a mutation's request key until the subsequent workspace refresh also succeeds. A failed refresh after a successful posting can therefore be retried without producing a duplicate invoice.

## Purchases and receipt storage

Version 3 adds vendors, bills, bill payments, direct expenses, receipt storage, and expense/payable accounts. Versions 1 and 2 are unchanged, preserving migration checksums for existing demos. Purchase writes use the same business lock as invoices, so duplicate-reference checks, payment allocation, retry handling, and journal entries commit together. Voiding or correcting a record adds an offsetting entry and changes its status; it does not delete history.

Receipts are stored as database bytes for this small local application. This avoids a separate filesystem write that could succeed while the accounting transaction fails, or vice versa. The workspace endpoint returns receipt metadata only; bytes are loaded only for authenticated downloads. A future database backup must include the attachment table. Larger deployments will need storage quotas, pagination, and a reviewed object-storage design.

The server limits files to 2 MiB, five files per purchase, and a 3 MiB multipart request. Filename, declared MIME type, and parsed content must agree. ImageIO checks dimensions before decoding and re-encodes PNG/JPEG files to discard metadata and trailing data. PDFBox parses unencrypted PDFs of 1–20 pages; an object-graph check rejects actions, forms, embedded files, and excessive complexity. These checks reduce the accepted file surface; they are not a malware scanner or a full sandbox for hostile parsers.

Content hashes detect duplicate attachments within a purchase. Uploads use CSRF and request keys, and commit the bytes, metadata, request result, and activity event together. Download names use generated UUIDs, with attachment disposition, `nosniff`, and `Cache-Control: no-store`. User filenames are display metadata, never filesystem paths or response header values.

The frontend keeps the original upload request key until a successful workspace refresh, as it does for other writes. Its upload fingerprint includes the file contents, so a different selection becomes a different request. Receipt uploads and downloads do not post to the journal.


## Bank matching and closed periods

Flyway versions 4–6 add bank imports, matching, and reconciliation without rewriting the earlier migrations. A bounded UTF-8 CSV parser accepts one documented layout. Stable bank transaction IDs identify duplicates; changed details reject an entire import. Statement rows remain separate from the journal so an import cannot accidentally recognize revenue or expense again.

Unique constraints protect both sides of a match. Candidate queries restrict amount, direction, account, business, and supported posted sources. Match history is separate from the active association so undo never erases it. A current match ID prevents a stale tab from undoing a replacement match.

Preview reads a consistent snapshot. Close takes the same business lock as imports, matches and postings, recalculates, checks continuity and unresolved items, then saves the snapshot, request result and activity together. Journal posting checks the closed end date centrally; import and matching services apply the corresponding bank-row guards. Outstanding historical payments can clear against later statement rows without changing the earlier calculation.

Reopening checks the latest closed record and its version under that lock, retains the snapshot, and records the reason and owner. A replacement close creates a new record. This trades concurrency throughput for a simple, testable single-business correctness model.

The browser uses decimal strings/integer cents, invalidates previews after editing or refreshing, requires explicit close/reopen confirmation, and retains command keys after an uncertain refresh. A separate Playwright configuration starts an in-memory demo backend and frontend for reconciliation, preventing its closed periods from affecting other workflows.

## Owner equity

Migration V7 adds retained owner transfer records and separate contribution/drawing accounts. `EquityService` uses the existing business lock, retry fingerprint and two-line journal helper. A contribution credits equity; a withdrawal debits equity. These accounts feed the dated balance sheet without changing profit or customer/vendor aging. Owner cash lines join bank matching candidates and outstanding reconciliation entries, retaining their direction and memo.

The owner form uses the same request-key retention through a failed workspace refresh as other postings. Its history and totals include all recorded dates; dated statements remain a separate Reports view. No edit/delete or correction endpoint is exposed yet. General journal adjustments and owner transfer corrections remain the next accounting work.
