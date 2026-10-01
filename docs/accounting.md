# Accounting rules for the invoice milestone

Northline Design Studio is a fictional USD service business. The initial ledger has no opening balances or expenses. All reports currently include every posted transaction, including future-dated entries; period cutoffs are not implemented yet.

## Accounts

| Code | Account | Normal balance |
| --- | --- | --- |
| 1000 | Business bank | Debit |
| 1100 | Accounts receivable | Debit |
| 4000 | Service revenue | Credit |

## Known example

| Event | Debit | Credit |
| --- | --- | --- |
| Post a $1,200 service invoice | Receivables $1,200 | Revenue $1,200 |
| Record $700 payment | Bank $700 | Receivables $700 |

After both events, bank has a $700 debit balance, receivables has a $500 debit balance, and revenue has a $1,200 credit balance. Trial balance debit and credit totals are both $1,200. The payment changes cash and receivables, not revenue.

Voiding a different unpaid invoice debits revenue and credits receivables for its full amount. The original journal entry remains. Voiding a paid invoice is rejected until a credit/refund workflow is designed.

## Posting rules

- Amounts must be positive, contain no more than two decimal places, and fit `NUMERIC(14,2)`.
- Each supported posting creates equal debit and credit lines.
- Invoice, ledger, request-key record, and activity event commit together.
- A failed posting leaves none of those records behind.
- A payment must not exceed the invoice's outstanding amount.
- Due dates and payment/reversal dates must not precede the invoice date.
- An invoice cannot be edited through the API after posting.
- Repeating the same request key and payload returns the original record.
- Reusing the key with different details is rejected.

The schema checks positive line amounts and valid foreign keys. Balance across journal lines is enforced by the posting service, not by a database-wide journal constraint. Direct database writes are outside the supported workflow.

No sales tax, refunds, credit notes, inventory, payroll, foreign currency, tax filing, or formal revenue-recognition standard compliance is claimed in this milestone.

## Drafts and numbering

Saving, editing, and discarding drafts creates activity records but no journal entries, revenue, or receivables. Posting a saved draft creates one posted invoice and one balanced journal entry in the same transaction. A saved version number rejects stale edits or posting from another tab. Retrying the original command key returns its original result; trying to post the same draft with a new key is rejected.

Invoice numbers are assigned under the business-row lock and roll back with a failed posting. Voiding retains the assigned number. Earlier invoices receive stable numbers during the version 2 migration without changing amounts, payments, or ledger entries.

Customer net invoiced totals include posted invoice amounts and exclude voided invoices. Recorded payments and outstanding amounts come from the same invoice snapshot. Drafts contribute zero. For this single-business model, the sum of customer outstanding balances should equal ledger receivables.

A PDF is a current document copy, not a new posting or an immutable historical snapshot. Downloading it never changes the books.
