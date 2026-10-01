# Statement reconciliation preview

The backend can now compare an imported statement with the bank ledger as of a chosen end date. This is a read-only calculation: it does not create journal entries, save a reconciliation, or lock a period. The browser screen and final close/reopen workflow are still to be built.

## What the numbers mean

Enter the statement's inclusive start and end dates, opening balance, and closing balance. Balances may be zero or negative and must use plain decimal notation with at most two decimal places. The current scope is the single business's USD bank account, `1000`.

| Result | Calculation |
| --- | --- |
| Imported movement | Sum of statement rows dated within the period |
| Statement difference | Closing balance minus opening balance minus imported movement |
| Book balance | Sum of all bank journal debits minus credits through the end date |
| Outstanding deposits | Positive book entries through the end date that have not cleared by then |
| Outstanding payments | Absolute value of negative book entries through the end date that have not cleared by then |
| Adjusted bank balance | Closing balance plus outstanding deposits minus outstanding payments |
| Book difference | Adjusted bank balance minus book balance |

For example, a statement closing at $500 with a $125 deposit and an $80 payment still outstanding should explain a book balance of $545. A zero statement difference checks that the imported movement agrees with the entered balances. A zero book difference checks the second calculation. Neither alone proves that the statement is complete or that every association is correct; the user must review the actual bank statement and supporting records.

A book payment matched to a bank row dated next month remains outstanding for this month. Conversely, a bank row matched to a book entry dated after this statement ends is returned in `futureDatedMatches` for correction. Unmatched bank rows through the end date are returned even when they precede the selected start, so moving the start forward cannot hide unfinished matching.

The book calculation includes every bank journal line, including corrections and reversals. Offsetting entries may therefore appear on both sides of the outstanding list and require review. The current matcher supports posted customer payments, bill payments, and direct expenses; it does not support matching reversal lines. The preview makes that limitation visible rather than omitting those lines from the balance.

## API

`POST /api/bank/reconciliations/preview` requires authentication and CSRF. It does not require an idempotency key because it saves nothing.

```json
{
  "startsOn": "2026-10-01",
  "endsOn": "2026-10-31",
  "openingBalance": "0.00",
  "closingBalance": "500.00"
}
```

As elsewhere in the API, amounts are returned as decimal strings. The response includes the amounts in the table plus `unmatchedTransactions`, `outstandingEntries`, and `futureDatedMatches`. Reads use one repeatable-read transaction so concurrent imports or matching cannot mix different workspace versions within a response.

Opening and closing balances are supplied by the user. Opening-balance continuity with a previously closed statement is not yet enforced, and a zero difference is not a closed-period status. Saved reconciliation records, close/reopen controls, and protection against changes to closed periods are the next backend step.

## Checks

Eight integration tests cover cleared and outstanding items, missing statement movement, unrecorded charges, transactions clearing next month, future-dated book matches, period boundaries, older unmatched rows, reversals, input validation, and endpoint authentication/CSRF. They also check that preview leaves journal lines and request records unchanged. All 76 backend integration tests passed on H2 and all 76 passed on PostgreSQL 17 with zero failures, errors, or skipped tests in [run 36923012651](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/36923012651), source commit `a79e45e0986654198b60d7d5131a9708b84e41e2`. This includes the eight new reconciliation tests and the 68 existing tests.
