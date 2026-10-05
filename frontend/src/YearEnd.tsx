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
export type YearEndHistory = { yearEndCloses: {
  id: string; calendar_year: number; starts_on: string; ends_on: string; status: string; version: number;
  review_note: string; snapshot: string; entry_id: string | null; accounting_period_id: string; bank_reconciliation_id: string;
  closed_by: string; closed_at: string; reversal_entry_id: string | null; reopen_reason: string | null;
  reopened_by: string | null; reopened_at: string | null;
}[] };
export type YearEndHistoryLoader = () => Promise<YearEndHistory | null>;
export type YearEndAction = (path: string, body: object, success: string) => Promise<boolean>;
export type YearEndLoader = (year: string) => Promise<YearEndData | null>;
function balance(value: string) {
  const amount = cents(value);
  return amount === 0n ? "$0.00" : `${dollars(amount < 0n ? -amount : amount)} ${amount < 0n ? "Cr" : "Dr"}`;
}

export function YearEnd({ busy, workspace, load, canWrite, loadHistory, act }: {
  busy: boolean; workspace: object; load: YearEndLoader; canWrite: boolean; loadHistory: YearEndHistoryLoader; act: YearEndAction;
}) {
  const [year, setYear] = useState(today().slice(0, 4));
  const [result, setResult] = useState<YearEndData | null>(null);
  const [note, setNote] = useState("");
  const [history, setHistory] = useState<YearEndHistory | null>(null);
  const [reopening, setReopening] = useState("");
  const [reason, setReason] = useState("");
  const latest = history?.yearEndCloses.find((row) => row.status === "CLOSED");
  const version = useRef(0);
  useEffect(() => {
    version.current++; setResult(null); setHistory(null); setReopening("");
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
            onChange={(event) => { version.current++; setYear(event.target.value); setResult(null); setNote(""); }} /></label>
          <button disabled={busy}>Run year-end preview</button>
        </fieldset>
      </form>
      <p>The review covers January 1 through December 31 in USD.</p>
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
      {canWrite && <form className="year-end-posting" onSubmit={async (event) => {
        event.preventDefault();
        if (!result.ready || !window.confirm(`Close earnings for ${result.year}? This posts the proposed entry and protects the year's supporting reviews.`)) return;
        if (await act("/api/year-end", { year: result.year, reviewNote: note }, "Year-end earnings closed. Load history to inspect the retained review.")) setNote("");
      }}>
        <label>Year-end review note<input required maxLength={240} disabled={busy || !result.ready} value={note} onChange={(event) => setNote(event.target.value)} /></label>
        <button disabled={busy || !result.ready || !note.trim()}>Close year-end earnings</button>
        <p>Review the proposal and supporting documents before confirming. A failed request retains the note so the same details can be retried.</p>
      </form>}
    </section>}
    <section className="card year-end-history">
      <h2>Year-end closing history</h2>
      <button className="secondary" disabled={busy} onClick={async () => {
        const requested = ++version.current; setHistory(null); setReopening("");
        const response = await loadHistory();
        if (requested === version.current) setHistory(response);
      }}>Load closing history</button>
      <p>Each close retains the figures reviewed before posting. Reopened records keep their original review and reversal references. Reload the history after changing the books.</p>
      {!history && <p>Load history to inspect recorded closes and reopening details.</p>}
      {history && !history.yearEndCloses.length && <p>No year-end closes have been recorded.</p>}
      {history?.yearEndCloses.map((row) => <article className="year-end-history-record" key={row.id}>
        <h3>{row.calendar_year} · {row.status === "CLOSED" ? "Closed" : "Reopened"}</h3>
        <p>{row.starts_on} to {row.ends_on} · version {row.version}</p>
        <p><strong>Review note:</strong> {row.review_note}<br /><strong>Closed by:</strong> {row.closed_by} · {row.closed_at}</p>
        {row.reopen_reason && <p><strong>Reopening reason:</strong> {row.reopen_reason}<br /><strong>Reopened by:</strong> {row.reopened_by} · {row.reopened_at}</p>}
        <details className="year-end-references"><summary>Closing evidence references</summary><dl>
          <dt>Close ID</dt><dd>{row.id}</dd><dt>Closing journal entry</dt><dd>{row.entry_id || "No entry: inactive year"}</dd>
          <dt>Accounting review ID</dt><dd>{row.accounting_period_id}</dd><dt>Bank review ID</dt><dd>{row.bank_reconciliation_id}</dd>
          {row.reversal_entry_id && <><dt>Reversal journal entry</dt><dd>{row.reversal_entry_id}</dd></>}
        </dl></details>
        <details><summary>Retained earnings review</summary><RetainedReview snapshot={row.snapshot} /></details>
        {canWrite && latest?.id === row.id && <>
          {reopening !== row.id && <button className="secondary" disabled={busy} onClick={() => { setReopening(row.id); setReason(""); }}>Reopen earnings year {row.calendar_year}</button>}
          {reopening === row.id && <form onSubmit={async (event) => {
            event.preventDefault();
            if (!window.confirm(`Reopen earnings for ${row.calendar_year}? The closing entry will be reversed; supporting period and bank reviews remain closed.`)) return;
            if (await act(`/api/year-end/${encodeURIComponent(row.id)}/reopen`, { version: row.version, reason }, "Year-end earnings reopened. Supporting period and bank reviews remain closed.")) { setReopening(""); setReason(""); }
          }}>
            <label>Year-end reopening reason<input required maxLength={240} disabled={busy} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
            <button disabled={busy || !reason.trim()}>Confirm earnings reopening</button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setReopening("")}>Cancel earnings reopening</button>
            <p>Reopen later accounting reviews first. To correct this year, separately reopen its accounting and bank reviews after this reversal.</p>
          </form>}
        </>}
      </article>)}
    </section>
  </>;
}


function RetainedReview({ snapshot }: { snapshot: string }) {
  try {
    const review = JSON.parse(snapshot) as YearEndData;
    return <>
      <p>Retained pre-posting figures: {review.startsOn} to {review.endsOn} · {review.currency}. These are the original review, not current account balances.</p>
      <p>Revenue {dollars(cents(review.profitLoss.revenue))} · Expenses {dollars(cents(review.profitLoss.expenses))} · Profit / loss {dollars(cents(review.profitLoss.netProfit))}</p>
      <div className="table-wrap"><table aria-label="Retained closing proposal"><thead><tr><th>Account</th><th>Debit USD</th><th>Credit USD</th></tr></thead><tbody>
        {review.proposedLines.map((line) => <tr key={line.code}><td>{line.code} - {line.name}</td><td>{dollars(cents(line.debit))}</td><td>{dollars(cents(line.credit))}</td></tr>)}
        <tr><td>Retained totals</td><td>{dollars(cents(review.proposedDebits))}</td><td>{dollars(cents(review.proposedCredits))}</td></tr>
      </tbody></table></div>
      {!review.proposedLines.length && <p>No closing lines were needed for this inactive year.</p>}
    </>;
  } catch {
    return <p role="alert">The retained review could not be displayed. Inspect the saved closing record before using it.</p>;
  }
}
