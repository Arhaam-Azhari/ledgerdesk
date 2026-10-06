# Fresh-install walkthrough

Use a fresh disposable installation and fictional records. An empty ledger starts with zero balances. Do not add these examples to live books.

1. Sign in over HTTPS. In **Business settings**, set the name to **Hosted recovery studio**.
2. Add **Maple Coffee Co.** as a customer and **Harbor Supply** as a vendor. Use fictional email addresses.
3. Post a **$1,200** design invoice dated **September 1, 2026**, due September 30. Record **$700** received on September 3. The customer still owes $500.
4. Post vendor bill **SUP-104** for **$600** of office supplies, dated **October 1**, due October 31. Record **$200** paid on October 2. The vendor bill still owes $400.
5. Record a separate **$50** direct software expense paid on **October 3**. It creates no unpaid bill.
6. Import these statement rows, review each matching entry and confirm the three matches:

| ID | Date | Description | Amount |
| --- | --- | --- | --- |
| HOSTED-1 | 2026-09-03 | Customer payment | 700.00 |
| HOSTED-2 | 2026-10-02 | Bill payment | -200.00 |
| HOSTED-3 | 2026-10-03 | Software | -50.00 |

The CSV header is `transaction_id,date,description,amount`. Save the rows under that header and use **Bank imports** to preview before importing.

7. Review **Reconciliation** from **September 1 to October 31**, opening **$0**, closing **$450**. Both differences should be zero with no unmatched rows. Close the statement.
8. Run reports for the same dates. Revenue should be **$1,200**, expenses **$650**, profit **$550**, receivables **$500** and payables **$400**. Trial-balance debits and credits must agree. Download an invoice PDF and check its business name, $700 received and $500 due.
9. Sign out and confirm private records need another sign-in. Restart the installation, sign in again and check the same records.
10. Follow [encrypted backup recovery](encrypted-backups.md): stop writers, back up, encrypt, decrypt and restore to a fresh database. Point the stopped application at the recovered database. Sign in and compare business details, transactions, bank matches, reconciliation and dated reports before reopening writes.

The hosted CI scenario performs the API accounting steps over HTTPS and compares the complete workspace and dated reports after restart and recovery. Separate Chromium checks cover browser access and setup. The expanded accounting scenario is prepared, with its first run pending. It is not a substitute for reviewing the actual live installation.
