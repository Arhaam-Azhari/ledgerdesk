import { useEffect, useState } from "react";
import { cents, dollars, today } from "./money";
export type CashData = {
  startsOn: string;
  endsOn: string;
  openingCash: string;
  receipts: string;
  payments: string;
  netChange: string;
  closingCash: string;
  difference: string;
  categories: {
    code: string;
    receipts: string;
    payments: string;
    net: string;
  }[];
  movements: {
    lineId: string;
    entryId: string;
    sourceId: string;
    postedOn: string;
    memo: string;
    category: string;
    receipt: string;
    payment: string;
  }[];
};
const labels: Record<string, string> = {
  CUSTOMERS: "Customers",
  SUPPLIERS: "Suppliers",
  DIRECT_PURCHASES: "Direct purchases",
  OWNER: "Owner transfers",
  OTHER: "Other",
};
export function CashActivity({
  busy,
  load,
  workspace,
}: {
  busy: boolean;
  workspace: object;
  load: (start: string, end: string) => Promise<CashData | null>;
}) {
  const [start, setStart] = useState(today().slice(0, 4) + "-01-01"),
    [end, setEnd] = useState(today());
  const [result, setResult] = useState<CashData | null>(null);
  useEffect(() => {
    setResult(null);
  }, [workspace]);
  function download() {
    if (!result) return;
    // Keep user-entered memos as text when the file is opened in a spreadsheet.
    function text(value: string) {
      const clean = value.replace(/[\u0000-\u001f\u007f]/g, " ");
      return (
        '"' +
        (/^\s*[=+\-@]/.test(clean) ? "'" + clean : clean).replaceAll(
          '"',
          '""',
        ) +
        '"'
      );
    }
    function amount(value: string) {
      cents(value);
      return '"' + value + '"';
    }
    const rows = [
      [text("Ledgerdesk cash activity"), text("USD")],
      [text("Start"), text(result.startsOn)],
      [text("End"), text(result.endsOn)],
      [],
    ];
    for (const [label, value] of [
      ["Opening cash", result.openingCash],
      ["Receipts", result.receipts],
      ["Payments", result.payments],
      ["Net change", result.netChange],
      ["Closing cash", result.closingCash],
      ["Difference", result.difference],
    ])
      rows.push([text(label), amount(value)]);
    rows.push(
      [],
      ["Category", "Receipts USD", "Payments USD", "Net USD"].map(text),
    );
    for (const c of result.categories)
      rows.push([
        text(labels[c.code] ?? c.code),
        amount(c.receipts),
        amount(c.payments),
        amount(c.net),
      ]);
    rows.push(
      [],
      [
        "Date",
        "Memo",
        "Category",
        "Receipt USD",
        "Payment USD",
        "Ledger entry ID",
        "Source ID",
        "Cash line ID",
      ].map(text),
    );
    for (const m of result.movements)
      rows.push([
        text(m.postedOn),
        text(m.memo),
        text(labels[m.category] ?? m.category),
        amount(m.receipt),
        amount(m.payment),
        text(m.entryId),
        text(m.sourceId),
        text(m.lineId),
      ]);
    const url = URL.createObjectURL(
      new Blob(
        ["\ufeff" + rows.map((r) => r.join(",")).join("\r\n") + "\r\n"],
        { type: "text/csv;charset=utf-8" },
      ),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = `ledgerdesk-cash-activity-${result.startsOn}-${result.endsOn}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <>
      <p className="intro">
        Follow money through the business bank ledger. Unpaid invoices, unpaid
        bills and depreciation do not move cash. Groups describe the original
        posting; this is a cash activity report, not a formal cash-flow
        statement.
      </p>
      <section className="card">
        <h2>Cash activity dates</h2>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setResult(null);
            setResult(await load(start, end));
          }}
        >
          <fieldset className="owner-fields" disabled={busy}>
            <label>
              Cash activity start
              <input
                type="date"
                min="0001-01-01"
                max="9999-12-31"
                value={start}
                onChange={(e) => {
                  setStart(e.target.value);
                  setResult(null);
                }}
                required
              />
            </label>
            <label>
              Cash activity end
              <input
                type="date"
                min={start}
                max="9999-12-31"
                value={end}
                onChange={(e) => {
                  setEnd(e.target.value);
                  setResult(null);
                }}
                required
              />
            </label>
            <button>Run cash activity</button>
          </fieldset>
        </form>
        <p>
          Both dates are included. Opening cash includes earlier postings;
          closing cash includes all postings through the end date. Bank imports
          and matching do not create a second cash movement.
        </p>
      </section>
      {!result && (
        <section className="card">
          <p>Choose dates and run the report to see recorded cash movements.</p>
        </section>
      )}
      {result && (
        <>
          <section className="card">
            <h2>
              Cash bridge · {result.startsOn} to {result.endsOn}
            </h2>
            <button className="secondary" onClick={download} disabled={busy}>
              Download cash activity CSV
            </button>
            <dl>
              {[
                ["Opening cash", result.openingCash],
                ["Receipts", result.receipts],
                ["Payments", result.payments],
                ["Net change", result.netChange],
                ["Closing cash", result.closingCash],
                ["Reconciliation difference", result.difference],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{dollars(cents(value))}</dd>
                </div>
              ))}
            </dl>
            {cents(result.difference) !== 0n && (
              <p className="error" role="alert">
                The cash bridge does not reconcile. Review the ledger before
                relying on this report.
              </p>
            )}
            <div className="table-wrap">
              <table aria-label="Cash activity groups">
                <thead>
                  <tr>
                    <th>Group</th>
                    <th>Receipts USD</th>
                    <th>Payments USD</th>
                    <th>Net USD</th>
                  </tr>
                </thead>
                <tbody>
                  {result.categories.map((c) => (
                    <tr key={c.code}>
                      <td>{labels[c.code] ?? c.code}</td>
                      <td>{dollars(cents(c.receipts))}</td>
                      <td>{dollars(cents(c.payments))}</td>
                      <td>{dollars(cents(c.net))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <section className="card">
            <h2>Recorded cash movements</h2>
            <p>
              Reversals remain separate receipts or payments. A purchase later
              capitalized as equipment remains in its original cash group.
            </p>
            {result.movements.length === 0 ? (
              <p>No recorded cash movements in this period.</p>
            ) : (
              <div className="table-wrap">
                <table aria-label="Cash movements">
                  <thead>
                    <tr>
                      <th>Date / evidence</th>
                      <th>Memo</th>
                      <th>Group</th>
                      <th>Receipt USD</th>
                      <th>Payment USD</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.movements.map((m) => (
                      <tr key={m.lineId}>
                        <td>
                          {m.postedOn}
                          <details>
                            <summary>Ledger references</summary>
                            <p>Entry: {m.entryId}</p>
                            <p>Source: {m.sourceId}</p>
                            <p>Cash line: {m.lineId}</p>
                          </details>
                        </td>
                        <td>{m.memo}</td>
                        <td>{labels[m.category] ?? m.category}</td>
                        <td>{dollars(cents(m.receipt))}</td>
                        <td>{dollars(cents(m.payment))}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </>
  );
}
