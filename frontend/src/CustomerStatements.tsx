import { useEffect, useRef, useState } from "react";
import { cents, dollars, today } from "./money";

export type StatementCustomer = { id: string; name: string; email: string };
export type StatementData = {
  customer: StatementCustomer;
  currency: string;
  startsOn: string; endsOn: string;
  openingBalance: string; charges: string; payments: string; reversals: string; closingBalance: string;
  movements: {
    entryId: string; lineId: string; sourceId: string; invoiceId: string; paymentId: string | null;
    reference: string; postedOn: string; kind: string; description: string;
    charge: string; reduction: string; balance: string;
  }[];
};
export type StatementLoader = (customer: string, start: string, end: string) => Promise<StatementData | null>;
export type StatementDownloader = (statement: StatementData) => Promise<void>;
const kinds: Record<string, string> = { INVOICE: "Invoice", PAYMENT: "Payment", INVOICE_REVERSAL: "Invoice reversal" };

export function CustomerStatements({ busy, customers, workspace, load, download }: {
  busy: boolean; customers: StatementCustomer[]; workspace: object; load: StatementLoader; download: StatementDownloader;
}) {
  const [customer, setCustomer] = useState(customers[0]?.id || "");
  const [start, setStart] = useState(today().slice(0, 4) + "-01-01"), [end, setEnd] = useState(today());
  const [result, setResult] = useState<StatementData | null>(null);
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
  const totals: [string, string][] = result ? [
    ["Opening amount owed", result.openingBalance], ["Invoice charges", result.charges],
    ["Payments received", result.payments], ["Invoice reversals", result.reversals], ["Closing amount owed", result.closingBalance],
  ] : [];
  function exportCsv() {
    if (!result) return;
    function text(value: string) {
      const clean = value.replace(/[\u0000-\u001f\u007f]/g, " ");
      return '"' + (/^\s*[=+\-@]/.test(clean) ? "'" + clean : clean).replaceAll('"', '""') + '"';
    }
    function amount(value: string) { cents(value); return '"' + value + '"'; }
    const rows = [
      [text("Ledgerdesk customer statement"), text(result.currency)],
      [text("Customer ID"), text(result.customer.id)],
      [text("Customer"), text(result.customer.name)], [text("Email"), text(result.customer.email)],
      [text("Period start"), text(result.startsOn)], [text("Period end"), text(result.endsOn)],
      ...totals.map(([label, value]) => [text(label), amount(value)]), [],
      ["Date", "Kind", "Invoice", "Description", "Charge USD", "Reduction USD", "Balance USD", "Entry ID", "Line ID", "Source ID", "Invoice ID", "Payment ID"].map(text),
      ...result.movements.map((m) => [text(m.postedOn), text(kinds[m.kind] || m.kind), text(m.reference), text(m.description),
        amount(m.charge), amount(m.reduction), amount(m.balance), text(m.entryId), text(m.lineId), text(m.sourceId), text(m.invoiceId), text(m.paymentId || "")]),
    ];
    // Dates, contact details and movements all come from the displayed statement.
    const url = URL.createObjectURL(new Blob(["\uFEFF" + rows.map((row) => row.join(",")).join("\r\n") + "\r\n"], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `ledgerdesk-customer-statement-${result.customer.id}-${result.startsOn}-${result.endsOn}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <>
    <p className="intro">Follow one customer's opening amount owed through dated invoices, payments and reversals. Reading or exporting does not change the books.</p>
    <section className="card">
      <h2>Customer statement dates</h2>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const requested = ++version.current;
        setResult(null);
        const response = await load(customer, start, end);
        if (requested === version.current) setResult(response);
      }}>
        <fieldset className="owner-fields" disabled={busy}>
          <label>Statement customer<select required value={customer} onChange={(e) => edit(setCustomer, e.target.value)}>
            <option value="">Choose a customer</option>
            {customers.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select></label>
          <label>Statement start<input type="date" min="0001-01-01" max="9999-12-31" required value={start} onChange={(e) => edit(setStart, e.target.value)} /></label>
          <label>Statement end<input type="date" min="0001-01-01" max="9999-12-31" required value={end} onChange={(e) => edit(setEnd, e.target.value)} /></label>
          <button disabled={!customer || busy}>Run statement</button>
        </fieldset>
      </form>
      <p>Opening includes postings before the start; activity includes both dates. Later payments and reversals leave the earlier statement unchanged.</p>
      {!customers.length && <p>No customers are available for a statement.</p>}
    </section>
    {!result && <p className="empty">Choose a customer and dates, then run the statement to view or export it.</p>}
    {result && <section className="card statement-results">
      <h2>Customer account statement</h2>
      <p><strong>{result.customer.name}</strong><br />{result.customer.email}<br />{result.startsOn} to {result.endsOn} · {result.currency}</p>
      <div className="button-row">
        <button className="secondary" disabled={busy} onClick={exportCsv}>Export statement CSV</button>
        <button className="secondary" disabled={busy} onClick={() => void download(result)}>Download statement PDF</button>
      </div>
      <p>PDF reads the books again for these dates. A new backdated posting can change its figures; CSV keeps the displayed statement. Neither download sends anything to the customer.</p>
      <div className="table-wrap"><table>
        <thead><tr><th>Calculation</th><th>USD</th></tr></thead>
        <tbody>{totals.map(([label, value]) => <tr key={label}><td>{label}</td><td>{dollars(cents(value))}</td></tr>)}</tbody>
      </table></div>
      <h3>Statement activity</h3>
      <p>Opening + charges − payments − reversals = closing. Scroll the activity table horizontally on a narrow screen.</p>
      <div className="table-wrap statement-movements"><table className="statement-table">
        <thead><tr><th>Date</th><th>Kind</th><th>Invoice / description</th><th>Charge USD</th><th>Reduction USD</th><th>Balance USD</th><th>Evidence</th></tr></thead>
        <tbody>{result.movements.map((m) => <tr key={m.lineId}>
          <td>{m.postedOn}</td><td>{kinds[m.kind] || m.kind}</td><td>{m.reference}<br />{m.description}</td>
          <td>{dollars(cents(m.charge))}</td><td>{dollars(cents(m.reduction))}</td><td>{dollars(cents(m.balance))}</td>
          <td><details><summary>Ledger references</summary><dl>
            <dt>Journal entry</dt><dd>{m.entryId}</dd><dt>Receivable line</dt><dd>{m.lineId}</dd>
            <dt>Source ID</dt><dd>{m.sourceId}</dd><dt>Invoice ID</dt><dd>{m.invoiceId}</dd>
            {m.paymentId && <><dt>Payment ID</dt><dd>{m.paymentId}</dd></>}
          </dl></details></td>
        </tr>)}</tbody>
      </table></div>
      {!result.movements.length && <p>No customer activity in this period. Any earlier balance is carried forward.</p>}
      <p>Same-day rows show invoices before payments and reversals; this is a stable display order, not a record of posting times. Contact details are current. CSV includes the displayed totals, movements and ledger references.</p>
    </section>}
  </>;
}
