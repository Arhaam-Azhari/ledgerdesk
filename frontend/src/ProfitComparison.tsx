import { useEffect, useRef, useState } from "react";
import type { ReportData } from "./Reports";
import { cents, dollars, today } from "./money";

type Profit = ReportData["profitLoss"];
export type ComparisonData = {
  current: { startsOn: string; endsOn: string; profitLoss: Profit };
  previous: { startsOn: string; endsOn: string; profitLoss: Profit };
  change: Profit;
};
export type ComparisonLoader = (
  start: string, end: string, previousStart: string, previousEnd: string,
) => Promise<ComparisonData | null>;

export function ProfitComparison({ busy, workspace, load }: {
  busy: boolean; workspace: object; load: ComparisonLoader;
}) {
  const monthStart = today().slice(0, 7) + "-01";
  const lastMonthEnd = new Date(Date.parse(monthStart + "T00:00:00Z") - 86400000).toISOString().slice(0, 10);
  const [start, setStart] = useState(monthStart), [end, setEnd] = useState(today());
  const [previousStart, setPreviousStart] = useState(lastMonthEnd.slice(0, 7) + "-01");
  const [previousEnd, setPreviousEnd] = useState(lastMonthEnd);
  const [result, setResult] = useState<ComparisonData | null>(null);
  const version = useRef(0);
  useEffect(() => {
    version.current++;
    setResult(null);
    return () => { version.current++; };
  }, [workspace]);
  function edit(update: (value: string) => void, value: string) {
    version.current++;
    update(value);
    setResult(null);
  }
  const rows = result ? [
    ...result.current.profitLoss.accounts.map((account) => ({
      code: account.code, label: account.name, kind: account.kind,
      previous: result.previous.profitLoss.accounts.find((a) => a.code === account.code)!.amount,
      current: account.amount,
      change: result.change.accounts.find((a) => a.code === account.code)!.amount,
    })),
    ...(["revenue", "expenses", "netProfit"] as const).map((key, i) => ({
      code: "", label: ["Revenue", "Expenses", "Net profit"][i], kind: "Total",
      previous: result.previous.profitLoss[key], current: result.current.profitLoss[key], change: result.change[key],
    })),
  ] : [];
  function exportCsv() {
    if (!result) return;
    function text(value: string) {
      const clean = value.replace(/[\u0000-\u001f\u007f]/g, " ");
      const safe = /^\s*[=+\-@]/.test(clean) ? "'" + clean : clean;
      return '"' + safe.replaceAll('"', '""') + '"';
    }
    function amount(value: string) { cents(value); return '"' + value + '"'; }
    // Export the displayed snapshot; changing any date clears it before another download.
    const csv = [
      [text("Ledgerdesk profit comparison"), text("USD")],
      [text("Previous period"), text(result.previous.startsOn), text(result.previous.endsOn)],
      [text("Current period"), text(result.current.startsOn), text(result.current.endsOn)],
      [text("Change"), text("Current minus previous; no length adjustment")],
      [],
      ["Code", "Account", "Kind", "Previous USD", "Current USD", "Change USD"].map(text),
      ...rows.map((row) => [text(row.code), text(row.label), text(row.kind), amount(row.previous), amount(row.current), amount(row.change)]),
    ].map((row) => row.join(",")).join("\r\n") + "\r\n";
    const url = URL.createObjectURL(new Blob(["\uFEFF" + csv], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `ledgerdesk-profit-comparison-${result.previous.startsOn}-${result.previous.endsOn}-vs-${result.current.startsOn}-${result.current.endsOn}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <>
    <p className="intro">Compare earned revenue and incurred expenses across two periods. Payments do not recognize income or expense again.</p>
    <section className="card">
      <h2>Profit comparison dates</h2>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const requestedVersion = ++version.current;
        setResult(null);
        const response = await load(start, end, previousStart, previousEnd);
        if (requestedVersion === version.current) setResult(response);
      }}>
        <fieldset className="owner-fields" disabled={busy}>
          <legend>Previous period</legend>
          <label>Previous start<input type="date" min="0001-01-01" max="9999-12-31" required value={previousStart} onChange={(e) => edit(setPreviousStart, e.target.value)} /></label>
          <label>Previous end<input type="date" min="0001-01-01" max="9999-12-31" required value={previousEnd} onChange={(e) => edit(setPreviousEnd, e.target.value)} /></label>
        </fieldset>
        <fieldset className="owner-fields" disabled={busy}>
          <legend>Current period</legend>
          <label>Current start<input type="date" min="0001-01-01" max="9999-12-31" required value={start} onChange={(e) => edit(setStart, e.target.value)} /></label>
          <label>Current end<input type="date" min="0001-01-01" max="9999-12-31" required value={end} onChange={(e) => edit(setEnd, e.target.value)} /></label>
        </fieldset>
        <button disabled={busy}>Run comparison</button>
      </form>
      <p>Dates are inclusive. The previous period must end before the current starts. Unequal lengths and gaps are allowed; totals are not adjusted for period length.</p>
    </section>
    {!result && <p className="empty">Choose both periods and run the comparison to view or export results.</p>}
    {result && <section className="card comparison-results">
      <h2>Profit comparison</h2>
      <p><strong>Previous: {result.previous.startsOn} to {result.previous.endsOn}<br />Current: {result.current.startsOn} to {result.current.endsOn} · USD</strong></p>
      <button className="secondary" disabled={busy} onClick={exportCsv}>Export comparison CSV</button>
      <div className="table-wrap"><table className="comparison-table">
        <thead><tr><th scope="col">Code</th><th scope="col">Account / total</th><th scope="col">Previous USD</th><th scope="col">Current USD</th><th scope="col">Change USD</th></tr></thead>
        <tbody>{rows.map((row) => <tr key={row.code || row.label} className={row.kind === "Total" ? "comparison-total" : undefined}>
          <td>{row.code}</td><th scope="row">{row.label}</th><td>{dollars(cents(row.previous))}</td><td>{dollars(cents(row.current))}</td><td>{dollars(cents(row.change))}</td>
        </tr>)}</tbody>
      </table></div>
      <p>Change is current minus previous. A positive expense change means more expense, rather than a favorable result. Reversals keep their signed amounts in the period where they were posted.</p>
    </section>}
  </>;
}
