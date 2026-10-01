# Verification

Checks are recorded per milestone. Prepared checks are not counted as passing checks.

## Purchases milestone

The purchase source checkpoint passed **45 backend integration tests on H2**, **45 on PostgreSQL 17**, and **six Chromium workflows** on [GitHub Actions](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36914631754). The source commit was `3880cc732a7bbaaaca0bda7694ce36e4dae0514d`. All backend tests had zero failures, errors, and skipped tests. The browser suite completed in 15.0 seconds.

The 21 purchase integration tests exercise bill posting, partial and full payments, exact decimals, duplicate bill references, request retries, conflicting request keys, competing payments, invalid dates/categories/amounts, unpaid bill voids, direct expense corrections, rollback, vendor totals, and long descriptions. Receipt checks cover authenticated downloads, CSRF, duplicate content, storage rollback, five-file limits, forged types, oversized files/images, and rejection of encrypted or interactive PDFs. These run against real JDBC transactions and the security filter chain.

The two added browser workflows cover:

1. Add two vendors; post a $600 bill; pay $200; attach and download a PNG receipt; record a separate $50 software expense with a JPEG receipt; inspect the $400 vendor balance and a balanced trial balance.
2. Reject a duplicate bill reference and a forged PDF; void an unpaid mistaken bill; reverse a mistaken direct expense; use the workspace at a 390-pixel width without page overflow.

The four invoice browser workflows below also passed alongside the purchases. The refreshed captures use the same test database: purchases run first, then invoices. The overview and trial balance show $700 received from a customer, $250 spent, $500 receivable, $400 payable, $1,200 revenue, and $650 expenses. Later customer and mobile captures also include the second invoice and its payment. All data is fictional.

Screenshots were inspected for readable text, layout, and the recorded amounts. A secondary-button hover contrast issue found during review was corrected before the follow-up run. The saved captures come from that successful run.

[Vendor balances](screenshots/vendor-balances.png), [bills and receipt attachments](screenshots/bills.png), and [direct expenses](screenshots/expenses.png) show the completed purchase workflow.

## Earlier invoice milestone

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

**GitHub Actions: all 24 passed on H2 and all 24 passed on PostgreSQL 17 using Maven Surefire.** The [invoice milestone run](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36831707974) also passed the frontend build and four Chromium workflows. The PostgreSQL suite includes the version 1 database upgrade check.

## Browser and build

TypeScript checking and the Vite production build passed. **All four Playwright workflows passed locally in Chromium:**

1. Post a $1,200 invoice, record $700 payment, inspect balanced entries, and check the trial balance.
2. Save a $1,250 draft without changing the ledger, edit it to $1,500, post it, record $500 payment, download its PDF, and inspect the customer's $1,000 outstanding balance.
3. Discard a draft, reload, and use the workspace at a 390-pixel width. Check that wide customer tables can scroll and the page itself does not overflow.
4. Interrupt the refresh after a successful invoice posting, retry, and verify only one invoice exists. Check that locking is disabled during posting and returns to the login screen after requests finish.

The earlier invoice checks used an initially empty demo database and fictional customers. Current captures are refreshed from the purchases milestone run described above; the later invoice retry workflow adds another invoice after those screenshots.

Invoice workflow captures and downloaded output:

![Overview after partial payment](screenshots/overview.png)

![Saved draft before editing](screenshots/drafts.png)

![Customer balances after payment](screenshots/customer-balances.png)

![Balanced trial balance](screenshots/trial-balance.png)

[Narrow-screen overview](screenshots/mobile-overview.png) and [downloaded invoice PDF](invoice-example.pdf).

The PDF was rendered and visually checked for readable text, page margins, wrapping, and correct amounts. Automated tests also reopen generated PDFs and inspect their contents, including a multi-page payment history.

## Earlier milestone

The [first milestone GitHub run](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36828185923) passed the original 12 backend tests on both H2 and PostgreSQL, the frontend build, and its single browser workflow. That result applies to the earlier commit.

## Release scope

The purchases milestone is currently on the development branch. The default branch remains the verified invoice milestone until the documentation and screenshot follow-up are complete. There is no hosted application deployment. Bank reconciliation, period reports, separate roles, backups, and deployment hardening remain on the roadmap. The tests cover the workflows described here; they are not a guarantee for every possible edge case or production security.
