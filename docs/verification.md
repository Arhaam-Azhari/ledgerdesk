# Verification

Checks are recorded per milestone. Prepared checks are not counted as passing checks.

## Invoice milestone

The backend suite contains **24 integration tests**, using real Spring services, JDBC transactions, Flyway migrations, PDFBox, and the security filter chain. There is no mocked accounting service.

The original accounting checks cover exact decimal arithmetic, invoice/payment retries, conflicting request keys, invalid amounts and dates, overpayment rejection, invoice reversals, transaction rollback, concurrent payments, and authentication/CSRF protection.

The invoice milestone adds checks for:

- Saving and editing drafts without journal entries or invoice numbers.
- Rejecting stale edits and posting from an older draft version.
- Posting a draft once, including competing requests and retries.
- Retaining discarded drafts and preventing later posting.
- Stable numbers, serialized assignment, and rollback after a failed posting.
- Customer totals excluding drafts and voids, with an empty customer balance.
- Authenticated PDFs with the invoice number, current payments, and amount due.
- Voided PDFs showing zero due; wrapping, multiple pages, and explicit unsupported-glyph codes.
- Upgrading an existing version 1 database without changing its accounting entries.

**Local H2: all 24 passed.** Maven compiled the source and tests; the JUnit Platform console runner executed the compiled tests because this workspace cannot download Maven Surefire runner dependencies through its Java network configuration. GitHub Actions uses the regular Maven runner.

**Invoice milestone PostgreSQL: awaiting its GitHub Actions run.** The workflow runs the same suite against a fresh PostgreSQL 17 service, including the version 1 upgrade check.

## Browser and build

TypeScript checking and the Vite production build passed. **All four Playwright workflows passed locally in Chromium:**

1. Post a $1,200 invoice, record $700 payment, inspect balanced entries, and check the trial balance.
2. Save a $1,250 draft without changing the ledger, edit it to $1,500, post it, record $500 payment, download its PDF, and inspect the customer's $1,000 outstanding balance.
3. Discard a draft, reload, and use the workspace at a 390-pixel width. Check that wide customer tables can scroll and the page itself does not overflow.
4. Interrupt the refresh after a successful invoice posting, retry, and verify only one invoice exists.

The tests use an initially empty demo database and fictional customers. Captures are taken at different workflow steps: the overview/trial-balance images show the first invoice; the customer and mobile captures also include the second invoice. The later retry test creates another invoice after those screenshots.

Actual captures and downloaded output:

![Overview after partial payment](screenshots/overview.png)

![Saved draft before editing](screenshots/drafts.png)

![Customer balances after payment](screenshots/customer-balances.png)

![Balanced trial balance](screenshots/trial-balance.png)

[Narrow-screen overview](screenshots/mobile-overview.png) and [downloaded invoice PDF](invoice-example.pdf).

The PDF was rendered and visually checked for readable text, page margins, wrapping, and correct amounts. Automated tests also reopen generated PDFs and inspect their contents, including a multi-page payment history.

## Earlier milestone

The [first milestone GitHub run](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36828185923) passed the original 12 backend tests on both H2 and PostgreSQL, the frontend build, and its single browser workflow. That result applies to the earlier commit.

## Release scope

This is a local invoice milestone, published as source code. There is no hosted application deployment. Expenses, bank reconciliation, period reports, separate roles, backups, and deployment hardening remain on the README roadmap. The tests cover the workflows described here; they are not a guarantee for every possible edge case or production security.
