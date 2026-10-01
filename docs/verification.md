# Verification

Verification is recorded per milestone; planned checks are not treated as passing checks.

## Backend

12 integration tests use the real Spring services, database transactions, migration, and security filter chain:

1. A partial payment separates cash, revenue, and receivables.
2. $0.10 plus $0.20 clears a $0.30 invoice exactly.
3. Retried invoice and payment requests create no duplicates.
4. A reused key with a different payload is rejected.
5. An overpayment leaves records unchanged.
6. Invalid and out-of-range amounts are rejected.
7. Customer and date validation rejects invalid postings.
8. Voiding retains the original and adds a reversal.
9. A paid invoice cannot be voided.
10. A database failure rolls back the document and ledger.
11. Concurrent payments cannot overpay an invoice.
12. API reads require authentication; writes also require CSRF tokens.

**H2: all 12 passed.** Java source and tests compiled with Maven. A network restriction prevented Maven's Surefire runner dependency from downloading, so the same compiled tests were executed with the JUnit Platform console runner instead. No mocked accounting service was used.

**GitHub Actions: all 12 passed on H2 and all 12 passed on PostgreSQL 17 using Maven Surefire.** The [first milestone run](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36828185923) also passed the frontend build and Chromium workflow. The PostgreSQL run uses a fresh database and the same Flyway migration and integration suite.

## Frontend

TypeScript checking and the Vite production build passed. The complete Playwright workflow passed in Chromium. It authenticated, posted a $1,200 invoice, recorded $700 payment, verified $500 remaining, inspected the two invoice journal lines, and checked equal trial-balance totals.

Actual browser captures:

![Overview after partial payment](screenshots/overview.png)

![Balanced trial balance](screenshots/trial-balance.png)

## Release status

This is the first milestone, published on GitHub with a passing automated workflow. There is no hosted application deployment. The complete product roadmap is in the README.
