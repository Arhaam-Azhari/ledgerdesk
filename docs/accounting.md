# Accounting rules

Northline Design Studio is a fictional USD service business. The demo starts with no opening balances or posted transactions. All reports currently include every posted transaction, including future-dated entries; period cutoffs are not implemented yet.

## Accounts

| Code | Account | Normal balance |
| --- | --- | --- |
| 1000 | Business bank | Debit |
| 1100 | Accounts receivable | Debit |
| 2000 | Accounts payable | Credit |
| 4000 | Service revenue | Credit |
| 5000 | Office supplies | Debit |
| 5100 | Software subscriptions | Debit |
| 5200 | Professional services | Debit |
| 5300 | Rent and utilities | Debit |
| 5400 | Business travel | Debit |
| 5500 | Other operating expenses | Debit |

## Known example

| Event | Debit | Credit |
| --- | --- | --- |
| Post a $1,200 service invoice | Receivables $1,200 | Revenue $1,200 |
| Record $700 payment | Bank $700 | Receivables $700 |

After both events, bank has a $700 debit balance, receivables has a $500 debit balance, and revenue has a $1,200 credit balance. Trial balance debit and credit totals are both $1,200. The payment changes cash and receivables, not revenue.

Voiding a different unpaid invoice debits revenue and credits receivables for its full amount. The original journal entry remains. Voiding a paid invoice is rejected until a credit/refund workflow is designed.

## Purchases

| Event | Debit | Credit |
| --- | --- | --- |
| Post a $600 office-supply bill | Office supplies $600 | Payables $600 |
| Pay $200 against the bill | Payables $200 | Bank $200 |
| Record a $50 software purchase paid immediately | Software subscriptions $50 | Bank $50 |

The bill recognizes the expense when posted. Its payment reduces the liability and bank; it does not recognize another expense. The direct expense is already paid, so it creates no payable. Enter each purchase through one workflow.

Following the invoice example above with these purchases gives bank $450 debit, receivables $500 debit, payables $400 credit, revenue $1,200 credit, and expenses $650 debit. Net trial balance totals are $1,600 on each side. Without the earlier customer payment, the recorded bank balance would be negative because the demo has no opening funds.

Voiding an unpaid bill debits payables and credits its expense category for the full amount. Correcting a mistaken direct expense debits bank and credits its expense category. The original documents and journal entries remain, with an offsetting entry dated on or after the original transaction. An expense correction is a bookkeeping reversal, not a real vendor refund. A bill with any recorded payment cannot be voided.

Vendor net billed and outstanding totals exclude voided bills. Direct expenses appear separately and contribute no amount owed. For the one-business model, total vendor outstanding balances should equal the ledger's payable balance. Bill references are unique per vendor after trimming and case normalization, including voided bills. Attachments create no journal entry.

Categories are for ordinary operating expenses. Purchases of capital assets, tax components, inventory, and vendor credit notes need separate future workflows.

## Posting rules

- Amounts must be positive, contain no more than two decimal places, and fit `NUMERIC(14,2)`.
- Each supported posting creates equal debit and credit lines.
- Posted document or payment, ledger lines, request-key record, and activity event commit together.
- A failed posting leaves none of those records behind.
- Customer and bill payments must not exceed the document's outstanding amount.
- Due dates and payment/reversal dates must not precede their document date.
- Posted invoices, bills, and direct expenses cannot have their amounts edited through the API.
- Repeating the same request key and payload returns the original record.
- Reusing the key with different details is rejected.

The schema checks positive line amounts and valid foreign keys. Balance across journal lines is enforced by the posting service, not by a database-wide journal constraint. Direct database writes are outside the supported workflow.

No sales tax, refunds, credit notes, inventory, payroll, foreign currency, tax filing, or formal revenue-recognition standard compliance is claimed in this milestone.

## Drafts and numbering

Saving, editing, and discarding drafts creates activity records but no journal entries, revenue, or receivables. Posting a saved draft creates one posted invoice and one balanced journal entry in the same transaction. A saved version number rejects stale edits or posting from another tab. Retrying the original command key returns its original result; trying to post the same draft with a new key is rejected.

Invoice numbers are assigned under the business-row lock and roll back with a failed posting. Voiding retains the assigned number. Earlier invoices receive stable numbers during the version 2 migration without changing amounts, payments, or ledger entries.

Customer net invoiced totals include posted invoice amounts and exclude voided invoices. Recorded payments and outstanding amounts come from the same invoice snapshot. Drafts contribute zero. For this single-business model, the sum of customer outstanding balances should equal ledger receivables.

A PDF is a current document copy, not a new posting or an immutable historical snapshot. Downloading it never changes the books.
