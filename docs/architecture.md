# Architecture

The browser calls `/api` through Vite's local proxy. Spring Security authenticates requests and checks CSRF tokens on writes. The API controller translates requests into accounting commands. `LedgerService` validates them and posts through Spring JDBC inside a database transaction.

## Data model

- `businesses`: the one configured demo business and currency.
- `accounts`: the three initial ledger accounts.
- `customers`: the parties being invoiced.
- `invoices`: posted invoice details and a paid-total cache.
- `invoice_numbers`: permanent sequential numbers; the next number lives on the locked business row.
- `invoice_drafts`: saved details, a version counter, and the posted/discarded state.
- `payments`: individual payments against an invoice.
- `journal_entries` and `journal_lines`: the accounting record.
- `commands`: successful request keys and payload fingerprints.
- `audit_events`: actor, action, time, and affected record.

The ledger is the source for account balances. Invoice paid totals are updated together with payment records and journal lines. There are no separate dashboard financial totals to keep synchronized.

## Concurrency and retries

All writes lock business row 1. For a single-business first milestone, this makes concurrent posting simple and prevents two payments from spending the same outstanding balance. It also serializes the check for a previously used request key. This approach prioritizes correctness over write throughput; it should be revisited before supporting larger workloads or multiple businesses.

Successful commands retain a fingerprint and record ID. The frontend keeps a request key after a network failure, allowing a retry of the same payload. Editing the payload creates a new request; users should refresh and inspect records after an uncertain result before submitting different details.

The state endpoint reads in a repeatable-read transaction so invoices and ledger totals come from a consistent snapshot. It currently returns all records, without pagination; large data volumes are outside this milestone's scope.

## Security boundaries

The default development servers bind to loopback. The demo has public local credentials and fictional data. The other profile requires credentials from the environment. SQL values use bound parameters, and React renders user text as text rather than HTML. CSRF protection stays enabled. Credentials are held in component memory and cleared by Lock workspace; this is not a full server-session logout implementation.

The prototype has one configured owner. Persistent accounts, role separation, business-scoped authorization, rate limits, reviewed HTTPS hosting, backups, dependency review, and tamper-resistant audit storage remain release requirements.

## Invoice lifecycle and documents

Drafts live separately from posted invoices, so draft queries cannot accidentally inflate ledger balances. Editing checks the saved version after taking the business lock. Posting retains the draft and links it to its posted invoice; discarding retains the record but hides it from the active draft list. Both transitions prevent further editing.

The version 2 Flyway migration adds draft storage and backfills stable invoice numbers for existing invoices. Version 1 is unchanged so existing migration checksums remain valid. Upgrade testing starts from version 1 with an existing invoice and ledger entry, applies version 2, and checks that accounting records survive.

`InvoicePdf` receives a repeatable-read snapshot of the invoice, customer, and payments. PDFBox writes plain text into an embedded font; it does not fetch remote URLs or interpret customer text as HTML. Downloads require authentication and use `Cache-Control: no-store`. The filename is derived from the generated number, never from customer input. The bundled font license is in `backend/src/main/resources/fonts/LICENSE.txt`.

The frontend retains a mutation's request key until the subsequent workspace refresh also succeeds. A failed refresh after a successful posting can therefore be retried without producing a duplicate invoice.
