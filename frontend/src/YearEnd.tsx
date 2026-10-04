import { useEffect, useRef, useState } from "react";
import { cents, dollars, today } from "./money";
import type { ReportData } from "./Reports";

export type YearEndData = {
  year: number; startsOn: string; endsOn: string; currency: string;
  profitLoss: ReportData["profitLoss"];
  temporaryAccounts: { code: string; name: string; kind: string; openingBalance: string; periodBalance: string; closingBalance: string }[];
  proposedLines: { code: string; name: string; debit: string; credit: string }[];
  retainedEarningsChange: string; proposedDebits: string; proposedCredits: string;
  accountingPeriodId: string | null; bankReconciliationId: string | null;
  pendingPrepaidMonths: number; pendingDepreciationMonths: number; blockers: string[]; ready: boolean;
};
export type YearEndLoader = (year: string) => Promise<YearEndData | null>;
function balance(value: string) {
  const amount = cents(value);
  return amount === 0n ? "$0.00" : `${dollars(amount < 0n ? -amount : amount)} ${amount < 0n ? "Cr" : "Dr"}`;
}

export function YearEnd({ busy, workspace, load }: { busy: boolean; workspace: object; load: YearEndLoader }) {
  const [year, setYear] = useState(today().slice(0, 4));
  const [result, setResult] = useState<YearEndData | null>(null);
  const version = useRef(0);
  useEffect(() => {
    version.current++; setResult(null);
    return () => { version.current++; };
  }, [workspace]);
  return <>
    <p className="intro">Review a calendar year's earnings and the entries proposed to clear its revenue and expense accounts. Running a preview does not post entries or close the year.</p>
    <section className="card">
      <h2>Year-end review</h2>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const requested = ++version.current; setResult(null);
        const response = await load(year);
        // A workspace reload or new year makes the previous request obsolete.
        if (requested === version.current) setResult(response);
      }}>
        <fieldset className="owner-fields" disabled={busy}>
          <label>Calendar year<input type="number" min="1" max="9999" step="1" required value={year}
            onChange={(event) => { version.current++; setYear(event.target.value); setResult(null); }} /></label>
          <button disabled={busy}>Run year-end preview</button>
        </fieldset>
      </form>
      <p>The review covers January 1 through December 31 in USD. Closing entry posting is not available yet.</p>
    </section>
    {!result && <p className="empty">Choose a year and run the preview to inspect its earnings, proposed entries and prerequisites.</p>}
    {result && <section className="card year-end-results">
      <h2>Year-end preview: {result.year}</h2>
      <p><strong>{result.startsOn} to {result.endsOn} · {result.currency}</strong></p>
      <h3>Review prerequisites</h3>
      <p className="year-end-readiness"><strong>{result.ready ? "Preview prerequisites met" : "Review needed before closing"}</strong></p>
      <p>This result is a proposal, not a posted closing entry. Passing these checks does not close the year.</p>
      {result.blockers.length > 0 && <ul className="year-end-blockers">{result.blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}</ul>}
      <div className="table-wrap"><table aria-label="Year-end prerequisites">
        <thead><tr><th>Prerequisite</th><th>Review status</th></tr></thead>
        <tbody>
          <tr><td>Accounting period close at year-end</td><td>{result.accountingPeriodId ? "Active close retained" : "Missing active close"}</td></tr>
          <tr><td>Bank reconciliation at year-end</td><td>{result.bankReconciliationId ? "Active reconciliation retained" : "Missing active reconciliation"}</td></tr>
          <tr><td>Unposted prepaid months due</td><td>{result.pendingPrepaidMonths}</td></tr>
          <tr><td>Unposted depreciation months due</td><td>{result.pendingDepreciationMonths}</td></tr>
        </tbody>
      </table></div>
      {(result.accountingPeriodId || result.bankReconciliationId) && <details className="year-end-references"><summary>Year-end review references</summary><dl>
        {result.accountingPeriodId && <><dt>Accounting close ID</dt><dd>{result.accountingPeriodId}</dd></>}
        {result.bankReconciliationId && <><dt>Bank reconciliation ID</dt><dd>{result.bankReconciliationId}</dd></>}
      </dl></details>}
      <h3>Annual earnings</h3>
      <div className="table-wrap"><table aria-label="Annual earnings"><thead><tr><th>Calculation</th><th>USD</th></tr></thead><tbody>
        <tr><td>Revenue</td><td>{dollars(cents(result.profitLoss.revenue))}</td></tr>
        <tr><td>Expenses</td><td>{dollars(cents(result.profitLoss.expenses))}</td></tr>
        <tr><td>Net profit / loss</td><td>{dollars(cents(result.profitLoss.netProfit))}</td></tr>
        <tr><td>Retained earnings change</td><td>{dollars(cents(result.retainedEarningsChange))}</td></tr>
      </tbody></table></div>
      <p>Profit increases retained earnings; a negative change is a loss and reduces it. Unpaid invoices and bills still count in accrual earnings.</p>
      <h3>Revenue and expense balances</h3>
      <p>Dr means debit balance and Cr means credit balance. Opening is before January 1; closing includes all postings through December 31. The draft offsets only this year's activity. Earlier nonzero balances must be resolved first.</p>
      <div className="table-wrap year-end-balances"><table className="year-end-table" aria-label="Temporary account balances">
        <thead><tr><th>Account</th><th>Kind</th><th>Opening</th><th>Year activity</th><th>Closing</th></tr></thead>
        <tbody>{result.temporaryAccounts.map((account) => <tr key={account.code}>
          <td>{account.code} - {account.name}</td><td>{account.kind}</td><td>{balance(account.openingBalance)}</td><td>{balance(account.periodBalance)}</td><td>{balance(account.closingBalance)}</td>
        </tr>)}</tbody>
      </table></div>
      <h3>Proposed closing lines</h3>
      <div className="table-wrap"><table aria-label="Proposed closing lines">
        <thead><tr><th>Account</th><th>Debit USD</th><th>Credit USD</th></tr></thead>
        <tbody>{result.proposedLines.map((line) => <tr key={line.code}>
          <td>{line.code} - {line.name}</td><td>{dollars(cents(line.debit))}</td><td>{dollars(cents(line.credit))}</td>
        </tr>)}<tr><td>Proposed totals</td><td>{dollars(cents(result.proposedDebits))}</td><td>{dollars(cents(result.proposedCredits))}</td></tr></tbody>
      </table></div>
      {!result.proposedLines.length && <p>No closing lines are proposed for this year's activity.</p>}
      <p>Owner contributions, drawings and permanent asset/liability accounts are not cleared by this earnings proposal. Scroll the balances table horizontally on a narrow screen.</p>
    </section>}
  </>;
}
