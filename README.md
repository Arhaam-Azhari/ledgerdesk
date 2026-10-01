# Ledgerdesk

A small-business accounting application for freelancers and service agencies. The first milestone connects customers, posted invoices, partial payments, a double-entry ledger, and a trial balance.

The sample business is **Northline Design Studio**, a fictional agency using USD and accrual accounting. This is an early working milestone, not a finished accounting product.

## Working now

- Add customers and post a service invoice.
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

## Stack and design

- Java 17 / Spring Boot, Spring JDBC, and Spring Security
- React / TypeScript / Vite
- PostgreSQL configuration and Flyway migrations; H2 for the local demo
- JUnit integration checks and a Playwright browser workflow

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

GitHub Actions is configured to test H2 and PostgreSQL plus the browser workflow when this project is published. No GitHub Actions result is claimed yet.

## Next milestones

1. Draft invoices, numbering, invoice PDFs, and richer customer balances.
2. Vendor bills and expenses, including payment allocation and supporting documents.
3. CSV bank imports, duplicate detection, matching, and reconciliation.
4. Date-based financial statements, aging reports, adjustments, and period close.
5. Persistent users, separate roles, business isolation, hardened deployment, backups, and restore testing.

The current version has one business and one configured owner login. Bookkeeper/reviewer roles, multi-business access, secure hosted sessions, and deployment are not implemented. Basic authentication is limited to local development; a hosted release will need HTTPS and a reviewed session-based login. Activity records are application history, not a tamper-proof audit system.

## What this project demonstrates

The first milestone demonstrates double-entry posting, invoice-to-payment accounting, exact monetary calculations, SQL relationships and constraints, transactional rollback, serialized concurrent writes, retry handling, protected API writes, and a tested browser workflow.

Further milestones will extend those foundations into a complete service-business accounting product.
