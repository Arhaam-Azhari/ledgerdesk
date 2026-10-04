import { useEffect, useRef, useState } from "react";
import { cents, dollars, today } from "./money";

export type LedgerAccount = { code: string; name: string };
export type AccountActivityData = {
  account: LedgerAccount & { kind: string }; currency: string; startsOn: string; endsOn: string;
  openingBalance: string; debits: string; credits: string; closingBalance: string;
  movements: { entryId: string; lineId: string; sourceId: string; postedOn: string; memo: string;
    debit: string; credit: string; balance: string }[];
};
export type ActivityLoader = (code: string, start: string, end: string) => Promise<AccountActivityData | null>;

function balance(value: string) {
  const amount = cents(value);
  return amount === 0n ? "$0.00" : `${dollars(amount < 0n ? -amount : amount)} ${amount < 0n ? "Cr" : "Dr"}`;
}

export function AccountActivity({ busy, accounts, workspace, load }: {
  busy: boolean; accounts: LedgerAccount[]; workspace: object; load: ActivityLoader;
}) {
  const [code, setCode] = useState(accounts[0]?.code || "");
  const [start, setStart] = useState(today().slice(0, 4) + "-01-01"), [end, setEnd] = useState(today());
  const [result, setResult] = useState<AccountActivityData | null>(null);
  const version = useRef(0);
  useEffect(() => {
    version.current++; setResult(null);
    return () => { version.current++; };
  }, [workspace]);
  function edit(update: (value: string) => void, value: string) {
    version.current++; update(value); setResult(null);
  }
  function exportCsv() {
    if (!result) return;
    function text(value: string) {
      const clean = value.replace(/[\u0000-\u001f\u007f]/g, " ");
      return '"' + (/^\s*[=+\-@]/.test(clean) ? "'" + clean : clean).replaceAll('"', '""') + '"';
    }
    function amount(value: string) { cents(value); return '"' + value + '"'; }
    const rows = [
      [text("Ledgerdesk account activity"), text(result.currency)],
      [text("Account code"), text(result.account.code)], [text("Account name"), text(result.account.name)],
      [text("Account kind"), text(result.account.kind)], [text("Period start"), text(result.startsOn)], [text("Period end"), text(result.endsOn)],
      [text("Balance convention"), text("Debits minus credits; negative is credit, positive is debit")],
      [text("Opening balance"), amount(result.openingBalance)], [text("Period debits"), amount(result.debits)],
      [text("Period credits"), amount(result.credits)], [text("Closing balance"), amount(result.closingBalance)], [],
      ["Date", "Memo", "Debit USD", "Credit USD", "Signed balance USD", "Entry ID", "Line ID", "Source ID"].map(text),
      ...result.movements.map((m) => [text(m.postedOn), text(m.memo), amount(m.debit), amount(m.credit), amount(m.balance),
        text(m.entryId), text(m.lineId), text(m.sourceId)]),
    ];
    // Export server balances as signed decimals; Dr/Cr is only the screen presentation.
    const url = URL.createObjectURL(new Blob(["\uFEFF" + rows.map((row) => row.join(",")).join("\r\n") + "\r\n"], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url; link.download = `ledgerdesk-account-${result.account.code}-${result.startsOn}-${result.endsOn}.csv`;
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <>
    <p className="intro">Explain one ledger account's opening balance, debit and credit postings, and closing balance for an inclusive period. Reading and exporting do not change the books.</p>
    <section className="card">
      <h2>Account activity dates</h2>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const requested = ++version.current; setResult(null);
        const response = await load(code, start, end);
        if (requested === version.current) setResult(response);
      }}>
        <fieldset className="owner-fields" disabled={busy}>
          <label>Ledger account<select required value={code} onChange={(e) => edit(setCode, e.target.value)}>
            <option value="">Choose an account</option>
            {accounts.map((a) => <option key={a.code} value={a.code}>{a.code} - {a.name}</option>)}
          </select></label>
          <label>Activity start<input type="date" min="0001-01-01" max="9999-12-31" required value={start} onChange={(e) => edit(setStart, e.target.value)} /></label>
          <label>Activity end<input type="date" min="0001-01-01" max="9999-12-31" required value={end} onChange={(e) => edit(setEnd, e.target.value)} /></label>
          <button disabled={busy || !code}>Run account activity</button>
        </fieldset>
      </form>
      <p>Opening includes postings before the start; activity includes both dates. Dr means debit balance, Cr means credit balance. Zero has neither side.</p>
      {!accounts.length && <p>No ledger accounts are available.</p>}
    </section>
    {!result && <p className="empty">Choose an account and dates, then run activity to view or export it.</p>}
    {result && <section className="card account-activity-results">
      <h2>Ledger account activity</h2>
      <p><strong>{result.account.code} - {result.account.name}</strong><br />{result.account.kind} · {result.currency} · {result.startsOn} to {result.endsOn}</p>
      <button className="secondary" disabled={busy} onClick={exportCsv}>Export account activity CSV</button>
      <div className="table-wrap"><table>
        <thead><tr><th>Calculation</th><th>USD</th></tr></thead>
        <tbody>
          <tr><td>Opening balance</td><td>{balance(result.openingBalance)}</td></tr>
          <tr><td>Period debits</td><td>{dollars(cents(result.debits))}</td></tr>
          <tr><td>Period credits</td><td>{dollars(cents(result.credits))}</td></tr>
          <tr><td>Closing balance</td><td>{balance(result.closingBalance)}</td></tr>
        </tbody>
      </table></div>
      <h3>Account postings</h3>
      <p>Opening + debits − credits = closing, using signed debit-minus-credit amounts. CSV preserves those signed decimals.</p>
      <div className="table-wrap account-movements"><table className="statement-table">
        <thead><tr><th>Date</th><th>Memo</th><th>Debit USD</th><th>Credit USD</th><th>Balance USD</th><th>Evidence</th></tr></thead>
        <tbody>{result.movements.map((m) => <tr key={m.lineId}>
          <td>{m.postedOn}</td><td>{m.memo}</td><td>{dollars(cents(m.debit))}</td><td>{dollars(cents(m.credit))}</td><td>{balance(m.balance)}</td>
          <td><details><summary>Posting references</summary><dl>
            <dt>Journal entry</dt><dd>{m.entryId}</dd><dt>Journal line</dt><dd>{m.lineId}</dd><dt>Source ID</dt><dd>{m.sourceId}</dd>
          </dl></details></td>
        </tr>)}</tbody>
      </table></div>
      {!result.movements.length && <p>No account postings in this period. Any earlier balance is carried forward.</p>}
      <p>Rows follow accounting date, entry ID and line ID; same-day order does not represent posting times. Source IDs are not always invoice IDs. Scroll the postings horizontally on a narrow screen.</p>
    </section>}
  </>;
}
