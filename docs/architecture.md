# Architecture

The browser calls `/api` through Vite's local proxy. Spring Security authenticates requests and checks CSRF tokens on writes. The API controller translates requests into accounting commands. `LedgerService` validates them and posts through Spring JDBC inside a database transaction.

## Data model

- `businesses`: the one configured demo business and currency.
- `accounts`: the three initial ledger accounts.
- `customers`: the parties being invoiced.
- `invoices`: posted invoice details and a paid-total cache.
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
