# Prepaid expense schedules

This backend checkpoint converts an unreversed purchase paid upfront into a prepaid asset, then recognizes its expense over whole calendar months. Start with a direct expense in the existing purchase workflow. Its bank posting, supplier and receipt evidence stay intact.

## Example

Pay $100 for October–December software on October 1, using Software subscriptions (5100). Create a three-month prepaid plan starting October 1. The conversion debits Prepaid expenses (1300) and credits Software subscriptions for $100 on the payment date. Cash remains $100 lower, but the unconsumed benefit is now an asset rather than an immediate expense.

The schedule recognizes $33.33 at October month-end, $33.33 at November month-end and $33.34 at December month-end. Each posting debits Software subscriptions and credits Prepaid expenses. The final rounding remainder ensures the schedule consumes exactly $100. Later postings preserve earlier report cutoffs. Vendor aging stays unchanged because the source purchase was paid immediately.

## API

`POST /api/prepaid` accepts:

```json
{"expenseId":"existing-paid-expense-id","startsOn":"2026-10-01","months":3,"memo":"Software October to December"}
```

`POST /api/prepaid/{id}/recognize` accepts:

```json
{"periodOn":"2026-10-31"}
```

Both routes require authentication, CSRF and an `Idempotency-Key`. Exact retries return the original ID; changed details under the same key are rejected. Workspace state includes `prepaidPlans` and `prepaidPeriods`. A period with an `entry_id` has been posted; future schedule rows alone do not affect the ledger.

Plans cover one to sixty whole months, starting on the first day of a month on or after payment. The final month must end by year 9999, and each monthly allocation must be at least one cent. The expense must be posted, unreversed and not already used for a plan. The payment date must remain open because conversion reclassifies that original date. Only a consistent cash/expense source journal may be converted.

Recognition uses the schedule's exact month-end date and amount. Post earlier months first; duplicate, skipped, unknown and closed periods are rejected. Conversion and recognition write their journals, schedule state, request key and activity atomically under the shared business lock. The original paid expense cannot be reversed while it funds a prepaid plan; cancellation writes off the remaining benefit without reversing the original payment. Supplier refunds and correction of an erroneous payment remain separate future work.

## Checks and current scope

Eight integration tests cover exact final rounding, unchanged original cash, asset/expense reports, historical cutoffs, source/month retries, closed dates, invalid schedules, inconsistent or reversed funding sources, transaction rollback and security for both endpoints. CI runs the full suite on H2 and PostgreSQL 17. Run `mvn test` from `backend`; database reproduction settings are in the README. Source checkpoint `f7dec9e72b31f5cbb1d58dad149a1ebb7fa48e99` passed [GitHub Actions run 36954853951](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36954853951): 163 integration tests on each database, zero failures, errors or skipped tests, a production frontend build and all 14 existing browser workflows. The browser checks cover regression behavior; prepaid-specific screens and browser proof are not included yet.

The plan, recognition, cancellation and correction screens and browser screenshots remain to be added before final milestone review. Cancellation and bank-matching regression checks are included in the backend checkpoint below. This version reclassifies an existing direct expense; it does not create another payment, schedule automatic jobs, support partial-month prorating or defer unpaid supplier bills. Postings can be future dated, like the other demo workflows; dated reports select what belongs at their cutoff.

## Ending a benefit early

`POST /api/prepaid/{id}/cancel` accepts `{"cancelledOn":"2026-11-01","reason":"Subscription ended early"}` with the same authentication, CSRF and request-key requirements. After the October recognition above, cancellation debits Software subscriptions and credits Prepaid expenses for the remaining $66.67. October reports retain their $33.33 expense; November bears the write-off. Cash, receipts, supplier aging and bank matches stay unchanged.

Choose an open date on or after funding and all posted recognitions. Unposted schedule rows remain as history, but cancellation blocks their posting. A fully recognized plan has no balance to write off. This is appropriate when a paid benefit is no longer available and no refund is due. It does not reverse an accidental plan, refund a payment or amend a closed period. An untouched accidental plan can be corrected as described below; refund and later adjustment workflows remain outside this feature.

Eight additional tests cover full and partial write-offs, retained closed reports, validation, retries, rollback, endpoint security, preserved existing bank matches and matching an already converted expense. Source checkpoint `6faa503d1add2509bc9f12058af43188441a6808` passed [Actions run 36955500049](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36955500049): 171 integration tests on each of H2 and PostgreSQL 17 with zero failures, errors or skipped tests, the frontend production build and all 14 existing browser workflows.

## Correcting an accidental plan

`POST /api/prepaid/{id}/correct` accepts `{"reason":"This purchase should be expensed immediately"}` with authentication, CSRF and an idempotency key. It offsets the original prepaid setup on its original funding date, restoring the direct expense without changing its cash payment, receipt or bank match. That date must be open. The setup journal must agree with the plan amount and category.

Correction is allowed only before any recognition or cancellation. The plan, schedule and reason remain visible, and new recognition or cancellation requests are blocked. Exact retries still return their original result. After correction, the original purchase can use its normal reversal workflow, including the requirement to unmatch a bank transaction first.

This version keeps one retained plan per purchase, including corrected plans; it does not create replacement schedules on the same purchase. It does not undo already recognized plans or rewrite closed reports. Use it to restore a purchase that should have remained a direct expense.

Seven additional integration tests cover restored reports, retained history and retries, recognition/cancellation blocking, closed dates, invalid or inconsistent sources, atomic rollback, endpoint security and preserved bank matches with purchase-reversal guards. Verification is pending for this checkpoint.
