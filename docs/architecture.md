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

The owner form uses the same request-key retention through a failed workspace refresh as other postings. Its history and totals include all recorded dates; dated statements remain a separate Reports view. Migration V8 stores one reversal per transfer, with a date and reason. The original entry is retained; its offset posts on the reversal date. A correction requires open original/reversal dates and an unmatched original bank line. Reversed originals cannot be selected for bank matching. The reversal and activity/command records share the posting transaction and retry lock. There is no edit/delete endpoint. General journal adjustments remain the next accounting work.

## Expense adjustments

Migrations V9 and V10 retain adjustment headers and one optional dated reversal per header. `AdjustmentService` validates distinct expense accounts, bounded line counts, exact monetary values and balanced totals before posting under the business lock. Bank, receivables, payables and equity retain their dedicated workflows. This keeps statement evidence, payment allocation and aging aligned with their control balances.

A reversal validates the original allocation and swaps every debit/credit without deleting or editing original journal lines. Its date must be in an open period and on or after the original. A later expense reclassification reversal can preserve an earlier closed period because it has no cash or payment allocations to reinterpret. History exposes original and reversal lines separately with their dates.

The controlled browser editor uses integer cents for totals and retains its details until posting and the following workspace refresh both succeed. Reversal forms use the same retry mechanism. Purchase metadata remains the original document description/category; reports use the journals. Independent adjustments require separate review if a purchase is later voided. Arbitrary journals, accruals, prepayments and depreciation are outside this expense-reclassification milestone.


## Accruals and incoming supplier bills

An expense estimate debits its operating category and credits a separate accrued-expenses liability. Vendor aging starts when an actual supplier bill is posted. Receiving that bill creates the estimate reversal, actual bill and their retained link in one transaction and under the same business lock used by other posting commands. A unique accrual-to-bill link prevents repeated consumption of an estimate. One request key and activity event cover the whole handoff; a failed bill or activity write rolls back the reversal too.

The estimate keeps its original date. The reversal and bill share the new bill date and original category, so a report at an earlier cutoff remains unchanged while the later period records only the amount difference. Cash changes through the existing bill-payment workflow. Ordinary bill posting and handoff share bill creation rules. Current bill status is shown alongside the retained link, including a later void, without silently restoring the estimate.

The browser uses integer cents for the preview and keeps its open draft after an interrupted refresh. Retrying the same details retrieves the original bill. A manual reversal remains available for correcting an estimate without a bill; one unreversed estimate can instead be handed off to one new actual bill.

## Prepaid expense schedules

A paid purchase retains its original cash journal and receipts. A dated setup entry debits the prepaid asset and credits the purchase's expense category. Monthly schedule rows carry exact amounts but affect reports only when recognized. Recognition reverses part of the asset into the original category; integer-cent previews and decimal backend calculations assign the rounding remainder to the final month.

Cancellation expenses the remaining benefit on a later open date and blocks future recognition. Correction is limited to an untouched plan and offsets the setup on its original open date. Neither changes cash or bank matches. Separate retained records distinguish the two actions. The original purchase stays protected from reversal until its plan has been corrected; normal bank-match guards still apply afterward. Commands, journals, request keys and activity share one transaction and business lock.

## Fixed assets

A fixed asset references one retained paid purchase. Setup reclassifies its original expense into equipment cost on the original payment date, preserving cash, receipts and bank matches. Whole-month schedules allocate cost less residual value using exact decimals; the final row takes the rounding remainder. Unposted rows do not affect reports. Depreciation debits expense and credits accumulated depreciation, a contra-asset kept separate from cost.

Before any depreciation, correction offsets setup on the original open date. Zero-proceeds retirement validates the setup and posted depreciation journals, removes cost and accumulated depreciation and records remaining book value as a loss. Earlier scheduled months must be posted first. Retirement on a later open date preserves closed historical balances. Source locks, transaction boundaries, audit entries and idempotency records keep retries atomic. Purchase eligibility prevents active prepaid and fixed-asset allocations on the same payment.

## Cash activity

The cash activity report reads bank-account journal lines in a repeatable-read transaction. Opening and closing balances use independent ledger sums; period cash lines retain gross receipts and payments, including reversals. A grouped counterpart query avoids multiplying cash lines when an entry has several noncash lines. A single known counterpart supplies the original posting group; mixed or unknown entries remain in Other. Later noncash capitalization does not rewrite an original cash group.

The response keeps entry, source and cash-line IDs for traceability. No report request writes accounting or audit records. The screen invalidates results on date/workspace changes, and CSV exports use displayed report dates, escaped text and validated monetary values. Formal cash-flow classification is outside this report's scope.

## Owner and reviewer permissions

The local account store can configure an owner and a distinct optional reviewer, with BCrypt-encoded passwords. The security filter permits anonymous CSRF retrieval, requires authentication for GET/HEAD, and requires the owner role for other methods. CSRF remains enabled. This rule protects current and future write routes before controller execution; frontend navigation is an additional usability layer.

The frontend reads the no-store access response and workspace data before setting permission state. Reviewers receive only reports and ledger navigation. A frontend action guard rejects mutation attempts, while the backend independently enforces role permissions. Locking clears credentials, permissions, workspace data and pending request keys; the control is in the responsive header. This remains configured in-memory access for one local business, with persistent users, memberships and hosted session handling still to be implemented.
