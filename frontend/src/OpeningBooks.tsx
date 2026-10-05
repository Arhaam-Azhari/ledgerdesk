import { useEffect, useRef, useState } from "react";
import { cents, dollars, today } from "./money";

type Balance = { code: string; debit: string; credit: string };
type Document = { partyId: string; partyName: string; reference: string; description: string; issuedOn: string; dueOn: string; amount: string };
type EditorDocument = Omit<Document, "partyName"> & { key: string };
export type OpeningBooksRequest = {
  asOf: string; reviewNote: string; balances: Balance[];
  receivables: { customerId: string; reference: string; description: string; issuedOn: string; dueOn: string; amount: string }[];
  payables: { vendorId: string; reference: string; description: string; issuedOn: string; dueOn: string; amount: string }[];
};
export type OpeningBooksPreview = {
  asOf: string; operatingStartsOn: string; currency: string; reviewNote: string;
  lines: (Balance & { name: string; kind: string })[];
  receivables: Document[]; payables: Document[];
  debits: string; credits: string; difference: string; bankBalance: string;
  receivableBalance: string; receivableDocuments: string; receivableDifference: string;
  payableBalance: string; payableDocuments: string; payableDifference: string;
  blockers: string[]; ready: boolean;
};
export type OpeningBooksHistory = {
  openingBooks: { id: string; as_of: string; review_note: string; snapshot: string; entry_id: string | null; bank_opening_id: string; created_by: string; created_at: string }[];
  receivables: { invoice_id: string; opening_books_id: string; original_reference: string; entry_id: string; amount: string; paid: string; status: string }[];
  payables: { bill_id: string; opening_books_id: string; reference: string; entry_id: string; amount: string; paid: string; status: string }[];
};
const accounts = [
  ["1000", "Bank"], ["1100", "Accounts receivable"], ["2000", "Accounts payable"],
  ["3000", "Owner capital"], ["3100", "Owner drawings"], ["3200", "Opening balance equity"], ["3300", "Retained earnings"],
];
const money = (value: string) => dollars(cents(value));
function Review({ result }: { result: OpeningBooksPreview }) {
  return <div className="opening-books-review">
    <p>Prior books end {result.asOf}. Operations start {result.operatingStartsOn}. Currency: {result.currency}.</p>
    <p>Debits {money(result.debits)} · Credits {money(result.credits)} · Difference {money(result.difference)}</p>
    <p>Bank {money(result.bankBalance)}</p>
    <p>Receivables: control {money(result.receivableBalance)} · documents {money(result.receivableDocuments)} · difference {money(result.receivableDifference)}</p>
    <p>Payables: control {money(result.payableBalance)} · documents {money(result.payableDocuments)} · difference {money(result.payableDifference)}</p>
    <div className="table-wrap"><table><thead><tr><th>Account</th><th>Debit</th><th>Credit</th></tr></thead><tbody>{result.lines.map(line => <tr key={line.code}><td>{line.code} · {line.name}</td><td>{money(line.debit)}</td><td>{money(line.credit)}</td></tr>)}</tbody></table></div>
    {(["receivables", "payables"] as const).map(kind => <div key={kind}><h3>{kind === "receivables" ? "Unpaid invoices" : "Unpaid bills"}</h3>{result[kind].length === 0 ? <p>None.</p> : result[kind].map((doc, index) => <p key={index}>{doc.partyName} · {doc.reference} · {doc.description} · issued {doc.issuedOn} · due {doc.dueOn} · {money(doc.amount)}</p>)}</div>)}
    <p>Review note: {result.reviewNote}</p>
  </div>;
}
export function OpeningBooks({ data, owner, busy, preview, loadHistory, act }: {
  data: { customers: { id: string; name: string }[]; vendors: { id: string; name: string }[]; openingBankBalances: unknown[] };
  owner: boolean; busy: boolean;
  preview: (request: OpeningBooksRequest) => Promise<OpeningBooksPreview | null>;
  loadHistory: () => Promise<OpeningBooksHistory | null>;
  act: (path: string, body: object, success: string) => Promise<boolean>;
}) {
  const [asOf, setAsOf] = useState(today()), [reviewNote, setReviewNote] = useState("");
  const [balances, setBalances] = useState<Balance[]>(accounts.map(([code]) => ({ code, debit: "0.00", credit: "0.00" })));
  const [receivables, setReceivables] = useState<EditorDocument[]>([]), [payables, setPayables] = useState<EditorDocument[]>([]);
  const [review, setReview] = useState<{ request: OpeningBooksRequest; result: OpeningBooksPreview } | null>(null);
  const [history, setHistory] = useState<OpeningBooksHistory | null>(null);
  const version = useRef(0);
  useEffect(() => { version.current++; setReview(null); setHistory(null); return () => { version.current++; }; }, [data]);
  function changed() { version.current++; setReview(null); }
  function documents(kind: "receivables" | "payables") {
    const rows = kind === "receivables" ? receivables : payables;
    const setRows = kind === "receivables" ? setReceivables : setPayables;
    const parties = kind === "receivables" ? data.customers : data.vendors;
    const label = kind === "receivables" ? "Invoice" : "Bill";
    return <section><h3>{kind === "receivables" ? "Unpaid invoices" : "Unpaid bills"}</h3>
      <p>Use fully unpaid original documents from prior books; historical partial payments are unsupported. Add {kind === "receivables" ? "customers in Customers" : "suppliers in Vendors"} first.</p>
      {rows.map((row, index) => {
        const prefix = `${label} ${index + 1}`;
        function update(field: keyof EditorDocument, value: string) { changed(); setRows(rows.map(item => item.key === row.key ? { ...item, [field]: value } : item)); }
        return <fieldset className="opening-document" key={row.key}><legend>{prefix}</legend><div className="owner-fields">
          <label>{prefix} {kind === "receivables" ? "customer" : "vendor"}<select required value={row.partyId} onChange={e => update("partyId", e.target.value)}><option value="">Choose a party</option>{parties.map(party => <option key={party.id} value={party.id}>{party.name}</option>)}</select></label>
          <label>{prefix} reference<input required maxLength={80} value={row.reference} onChange={e => update("reference", e.target.value)} /></label>
          <label>{prefix} description<input required maxLength={240} value={row.description} onChange={e => update("description", e.target.value)} /></label>
          <label>{prefix} unpaid amount<input required inputMode="decimal" pattern="[0-9]{1,12}(\.[0-9]{1,2})?" value={row.amount} onChange={e => update("amount", e.target.value)} /></label>
          <label>{prefix} issued on<input required type="date" min="0001-01-01" max={asOf} value={row.issuedOn} onChange={e => update("issuedOn", e.target.value)} /></label>
          <label>{prefix} due on<input required type="date" min={row.issuedOn || "0001-01-01"} max="9999-12-31" value={row.dueOn} onChange={e => update("dueOn", e.target.value)} /></label>
          <button type="button" onClick={() => { changed(); setRows(rows.filter(item => item.key !== row.key)); }}>Remove {prefix.toLowerCase()}</button>
        </div></fieldset>;
      })}
      <button type="button" disabled={rows.length >= 100 || parties.length === 0} onClick={() => { changed(); setRows([...rows, { key: crypto.randomUUID(), partyId: "", reference: "", description: "", issuedOn: asOf, dueOn: asOf, amount: "" }]); }}>Add unpaid {label.toLowerCase()}</button>
    </section>;
  }
  return <>
    <p className="intro">Bring reviewed balances and unpaid customer and supplier documents into fresh books. USD · one business per installation.</p>
    {owner && data.openingBankBalances.length === 0 && <section className="card"><h2>Prepare opening books</h2>
      <p>Use the last day covered by prior books and a cleared bank balance. Other activity must not already exist. Enter only the supported accounts below; the server checks the totals and setup prerequisites.</p>
      <form onSubmit={async e => {
        e.preventDefault(); setReview(null); const current = ++version.current;
        const request: OpeningBooksRequest = { asOf, reviewNote, balances: balances.map(row => ({ ...row })), receivables: receivables.map(({ partyId, reference, description, issuedOn, dueOn, amount }) => ({ customerId: partyId, reference, description, issuedOn, dueOn, amount })), payables: payables.map(({ partyId, reference, description, issuedOn, dueOn, amount }) => ({ vendorId: partyId, reference, description, issuedOn, dueOn, amount })) };
        const result = await preview(request); if (current === version.current && result) setReview({ request, result });
      }}><fieldset className="opening-editor" disabled={busy}>
        <label>Prior books end on<input required type="date" min="0001-01-01" max="9999-12-30" value={asOf} onChange={e => { changed(); setAsOf(e.target.value); }} /></label>
        <label>Opening review note<input required maxLength={240} value={reviewNote} onChange={e => { changed(); setReviewNote(e.target.value); }} /></label>
        <h3>Opening trial balance</h3><p>Use debit and credit amounts as shown in reviewed prior books. Zero is allowed. Do not put a positive amount on both sides of an account.</p>
        {balances.map((row, index) => <div className="opening-balance" key={row.code}><strong>{row.code} · {accounts[index][1]}</strong>{(["debit", "credit"] as const).map(side => <label key={side}>{row.code} {side}<input required inputMode="decimal" pattern="[0-9]{1,12}(\.[0-9]{1,2})?" value={row[side]} onChange={e => { changed(); setBalances(balances.map(item => item.code === row.code ? { ...item, [side]: e.target.value } : item)); }} /></label>)}</div>)}
        {documents("receivables")}{documents("payables")}
        <button type="submit">Preview opening books</button>
      </fieldset></form>
      {review && <section className="opening-preview"><h3>{review.result.ready ? "Opening preview ready" : "Opening preview needs attention"}</h3><Review result={review.result} />
        {review.result.blockers.length > 0 && <ul>{review.result.blockers.map(blocker => <li key={blocker}>{blocker}</li>)}</ul>}
        <p>Import creates opening journals and unpaid documents, without current sales or expenses. This setup is retained and cannot be undone here. Check every date, party, reference and amount before confirming.</p>
        <button disabled={busy || !review.result.ready} onClick={async () => {
          if (!window.confirm("Import these reviewed opening books? This setup is retained and has no correction workflow yet.")) return;
          // Keep the submitted snapshot on a failed response so an exact retry uses the same request key.
          await act("/api/opening-books", review.request, "Opening books imported. Load opening history to inspect the retained review.");
        }}>Import opening books</button>
      </section>}
    </section>}
    {owner && data.openingBankBalances.length > 0 && <p>Opening setup is already recorded. Load history below; bank-only setup has no opening-books import record.</p>}
    {!owner && <p>Only the owner can preview or import opening books. You can inspect the retained history below.</p>}
    <section className="card opening-history"><h2>Opening books history</h2><button disabled={busy} onClick={async () => { setHistory(null); const current = ++version.current; const result = await loadHistory(); if (current === version.current) setHistory(result); }}>Load opening history</button>
      {history && (history.openingBooks.length === 0 ? <p>No opening-books imports recorded.</p> : history.openingBooks.map(record => <article className="opening-history-record" key={record.id}>
        <h3>Prior books through {record.as_of}</h3><p>{record.review_note}</p><p>Recorded by {record.created_by} · {record.created_at}</p>
        <details><summary>Original opening review</summary><Review result={JSON.parse(record.snapshot) as OpeningBooksPreview} /></details>
        <h4>Current document settlement</h4><p>Payments below are current. The original review above retains the unpaid amounts at import.</p>
        {history.receivables.filter(row => row.opening_books_id === record.id).map(row => <p key={row.invoice_id}>Invoice {row.original_reference} · amount {money(row.amount)} · paid {money(row.paid)} · remaining {dollars(cents(row.amount) - cents(row.paid))} · {row.status}</p>)}
        {history.payables.filter(row => row.opening_books_id === record.id).map(row => <p key={row.bill_id}>Bill {row.reference} · amount {money(row.amount)} · paid {money(row.paid)} · remaining {dollars(cents(row.amount) - cents(row.paid))} · {row.status}</p>)}
        <details><summary>Opening evidence references</summary><p>Import {record.id} · balance journal {record.entry_id || "None (zero balances)"} · bank opening {record.bank_opening_id}</p>{history.receivables.filter(row => row.opening_books_id === record.id).map(row => <p key={row.invoice_id}>Invoice {row.invoice_id} · journal {row.entry_id}</p>)}{history.payables.filter(row => row.opening_books_id === record.id).map(row => <p key={row.bill_id}>Bill {row.bill_id} · journal {row.entry_id}</p>)}</details>
      </article>))}
    </section>
  </>;
}
