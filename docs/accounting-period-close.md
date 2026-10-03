# Accounting period close

An accounting close records the owner's review of a reporting period and protects its posted books. It is separate from bank reconciliation: the bank close checks statement evidence; the accounting close retains financial reports, cash activity and an explicit review note. It does not create a journal or move profit into another equity account.

## Rules

Choose a supported month-end. The first range starts after an opening bank cutover, or at the earliest posted journal date (the first day of the chosen month for empty books). Later ranges start the day after the latest closed accounting period. A range may span several months; skipped calendar months remain included in its reports.

A bank statement ending at that month-end must already be closed. Due prepaid and depreciation schedule months must be posted first, unless their plan/asset was corrected, cancelled or retired by the cutoff. Complete this schedule work before closing the bank statement; if the bank period is already protected, reopen it to post the missing work. A future cancellation or retirement does not excuse an earlier missed month.

The trial balance, balance sheet and cash bridge must balance. Unpaid invoices, unpaid bills and outstanding book deposits/payments can legitimately remain; a successful check does not prove that every business document or estimate was supplied. The owner's review note records that human review. This checkpoint permits fictional future month-ends like the existing demo accounting workflows.

The close recalculates under the business posting lock, saves the entire review as JSON with dates, owner and timestamp, and records the request key and activity atomically. All posting/import/matching operations that use the central open-date guard respect accounting closes. A supporting bank statement cannot be reopened while its dates overlap a closed accounting period.

Only the latest closed accounting period can be reopened, with its current version and a reason. The original snapshot is retained, the version increases, and reopening records its actor/time/reason. Bank reconciliation protection still applies until that statement is also reopened. A later reclose creates a new retained record. Exact retries return their original result even after reopening; an old close key does not silently reclose a reopened period.

## API

Authenticated owners and reviewers can read `GET /api/accounting-periods` and `GET /api/accounting-periods/preview?endsOn=2026-10-31`. Workspace state includes `accountingPeriodCloses`. Owners post with CSRF and an idempotency key:

```json
{"endsOn":"2026-10-31","reviewNote":"Reviewed statements, supporting documents and adjustments"}
```

Send this to `POST /api/accounting-periods`. Reopen with `POST /api/accounting-periods/{id}/reopen` and `{"version":1,"reason":"Review missed supporting document"}`. There is no deletion of the review history.

Fourteen new integration tests cover snapshots without extra journals, prerequisites, retries, continuity/latest-only reopening, date and bank protections, retained reclose history, invalid input, unbalanced books, audit rollback, concurrency, schedule work and endpoint permissions. Backend source `800971503d1b8eac476c2815dc28053578b24b3f` passed [run 37151008115](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37151008115): 253 integration tests on each of H2 and PostgreSQL 17, no failures/errors/skips, 14 backup-tool tests, production build, all 21 existing Chromium workflows and the PostgreSQL restore check. The subsequent UI checkpoint adds the review screen and a dedicated browser workflow; execution of that new workflow is pending. Screenshot review and period-close restore fixtures remain. This is not a full fiscal-year closing, tax filing or accountant certification workflow.


## Review screen

Open **Period close**. Choose a month-end and **Preview accounting period**. Resolve a missing closed bank statement and any due prepaid/depreciation work, then inspect profit, balance sheet, trial balance, cash and unpaid balances. Owners enter a review note and confirm the close. A cancelled confirmation changes nothing. If posting succeeds but refresh fails, keep the same details and retry.

Saved periods retain their dates, note, closing actor and report totals under **View retained period reports**. Only the latest active close offers **Reopen latest accounting period** to an owner. Supply a reason and confirm; the original reports stay in history. Its supporting bank statement remains protected until that statement is reopened too. Reclosing keeps both the reopened record and new review. Reviewers can preview periods and inspect history without close/reopen controls.

Package the backend and run `npm run test:periods` from `frontend`. The isolated browser workflow covers missing statement evidence, invalid month-end, confirmation cancellation, uncertain-refresh close/reopen retries, original snapshot retention, reclose, mobile layout and reviewer access. CI captures the review, saved close, mobile history and reviewer screen.
