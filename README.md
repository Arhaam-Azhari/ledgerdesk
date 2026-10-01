# Ledgerdesk

A small-business accounting application for freelancers and service agencies. The invoice milestone connects saved drafts, numbered invoices, partial payments, customer balances, PDF downloads, a double-entry ledger, and a trial balance.

The sample business is **Northline Design Studio**, a fictional agency using USD and accrual accounting. This is a working invoice milestone, not a finished accounting product.

## Working now

- Add customers and save, edit, post, or discard a complete invoice draft.
- Assign a permanent sequential invoice number when posting.
- Download an invoice PDF with its status, payment history, and amount due.
- Inspect each customer's invoices, payments total, and outstanding balance.
- Post a service invoice directly when no draft is needed.
- Record partial or full payment without recognizing revenue again.
- Reject overpayments, invalid amounts, and dates before the invoice.
- Retry a request without recording it twice.
- Void an unpaid invoice through a reversing entry, preserving the original.
- Inspect payment history, journal lines, net account balances, and recorded actions.
- View all-time bank, receivables, and service-revenue balances.
- Keep local demo records between restarts.

## Run locally

Install **JDK 17+, Maven 3.9+, and Node.js 22.12+**. Commands below work from the project folder in a terminal. The application uses two terminals during development.

Terminal 1:

```sh
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Terminal 2:

```sh
cd frontend
npm ci
npm run dev
```

Open **http://127.0.0.1:5173**. Sign in with `demo` / `demo-local-only`.

The demo uses an H2 database in `backend/demo-data/`, so Docker is not needed to try the workflow. Both development servers bind to the local machine. Use fictional data. The known demo password is for local development only.

Stop both servers with Ctrl+C. To clear the local demo, stop the backend and delete `backend/demo-data/`; the next launch creates an empty ledger and the example customer again.

## Try the accounting workflow

1. Open **Invoices** and choose Maple Coffee Co.
2. Enter a description, invoice date `2026-09-01`, due date `2026-09-30`, and amount `1200.00`.
3. Post the invoice. Receivables and service revenue each increase by $1,200.
4. Record a `700.00` payment dated `2026-09-03` against that invoice.
5. Check the overview: recorded bank balance is $700, receivables are $500, and service revenue is $1,200, assuming an otherwise empty ledger.
6. Open **General ledger** to inspect both entries, then **Trial balance** to see total debit and credit balances of $1,200 each.

These figures are not an income statement or reconciled bank balance. Expenses, opening balances, and period reporting are later milestones.

## Drafts, invoice numbers, and PDFs

On **Invoices**, fill in all invoice details and choose **Save draft**. Drafts do not recognize revenue or create receivables. Use **Edit draft** to revise the saved amount or details, then **Save changes**. **Post draft** posts exactly the saved version and assigns a number such as `INV-000001`. **Discard draft** removes it from the active list while retaining its saved record and activity history.

A draft must have complete, valid invoice details; partially filled forms are not saved. Posting or discarding makes the saved copy read-only. If another tab has changed a draft, reload the workspace before editing or posting it. Save changes before posting; unsaved form edits are not posted.

Numbers increase within this one business and are not reused after voiding. The number and ledger entry commit together. Earlier invoices receive numbers ordered by invoice date and internal ID during the migration. This is an application sequence, not a claim of compliance with jurisdiction-specific invoice numbering requirements.

Choose **PDF** next to a posted invoice. The PDF includes the customer, service description, total, recorded payments, and current amount due. A voided copy clearly says **VOID** and shows zero due. The embedded DejaVu font supports common Latin and other characters; unsupported glyphs appear as explicit Unicode codes rather than disappearing. Long descriptions wrap, and payment histories continue across pages.

On **Customers**, balances exclude drafts and voided invoices. Select a customer name to inspect their posted invoices. These are all-time balances; aging and date-based statements come later.

Screenshots: [saved drafts](docs/screenshots/drafts.png), [customer balances](docs/screenshots/customer-balances.png), [narrow-screen overview](docs/screenshots/mobile-overview.png), and an [example invoice PDF](docs/invoice-example.pdf). All use fictional data.

## Stack and design

- Java 17 / Spring Boot, Spring JDBC, and Spring Security
- React / TypeScript / Vite
- PostgreSQL configuration and Flyway migrations; H2 for the local demo
- Apache PDFBox for invoice PDFs, with an embedded DejaVu font
- JUnit integration checks and Playwright browser workflows

This is one backend with separate service, API, and security responsibilities. JDBC makes the SQL and transaction boundaries visible. Java `BigDecimal` and SQL `NUMERIC` handle money. The API serializes amounts as decimal strings, and the interface uses integer cents for sums and display.

See [Accounting rules](docs/accounting.md), [Architecture](docs/architecture.md), and [Verification](docs/verification.md).

## PostgreSQL development setup

Install Docker if you want to use PostgreSQL. Create a local `.env` containing `DATABASE_PASSWORD=your-local-database-password`, then run:

```sh
docker compose up -d database
```

Export `DATABASE_PASSWORD`, `APP_USERNAME`, and `APP_PASSWORD` into the backend terminal before running `mvn spring-boot:run` without the demo profile. Docker Compose reads `.env`; Maven does not automatically load it. The defaults are database `ledgerdesk`, database user `ledgerdesk`, and URL `jdbc:postgresql://localhost:5432/ledgerdesk`.

For PowerShell, set variables with `$env:DATABASE_PASSWORD="..."`; for bash, use `export DATABASE_PASSWORD="..."`. Supply your own application username and password through the equivalent `APP_USERNAME` and `APP_PASSWORD` variables. Never commit these values.

The initial PostgreSQL configuration is for local development. The verification document distinguishes checks actually run from checks prepared for CI.

## Tests

Backend:

```sh
cd backend
mvn test
```

Frontend:

```sh
cd frontend
npm ci
npm run build
npx playwright install chromium
npm run test:e2e
```

The browser test needs the demo backend running on port 8080. Playwright starts the frontend if needed. It posts a $1,200 invoice and $700 payment, checks the remaining balance and ledger, and captures the overview and trial balance.

The invoice milestone has 24 backend integration tests and four browser workflows. See [verification notes](docs/verification.md) for the checks completed locally and on GitHub Actions.

## Next milestones

1. Vendor bills and expenses, including payment allocation and supporting documents.
2. CSV bank imports, duplicate detection, matching, and reconciliation.
3. Date-based financial statements, aging reports, adjustments, and period close.
4. Persistent users, separate roles, business isolation, hardened deployment, backups, and restore testing.

The current version has one business and one configured owner login. Bookkeeper/reviewer roles, multi-business access, secure hosted sessions, and deployment are not implemented. Basic authentication is limited to local development; a hosted release will need HTTPS and a reviewed session-based login. Activity records are application history, not a tamper-proof audit system.

## What this project demonstrates

The current milestone demonstrates double-entry posting, invoice-to-payment accounting, exact monetary calculations, SQL relationships and constraints, transactional rollback, serialized concurrent writes, retry handling, protected API writes, optimistic draft version checks, database upgrades, PDF generation, and tested browser workflows.

Further milestones will extend those foundations into a complete service-business accounting product.
