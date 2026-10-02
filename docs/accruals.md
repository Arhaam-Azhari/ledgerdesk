# Accrued expenses

This backend checkpoint records an operating expense that has been incurred but has no supplier bill yet. A month-end estimate for professional fees debits Professional services (5200) and credits Accrued expenses (2100). Cash and vendor aging stay unchanged. The liability appears separately on the balance sheet.

## Example

On October 31, record $125.37 for work already received. October expenses increase by $125.37, accumulated earnings fall by the same amount, and accrued liabilities increase by $125.37. The trial balance and balance sheet still balance.

A November 1 reversal debits accrued expenses and credits the original expense category. October reports retain the estimate; November shows its offset. The reversal can be in an open period even when October is closed. Entering the eventual supplier bill remains a separate step. Until a linked bill handoff is added, review the estimate and reversal together to avoid counting the expense twice. A reversal does not mark the supplier as paid or automatically create a bill.

## API

`POST /api/accruals` accepts:

```json
{"postedOn":"2026-10-31","memo":"October professional fees; supplier bill pending","accountCode":"5200","amount":"125.37"}
```

`POST /api/accruals/{id}/reverse` accepts:

```json
{"reversedOn":"2026-11-01","reason":"Reverse estimate before entering supplier bill"}
```

Both routes require authentication, a CSRF token and an `Idempotency-Key`. Each response returns an ID. Keep the request key when retrying an uncertain response; an exact retry returns the original ID. Reusing a key for different details is rejected. Workspace state exposes `accruals`, with original details and reversal metadata. The journal retains both dated entries.

Dates must have a year between 1 and 9999; memo/reason must be nonblank and at most 240 characters. Amounts must be positive, with at most two decimal places and twelve integer digits. Only operating expense categories are accepted. New postings and reversal dates cannot enter closed periods. A reversal cannot precede its original accrual or be repeated with a new key. An inconsistent original journal requires review. Header, journal, request key and activity are written in one transaction.

## Current scope and checks

This is an API milestone, with ten new integration checks for exact reports, cutoff history, closed periods, retries, input validation, transaction rollback, inconsistent journals and endpoint security. CI runs the full suite against H2 and PostgreSQL 17. Run `mvn test` from `backend`; PostgreSQL reproduction settings are in the main README. Source checkpoint `c261a2f851368f30bfde3f21c44931b19ffc7e1f` passed all 143 integration tests on each database, the production frontend build and all twelve existing Chromium workflows in [CI run 36949307897](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36949307897). The browser checks cover existing workflows; accrual browser coverage will accompany its entry screen.

The entry screen, browser proof, linked bill handoff, partial settlement and scheduled reversals are not included yet. Prepaid expenses and depreciation remain separate future workflows.
