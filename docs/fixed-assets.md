# Fixed assets and straight-line depreciation

This backend checkpoint capitalizes an existing purchase paid immediately. It retains the source expense, supplier, receipts and bank line; it does not create another payment or an unpaid bill. Use it for equipment that the business will use over a chosen useful life. Asset eligibility, useful life and residual value are supplied by the user.

## Example and ledger

A $100 equipment purchase on October 1 initially appears in Other operating expenses. Capitalization on that same open payment date debits Equipment at cost (1500) and credits the original expense category for $100. Begin service October 1, with three months of life and $10 residual value. The depreciable amount is $90, recognized as $30 each month.

Each month-end posting debits Depreciation expense (5600) and credits Accumulated depreciation (1590). Cost stays $100, accumulated depreciation finishes at a $90 credit balance and net equipment value is $10. Accumulated depreciation is a contra asset, shown as a negative asset balance in dated reports. Cash and vendor aging stay unchanged. Future schedule rows alone do not affect the books. With zero residual value, $100 over three months becomes $33.33, $33.33 and $33.34, assigning the rounding remainder to the final month.

## API and constraints

`POST /api/assets` accepts:

```json
{"expenseId":"existing-paid-purchase-id","inServiceOn":"2026-10-01","months":3,"name":"Office equipment","residualValue":"10"}
```

`POST /api/assets/{id}/depreciate` accepts `{"periodOn":"2026-10-31"}`. Both routes require authentication, CSRF and an `Idempotency-Key`. Exact retries return the original result; changed details under the same key are rejected.

Choose a posted, unreversed purchase with a consistent original cash/expense journal. It cannot simultaneously fund an uncorrected prepaid plan or another asset. The original purchase cannot be reversed while it funds an asset. Depreciation expense is excluded from ordinary purchase and accrued-expense categories; its postings use the asset workflow.

The service date must be the first of a month on or after payment. Useful life is one to six hundred months, with the final month by year 9999. Residual value must be nonnegative, less than cost and expressed to at most two decimal places. Every depreciation month must receive at least one cent. Recognize scheduled month ends in order and only on open dates. Later depreciation preserves earlier report cutoffs. Journal, schedule state, request result and activity commit together under the shared business lock.

## Verification and remaining work

Eight integration tests cover exact final rounding, unchanged bank lines, cost/contra asset and expense reports, residual value, earlier cutoffs, retries, closed dates, invalid lives/values, consistent funding sources, prepaid/asset exclusivity, rollback and security for both endpoints. The full CI suite runs on H2 and PostgreSQL 17, together with the existing browser workflows. Verification is pending for this checkpoint.

Asset correction/disposal, bank-match regression proof, screens and dedicated browser captures remain before milestone review. This version supports equipment at cost, whole calendar months and explicit straight-line postings. It does not provide tax depreciation, asset sales/refunds, impairment, life/residual revisions, unpaid asset bills or partial-month conventions.
