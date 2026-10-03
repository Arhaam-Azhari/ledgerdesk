# Accounting period close: backend checkpoint

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

Fourteen new integration tests cover snapshots without extra journals, prerequisites, retries, continuity/latest-only reopening, date and bank protections, retained reclose history, invalid input, unbalanced books, audit rollback, concurrency, schedule work and endpoint permissions. Execution is pending CI for this checkpoint. The owner review screen, dedicated browser workflow, screenshots and period-close restore fixtures remain to be added. This is not a full fiscal-year closing, tax filing or accountant certification workflow.
