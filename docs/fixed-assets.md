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

Eight integration tests cover exact final rounding, unchanged bank lines, cost/contra asset and expense reports, residual value, earlier cutoffs, retries, closed dates, invalid lives/values, consistent funding sources, prepaid/asset exclusivity, rollback and security for both endpoints. The full CI suite runs on H2 and PostgreSQL 17, together with the existing browser workflows. Source checkpoint `85f2f3d1650e4b6a8c106337cb92d08feac7d7c0` passed [Actions run 36978114358](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36978114358): 186 integration tests on each of H2 and PostgreSQL 17 with zero failures, errors or skipped tests, the production frontend build and all 15 existing Chromium workflows. These browser workflows check regression behavior; asset-specific screens and browser proof are still to be added.

Asset screens and dedicated browser captures remain before milestone review. Correction, zero-proceeds retirement and bank-match regression checks are included in the additional backend checkpoint below. This version supports equipment at cost, whole calendar months and explicit straight-line postings. It does not provide tax depreciation, asset sales/refunds, impairment, life/residual revisions, unpaid asset bills or partial-month conventions.

## Correction and retirement

`POST /api/assets/{id}/correct` accepts `{"reason":"This should remain an immediate expense"}`. It is allowed only before depreciation or retirement, offsets the original capitalization on its original open date, and retains the asset and schedule as history. The original purchase can then use its normal reversal workflow, including unmatching a bank transaction first. One retained asset per purchase is still enforced; replacement schedules on the same purchase are not supported.

`POST /api/assets/{id}/retire` accepts `{"retiredOn":"2026-11-01","reason":"Equipment no longer usable"}`. This is a zero-proceeds retirement, with no sale, refund or bank posting. The date must be open, on or after payment and all posted depreciation dates. Post scheduled month ends strictly before the retirement date first. The retirement date's own month-end depreciation can either be posted first or included in the remaining retirement loss; there is no partial-month depreciation.

For the $100 equipment above, one $30 October depreciation leaves $70 book value. November retirement credits Equipment at cost $100, debits Accumulated depreciation $30 and debits Loss on equipment retirement (5700) $70. Cost and accumulated depreciation disappear from current balances; October's closed reports remain unchanged. Fully depreciated zero-residual equipment retires with no loss. Fully depreciated equipment with residual value writes off that residual value when retired with no proceeds.

Both routes require authentication, CSRF and idempotency keys. Exact retries return the original result. Corrected and retired assets reject new depreciation, and both ending kinds remain visible with dates and reasons. Retirement validates the setup, schedule and posted depreciation journals before writing a balanced two- or three-line removal. It retains all original records and cash/bank evidence. Loss and depreciation categories are excluded from ordinary purchase/accrual forms.

Ten additional integration tests cover original expense restoration, preserved closed reports, full/partial/zero-loss retirement, date and state guards, inconsistent journals, rollback, security, unchanged bank matches and purchase-reversal protections. Source `16c13b181ca38a4a61494ca67435af72178840da` passed [Actions run 36979136611](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36979136611): 196 integration tests on each of H2 and PostgreSQL 17, zero failures/errors/skips, production frontend build and all 15 existing Chromium workflows. Asset-specific screens and browser captures remain the next checkpoint.
