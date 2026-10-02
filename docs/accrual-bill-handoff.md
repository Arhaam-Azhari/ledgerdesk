# Replacing an accrual with its supplier bill

This backend checkpoint receives an actual supplier bill against one unreversed accrued-expense estimate. It reverses the entire estimate and posts the actual bill on the same date, using the original expense category. Both records and their link are retained. It does not record a payment.

## Accounting example

An October 31 estimate of $125.37 debits Professional services (5200) and credits Accrued expenses (2100). On November 1, the supplier sends a $140 bill. The handoff posts:

| Posting | Debit | Credit |
| --- | --- | --- |
| Reverse estimate | Accrued expenses $125.37 | Professional services $125.37 |
| Actual bill | Professional services $140.00 | Accounts payable $140.00 |

October keeps its $125.37 expense and liability. November records $14.63 of additional expense; cumulative expense becomes $140. The accrued liability is cleared and vendor aging shows the actual $140 bill. Cash stays unchanged. If the actual bill is $100, November instead records a $25.37 expense reduction. Equal amounts produce no additional expense in November.

## API

`POST /api/accruals/{id}/bill` accepts:

```json
{"vendorId":"existing-vendor-id","reference":"FEES-100","description":"Actual October professional fees","issuedOn":"2026-11-01","dueOn":"2026-12-01","amount":"140.00"}
```

Use authentication, a CSRF token and an `Idempotency-Key`. The returned `id` is the newly posted bill. Workspace `accruals` includes `bill_id`, `bill_reference`, `bill_amount` and `bill_status`, alongside the original estimate and reversal metadata. A linked bill uses the normal Bills payment, receipt and voiding workflows.

The bill date must be open and on or after the estimate date. The original period may remain closed. The due date must be on or after the bill date. Vendor, reference, description and positive amount follow the existing bill rules, including duplicate references for that vendor. The original expense category is used automatically. An accrual already reversed manually cannot be received through this endpoint; enter its bill separately after reviewing its accounting history. Unknown accruals and inconsistent original journals are rejected.

## Why one command

The reversal, bill, link, request record and activity event commit together. If any validation, database write or activity write fails, all of them roll back. The operation shares the business lock with normal posting and reversals, so competing requests cannot both consume one estimate. Unique database links also enforce one bill per accrual. An exact retry returns the existing bill, including after its period closes; changing details under the same key is rejected.

Ordinary bill posting and the handoff share the same bill creation routine. That keeps category, vendor, reference and journal rules consistent without creating nested request keys. The handoff has one command and one combined activity event, `ACCRUAL_BILL_POSTED`.

Voiding an unpaid linked bill retains its link and the estimate reversal. It does not automatically restore the estimate. A paid bill cannot be voided under the existing purchase rules. Review the remaining obligation before recording a replacement; historical reports retain every dated entry.

## Checks and scope

Twelve new integration tests cover equal/larger/smaller bills, exact retries, closed and historical dates, rollback after invalid bill details and duplicate references, manually reversed or inconsistent originals, activity failure, normal payments and voiding, endpoint security and two competing submissions. Run `mvn test` from `backend`; CI runs the full suite on H2 and PostgreSQL 17. Results for this checkpoint are pending.

This checkpoint is the API and accounting logic. The handoff form, linked history display, browser tests and new screenshots follow in the next sprint. It replaces one entire estimate with one actual bill. Partial or multiple bills, attaching an already posted bill, and automatic scheduled reversals are future work.
