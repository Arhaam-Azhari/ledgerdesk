# Verification

Checks are recorded per milestone. Prepared checks are not counted as passing checks.

## Bank reconciliation milestone

The bank milestone passed **86 integration tests on H2**, **86 on PostgreSQL 17**, the frontend production build, and **nine Chromium workflows** in [run 36928181272](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36928181272), source `76558dc5be2f1cab430665ad31ae51aca1314780`. Backend totals had zero failures, errors or skipped tests. The browser results comprise the eight shared-demo workflows plus one isolated reconciliation workflow.

The 41 added backend tests cover CSV parsing and deduplication (13), reviewed one-to-one matching and undo (10), and reconciliation preview/close/reopen (18). They test exact amounts and cutoffs, period continuity, retained snapshots, stale commands, rollback, closed-period protections, security filters, and competing writes. The earlier 45 invoice/purchase tests also pass.

The three added browser workflows cover CSV import, matching and undo, and statement reconciliation. They verify unchanged journal balances, explicit selection/confirmation, failure recovery, saved history, protected bank matches, reopening and narrow-screen layout. The reconciliation workflow uses a separate in-memory database and ports 8081/5174.

The [import/matching notes](bank-imports.md) and [reconciliation notes](bank-reconciliation.md) link the exact runs and reviewed captures. Desktop and mobile images were downloaded from passing browser artifacts and visually checked. The reconciliation example shows a $500 statement balance, $80 outstanding payment, and $420 book balance. These are fictional test records, distinct from the README walkthrough.

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

This is the verified local invoicing, purchases and bank reconciliation milestone, published as source code. There is no hosted application deployment. Separate roles, backups, and deployment hardening remain on the roadmap. The later financial reporting checkpoint is recorded below. The tests cover the workflows described here; they are not a guarantee for every possible edge case or production security.

## Financial reporting checkpoint

[Run 36939023042](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36939023042), source `be64096a897e080169c5fe38f583cbfc371601ee`, passed 101 integration tests on each of H2 and PostgreSQL 17, the frontend production build, and ten Chromium workflows. The additional backend checks cover dated statements, historical aging and balance-sheet consistency. The isolated reporting browser workflow checks all five views and CSV downloads, historical payments, request failure/retry, stale-result clearing and mobile layout.

The desktop and mobile captures were downloaded from that run and reviewed. [Reporting notes](reports.md) include those captures and reproduction instructions. These reporting checks extend the bank checkpoint above; adjustments, roles, backups and deployment remain future work.

## Owner equity draft checkpoint

[Run 36942772413](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36942772413), source `7cdd41c80931aff69706b25921ea37187749402a`, passed 109 tests on each database, the production frontend build and eleven Chromium workflows. Eight backend tests cover owner posting, reports, bank matching/reconciliation, retries, rollback and access controls. The isolated owner browser workflow checks contributions, withdrawals, an interrupted refresh and exact-once retry, decimal input validation, dated equity with zero profit, retained records and mobile layout. Reviewed desktop/mobile examples and reproduction instructions are in [owner equity notes](owner-equity.md). At that checkpoint the branch remained a draft while corrections were being built.

## Completed owner correction checks

[Run 36943949702](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36943949702), source `76252166133c9bc92366f593fc5d7fecc813ffcd`, passed 117 integration tests on each of H2 and PostgreSQL 17 with no failures, errors or skipped tests, the production frontend build, and all eleven Chromium workflows. The sixteen owner integration tests now include corrections: retained originals and historical balances, both directions, duplicate/conflicting retries, invalid correction details, rollback, matching protections, closed original dates and net reconciliation. The browser test retries a reversal after an interrupted refresh without duplicating it, then checks the original, reversal reason and dated equity. All three owner captures were downloaded from that run and reviewed; [owner notes](owner-equity.md) contain the images and instructions.

## Expense adjustment browser checkpoint

[Run 36947801712](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36947801712), source `0a74169256865d3808f1413b9022c1f8e238fa80`, passed 133 integration tests on each of H2 and PostgreSQL 17, the production frontend build and all twelve Chromium workflows. The new isolated browser test verifies unbalanced and duplicate-category rejection, exact multi-line allocation, retries after unsuccessful refreshes for both posting and reversal, retained journals, earlier/later category reports, unchanged total profit, reloading and mobile layout. Four captures were downloaded from this run and visually reviewed. [Adjustment notes](adjustments.md) contain calculations, scope, reproduction instructions and the images. The adjustment milestone was subsequently merged in PR #5.


## Accrued expense screen checkpoint

[Run 36950384165](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36950384165), source `fa49a0586143e8c6b015e5b79638ab00b4b820d0`, passed 143 integration tests on each of H2 and PostgreSQL 17, the production frontend build and all thirteen Chromium workflows. The new workflow verifies input precision, exact posting/reversal retries after interrupted refreshes, closed-period rejection, historical reports, unchanged cash and vendor aging, retained history and mobile layout. Five downloaded captures were reviewed and added to [accrual notes](accruals.md). The accrual milestone was subsequently merged in PR #6.


## Accrual-to-bill backend checkpoint

[Run 36952050080](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36952050080), source `4dde31f8a61e0e052caa6154e64c8a26b92c9ef2`, passed 155 integration tests on each of H2 and PostgreSQL 17, the production frontend build and all thirteen existing Chromium workflows. Twelve new tests verify the atomic estimate reversal/bill/link, equal and different amounts, preserved closed history, retries, full rollback, normal payments and voiding, endpoint security and competing submissions. [Handoff notes](accrual-bill-handoff.md) explain the figures and API. This checkpoint verified the API before the handoff form was added.


## Accrual-to-bill screen checkpoint

[Run 36953376746](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36953376746), source `c62e9983f41b4db753409bde96e23a10bcefba26`, passed 155 integration tests on each of H2 and PostgreSQL 17, the production frontend build and all fourteen Chromium workflows. The new isolated handoff workflow covers supplier setup guidance, invalid amounts, closed dates, draft retention and interrupted-refresh retries, historical reports, the amount difference, linked bill navigation, partial payment, retained void status and mobile layout. Four reviewed captures and reproduction instructions are in [handoff notes](accrual-bill-handoff.md). Final milestone review and merge remain pending in PR #7.

## Prepaid expenses: final workflow review

Source `6ba4805688bc04a589d80638ebd70a6545dc88e5` passed [run 36976006689](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36976006689): 178 integration tests on each database, the production frontend build and all fifteen Chromium workflows. The prepaid workflow verifies exact allocations, invalid dates/year overflow, creation and cancellation retries after deliberately interrupted refreshes, one retained result, closed October reports unchanged by November cancellation, recognition, correction and phone-width layout. Five original captures were downloaded, visually reviewed and added to [prepaid notes](prepaid-expenses.md).

The retained plan supports whole calendar months, explicit scheduled postings, remaining-benefit cancellation and correction before recognition. Replacement schedules on the same purchase, supplier refunds and partial-month allocations remain outside this milestone. Final review covers those limits and the unchanged source cash/bank-matching behavior.

## Fixed assets

Source `541b2b4eed4f4dfe7311fa0f63474639ea263ac1` passed [run 36981453673](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36981453673): 196 integration tests on H2 and 196 on PostgreSQL 17, zero failures/errors/skips, the production frontend build and all sixteen Chromium workflows. Backend checks cover exact schedules and residuals, capitalization, ordered depreciation, correction, partial/full retirement, security, atomic rollback, unchanged bank matches and preserved closed reports.

The dedicated browser workflow checks first-of-month/year bounds, three $30 depreciation rows for a $100 purchase with $10 residual, desktop/mobile layout, registration and retirement retries after deliberately interrupted workspace refreshes, one retained result, $70 book value after October depreciation, November retirement without changing October reports, and correction to direct expense. Five unedited captures were downloaded, visually reviewed and added to [fixed-asset notes](fixed-assets.md). Wider deployment and role/isolation checks remain on the roadmap.

## Cash activity and export

Source `ff5a43d3298574ecdb021e5e43fe62445ea6de15` passed [run 37054373203](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37054373203): 204 integration tests on each of H2 and PostgreSQL 17, no failures/errors/skips, production frontend build and all seventeen Chromium workflows. Eight backend tests cover cash boundaries, negative balances, document payments versus accrual profit, owner reversals, noncash postings, mixed counterpart groups, business filtering and authenticated read-only access.

The cash browser workflow checks opening $100, payment $25, closing $75, CSV totals and escaped formula-like memo text, clearing stale results, an empty later period and mobile width. Two original captures were downloaded and visually reviewed; see [cash-activity notes](cash-activity.md).

## Read-only reviewer access

Source `353af6d17402a28942e9d54743dc1eff0448e64a` passed [run 37061714636](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37061714636): 209 integration tests on each database with no failures/errors/skips, production frontend build and all eighteen Chromium workflows. Five backend tests verify real reviewer credentials, state/report reads, valid-CSRF blocked writes across modules, PATCH/DELETE/future-route denial, owner CSRF and invalid/anonymous access.

The dedicated browser workflow verifies read-only navigation, report/CSV access, cash activity, phone width, a direct write returning 403, locking and returning to the owner workspace. Its first run caught the mobile-hidden sidebar lock; moving the control to the header resolved it, and the full suite passed afterward. Two original captures were downloaded and visually reviewed; see [reviewer access notes](reviewer-access.md). Persistent users, memberships and hosted sessions remain outside this checkpoint.


## Persistent accounts, administration and recovery

Source `9904cb9fe4b714323abcde02c4b0c5926e0dc472` passed [run 37075785948](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37075785948): **229 integration tests on H2 and 229 on PostgreSQL 17**, zero failures/errors/skips, the production frontend build and **20 Chromium workflows**. Twenty account-specific integration tests cover bootstrap persistence, membership filtering, hash-free administration, last-owner protection, actual password/role/enabled authentication, CSRF, atomic audit rollback and offline recovery.

The account browser workflow verifies creation, disabling/re-enabling, password reset, promotion/demotion, last-owner rejection, self-password change and Unicode sign-in at desktop and phone widths. The persistent workflow runs the packaged backend against a temporary file-backed H2 database, restarts with changed bootstrap credentials, verifies the original stored roles/passwords and supplier, then executes the real non-web recovery command and starts a third backend. It checks successful recovery, no password in command output, old-password rejection, restored owner access, retained supplier and recovery activity.

Five unedited captures from that run were downloaded and visually reviewed: three in [account management](account-management.md), two in [persistent accounts](persistent-accounts.md). [Recovery instructions](account-recovery.md) describe the command and operator prerequisites. This proves graceful H2 process restart and recovery; PostgreSQL process restart, crash recovery, backup restoration, hosted sessions and multi-business administration remain separate work.


## Offline H2 backup and separate-file restore

Source `01f1f6685bc5a387abdaf7f592fc76949b0848d6` passed [run 37077815392](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37077815392): six Python backup-tool tests, 229 backend tests on H2 and 229 on PostgreSQL 17 (zero failures/errors/skips), the production frontend build and all 20 Chromium workflows. The extended persistent workflow creates a supplier and $125.37 contribution, verifies restart and offline owner recovery, stops the backend, executes the backup and restore CLI, and starts the packaged backend against a separate restored H2 file.

The restored full workspace matches the pre-backup state, recovered owner and reviewer passwords/roles work, the old owner password fails, and retrying the original contribution key leaves one transaction. Unit checks reject corrupted copies, observed lock files, changed manifest paths and overwriting existing data. [Backup instructions](local-backups.md) explain operator shutdown, checksums, private storage and restore validation. The fixture does not include receipt uploads or prove PostgreSQL backup restoration, live backups or crash recovery.


## Native PostgreSQL backup and restore

Source `9185dc4538a539928cd464c3b68464fd8c520ec3` passed [run 37079403182](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37079403182): 14 Python backup tests, 229 integration tests on each of H2 and PostgreSQL 17, zero failures/errors/skips, production frontend build, all 20 Chromium workflows and the dedicated PostgreSQL restore job.

The packaged backend records a supplier and $125.37 owner contribution in a fresh PostgreSQL 17 database. After shutdown, the actual CLI runs native custom-archive backup, manifest validation, new-database creation and a single-transaction restore. A new backend process opens the restored database, compares the entire workspace and stored owner/reviewer access, rejects changed bootstrap credentials and reviewer writes, and retries the original contribution key without duplication. A second restore refuses the existing target and leaves the workspace unchanged.

[PostgreSQL backup instructions](postgres-backups.md) explain connection configuration, private storage, failed-target inspection and operator checks. This fixture does not upload receipts, restore server roles/grants, simulate power loss or establish point-in-time recovery.


## Receipt attachments after database restoration

Source `cdca1aaddda73407d32bb5a47a7d10e2e2a5ca9d` passed [run 37080727752](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37080727752): 14 backup-tool tests, 229 integration tests on each database, no failures/errors/skips, production frontend build, all 20 Chromium workflows and the PostgreSQL restore job. The real H2 and PostgreSQL process fixtures now retain a PNG bill receipt and JPEG expense receipt.

After opening separate restored databases, authenticated downloads match the pre-backup stored bytes and headers. Full workspace comparisons retain metadata and document links, both owner/reviewer downloads work, anonymous downloads return 401 and valid-CSRF reviewer uploads return 403. Retrying the original owner upload keys returns the retained IDs without changing receipts, accounting or activity. The [receipt restoration guide](receipt-restoration.md) explains normalization and the tested limits; PDF restoration remains outside this fixture.
