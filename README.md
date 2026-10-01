# Ledgerdesk

A small-business accounting application for freelancers and service agencies. It connects invoices, purchases, payments, and bank statements to a double-entry ledger, so the amount earned, cash received or spent, and balances still owed stay separate.

The sample business is **Northline Design Studio**, a fictional agency using USD and accrual accounting. Invoicing, purchases, CSV bank imports, reviewed matching, and statement reconciliation are working milestones. Period reports, additional user roles, and deployment remain on the roadmap.

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
- Preview and import a bank CSV, skip identical duplicate rows, and reject conflicting IDs.
- Review exact-amount candidates and explicitly match recorded payments or expenses.
- Undo a match while retaining its history and leaving journal entries unchanged.
- Reconcile statement balances with the books and outstanding deposits/payments.
- Close a balanced statement, retain its calculation, and protect the closed period.
- Reopen the latest closed statement with a reason and preserve the original snapshot.
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

The figures include all recorded dates, including future dates. The overview and trial balance are all-time ledger balances. Reconciliation uses the chosen statement end date; opening balance migration and period financial reports are later milestones. Spending from an empty demo ledger can produce a negative recorded bank balance. This program records transactions; it does not move money.

## Import, match, and reconcile the statement

Continue from the otherwise empty walkthrough above:

1. Open **Bank imports** and download the example CSV, also available at [docs/examples/bank-statement.csv](docs/examples/bank-statement.csv). It contains the $700 customer payment, $200 bill payment, and $50 software expense from the walkthrough.
2. Select the file, label it `September–October statement`, and choose **Preview import**. Check the three rows, then **Confirm import**. The journal balances stay unchanged. Importing the same file again skips those rows.
3. Open **Bank matching**. For each of the three rows, choose **Review entries**, inspect the document description/date/amount, select the correct recorded entry, and **Confirm match**. An equal amount is a candidate, not proof of a match.
4. Open **Reconciliation**. Set the statement start to `2026-09-01`, end to `2026-10-31`, opening balance to `0.00`, and closing balance to `450.00`. These are fictional statement figures for this example.
5. Choose **Preview reconciliation**. Imported movement is $450, book balance is $450, outstanding deposits/payments are zero, and both differences are zero.
6. Confirm **Close statement**. The saved calculation appears in history. New journal postings dated through `2026-10-31`, new bank rows through that date, and changes to its bank matches are protected. The next statement starts `2026-11-01` with $450 carried forward.
7. To correct the closed books, enter a reason and confirm **Reopen latest statement**. Its original calculation remains in history. After corrections and reviewed matching, closing again creates a new record.

Closing rechecks the books on the server. A statement difference means the imported movement does not explain the entered balances; a book difference means the closing balance adjusted for outstanding entries does not agree with the ledger. Unmatched rows and bank matches to future-dated book entries must also be resolved. Outstanding payments may clear next month and remain outstanding in the earlier statement.

The first close starts from zero and includes the recorded history. Subsequent statements must follow without gaps or overlaps. Existing opening balance migration, live bank feeds, arbitrary export columns, split matches, and matching reversal entries are not implemented. The accepted CSV format and full rules are in [Bank imports](docs/bank-imports.md) and [Statement reconciliation](docs/bank-reconciliation.md).

Actual captures: [imported rows](docs/screenshots/bank-imports.png), [reviewed matches](docs/screenshots/bank-matching.png), [reconciliation review](docs/screenshots/reconciliation-review.png), [saved close](docs/screenshots/reconciliation-closed.png), and [mobile reopening](docs/screenshots/mobile-reconciliation.png). Captures use separate fictional test scenarios; their amounts may differ from this walkthrough.

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

The browser suite needs the demo backend running on port 8080 with a fresh database. Stop the backend and clear `backend/demo-data/` before running the suite against a disposable local demo. The tests create fictional records and do not clean them up afterward. Playwright starts the frontend if needed. The eight workflows cover purchases and receipts, invoices and drafts, imports and matching, balances, corrections, downloads, narrow screens, and request retries.

The ninth browser workflow runs with an isolated in-memory database so closing a period cannot affect the other tests. In a new terminal at the project root, build the JAR, then run the check from the frontend folder:

```sh
mvn -f backend/pom.xml package -DskipTests
cd frontend
npm run test:reconciliation
```

Keep ports 8081 and 5174 free. Playwright starts and stops both isolated servers. This workflow covers reconciliation preview, close, refresh-failure retry, closed-period protection, saved calculations, reopening, and mobile layout.

All 86 backend integration tests passed on each of H2 and PostgreSQL 17, and all nine Chromium workflows passed on GitHub Actions. See [verification notes](docs/verification.md) for the checks completed locally and on GitHub Actions.

## Next milestones

1. Date-based financial statements, aging reports, adjustments, and a broader accounting period workflow.
2. Persistent users, separate roles, business isolation, hardened deployment, backups, and restore testing.

The current version has one business and one configured owner login. Bookkeeper/reviewer roles, multi-business access, secure hosted sessions, and deployment are not implemented. Basic authentication is limited to local development; a hosted release will need HTTPS and a reviewed session-based login. Activity records are application history, not a tamper-proof audit system.

## What this project demonstrates

The useful problem here is tracing a sale or purchase from its document through payment to the ledger and bank statement. An invoice creates revenue before cash arrives; a bill creates an expense before cash leaves. Matching connects the bank evidence to the recorded cash movement, and reconciliation explains why the bank statement and books may differ. The tests check those differences and show that retries, concurrent payments, or failed writes do not silently change the books.

The implementation demonstrates exact monetary calculations, SQL relationships and constraints, transaction boundaries and rollback, concurrency control, request idempotency, optimistic draft version checks, database migrations, PDF generation, file validation, protected API writes, statement cutoffs, reconciliation snapshots, closed-period controls, and browser testing. The accounting notes explain each posting; the architecture notes explain why these techniques were chosen.

Further milestones will extend those foundations into a complete service-business accounting product.
