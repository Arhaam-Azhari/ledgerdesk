# Ledgerdesk

A small-business accounting application for freelancers and service agencies. It connects customer invoicing and vendor purchases to a double-entry ledger, so the amount earned, the money received or spent, and the balances still owed stay separate.

The sample business is **Northline Design Studio**, a fictional agency using USD and accrual accounting. Invoicing and purchases are working milestones. Bank reconciliation, period reports, and deployment remain on the roadmap.

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
- Add vendors and inspect their bills, payments, and outstanding balances.
- Post operating expense bills and record partial or full bill payments.
- Record purchases paid immediately as direct expenses.
- Void an unpaid bill or reverse a mistaken direct expense, retaining the original.
- Attach and download validated PDF, PNG, or JPEG receipts.
- View all-time bank, receivables, payables, revenue, and operating expense balances.
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

Continue with the purchase side:

1. On **Vendors**, add Harbor Supply (`accounts@harbor.example`) and Cloudline Tools (`billing@cloudline.example`).
2. On **Bills**, select Harbor Supply, reference `SUP-104`, description `Office supplies for October`, category **Office supplies**, bill date `2026-10-01`, due date `2026-10-31`, and amount `600.00`. Choose **Post bill**.
3. Pay `200.00` against that bill dated `2026-10-02`. Its outstanding amount becomes $400; the payment does not record the expense again.
4. Under **Supporting receipts**, choose the bill and attach [the fictional supply receipt](frontend/tests/fixtures/supply-receipt.png). Download it from the receipt table.
5. On **Expenses**, select Cloudline Tools, description `October design software`, category **Software subscriptions**, date `2026-10-03`, and amount `50.00`. Choose **Record expense**. Optionally attach [the fictional software receipt](frontend/tests/fixtures/software-receipt.jpg).
6. On **Vendors**, Harbor Supply shows $600 billed, $200 paid, and $400 outstanding. The direct software expense is already paid and contributes no payable balance.
7. If these are the only transactions, the overview shows $450 bank, $500 receivables, $400 payables, $1,200 revenue, and $650 expenses. Trial balance totals are $1,600 on each side.

The figures include all recorded dates, including future dates. They are ledger balances; bank-statement reconciliation, opening balances, and period reports are later milestones. Spending from an empty demo ledger can produce a negative recorded bank balance. This program records transactions; it does not move money.

## Drafts, invoice numbers, and PDFs

On **Invoices**, fill in all invoice details and choose **Save draft**. Drafts do not recognize revenue or create receivables. Use **Edit draft** to revise the saved amount or details, then **Save changes**. **Post draft** posts exactly the saved version and assigns a number such as `INV-000001`. **Discard draft** removes it from the active list while retaining its saved record and activity history.

A draft must have complete, valid invoice details; partially filled forms are not saved. Posting or discarding makes the saved copy read-only. If another tab has changed a draft, reload the workspace before editing or posting it. Save changes before posting; unsaved form edits are not posted.

Numbers increase within this one business and are not reused after voiding. The number and ledger entry commit together. Earlier invoices receive numbers ordered by invoice date and internal ID during the migration. This is an application sequence, not a claim of compliance with jurisdiction-specific invoice numbering requirements.

Choose **PDF** next to a posted invoice. The PDF includes the customer, service description, total, recorded payments, and current amount due. A voided copy clearly says **VOID** and shows zero due. The embedded DejaVu font supports common Latin and other characters; unsupported glyphs appear as explicit Unicode codes rather than disappearing. Long descriptions wrap, and payment histories continue across pages.

On **Customers**, balances exclude drafts and voided invoices. Select a customer name to inspect their posted invoices. These are all-time balances; aging and date-based statements come later.

Screenshots: [saved drafts](docs/screenshots/drafts.png), [customer balances](docs/screenshots/customer-balances.png), [narrow-screen overview](docs/screenshots/mobile-overview.png), and an [example invoice PDF](docs/invoice-example.pdf). All use fictional data.

## Bills, expenses, and receipts

Use **Bills** for an operating purchase that the business owes a vendor. Use **Expenses** for a purchase paid immediately from the business bank. Do not enter the same purchase in both places. Categories cover office supplies, software subscriptions, professional services, rent and utilities, business travel, and other operating expenses. Capital assets, recoverable tax, and inventory need later accounting workflows.

A vendor's bill reference is unique after trimming spaces and ignoring case. A voided reference remains reserved; a replacement must use a distinguishable revised reference. Posted amounts are not edited. **Void bill** creates an offsetting entry for an unpaid bill. **Reverse expense** corrects a mistaken bookkeeping entry; it does not process a real refund. Paid bills require a future vendor-credit/refund workflow.

Each bill or expense accepts up to five receipts, no larger than 2 MiB each. Images must be readable PNG or JPEG files of at most 10 megapixels. PDFs must be static, unencrypted, and contain 1–20 pages; actions, forms, and embedded files are rejected. Files are checked on the backend, not just by their filename. Uploading the same content to the same record reuses its attachment. Attaching or downloading a receipt leaves the ledger unchanged.

Receipts stay in the local database with the purchase record and require the workspace login to download. Public repository screenshots and receipt fixtures contain fictional data. Content checks are not a malware-scanning service; hosted upload security remains part of deployment work.

Actual captures: [bills and receipts](docs/screenshots/bills.png), [direct expenses](docs/screenshots/expenses.png), [vendor balances](docs/screenshots/vendor-balances.png), and [trial balance](docs/screenshots/trial-balance.png).

![Overview after an invoice, a bill payment, and a direct expense](docs/screenshots/overview.png)

## Stack and design

- Java 17 / Spring Boot, Spring JDBC, and Spring Security
- React / TypeScript / Vite
- PostgreSQL configuration and Flyway migrations; H2 for the local demo
- Apache PDFBox for invoice PDFs and receipt PDF validation; Java ImageIO for receipt images
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

The browser suite needs the demo backend running on port 8080 with a fresh database. Stop the backend and clear `backend/demo-data/` before running the suite against a disposable local demo. The tests create fictional records and do not clean them up afterward. Playwright starts the frontend if needed. The suite covers purchases and receipts, invoices and drafts, balances, corrections, downloads, narrow screens, and request retries.

All 45 backend integration tests passed on H2 and PostgreSQL 17, and all six Chromium workflows passed on GitHub Actions. See [verification notes](docs/verification.md) for the checks completed locally and on GitHub Actions.

## Next milestones

1. CSV bank imports, duplicate detection, matching, and reconciliation.
2. Date-based financial statements, aging reports, adjustments, and period close.
3. Persistent users, separate roles, business isolation, hardened deployment, backups, and restore testing.

The current version has one business and one configured owner login. Bookkeeper/reviewer roles, multi-business access, secure hosted sessions, and deployment are not implemented. Basic authentication is limited to local development; a hosted release will need HTTPS and a reviewed session-based login. Activity records are application history, not a tamper-proof audit system.

## What this project demonstrates

The useful problem here is tracing a sale or purchase from its document through payment to the ledger. An invoice creates revenue before cash arrives; a bill creates an expense before cash leaves. The tests check those differences and show that retries, concurrent payments, or failed writes do not silently change the books.

The implementation demonstrates exact monetary calculations, SQL relationships and constraints, transaction boundaries and rollback, concurrency control, request idempotency, optimistic draft version checks, database migrations, PDF generation, file validation, protected API writes, and browser testing. The accounting notes explain each posting; the architecture notes explain why these techniques were chosen.

Further milestones will extend those foundations into a complete service-business accounting product.
