import { useRef, useState } from "react";
import sampleCsv from "../../docs/examples/bank-statement.csv?raw";
import { cents, dollars } from "./money";

export type BankRequest = { label: string; csv: string };
export type BankPreview = {
  added: string;
  duplicates: string;
  rows: {
    transaction: {
      transactionId: string;
      date: string;
      description: string;
      amount: string;
    };
    duplicate: boolean;
  }[];
};
export type BankState = {
  bankImports: {
    id: string;
    label: string;
    imported_at: string;
    added_rows: string;
    duplicate_rows: string;
  }[];
  bankTransactions: {
    id: string;
    external_id: string;
    posted_on: string;
    description: string;
    amount: string;
    import_label: string;
  }[];
};

type Props = {
  data: BankState;
  busy: boolean;
  preview: (body: BankRequest) => Promise<BankPreview | null>;
  act: (path: string, body: object, success: string) => Promise<boolean>;
  reportError: (message: string) => void;
};

export function Bank({ data, busy, preview, act, reportError }: Props) {
  const [csv, setCsv] = useState("");
  const [label, setLabel] = useState("");
  const [reading, setReading] = useState(false);
  const [checked, setChecked] = useState<{
    request: BankRequest;
    result: BankPreview;
  } | null>(null);
  const selection = useRef(0);

  async function chooseFile(file?: File) {
    const version = ++selection.current;
    setCsv("");
    setChecked(null);
    reportError("");
    if (!file) {
      setLabel("");
      setReading(false);
      return;
    }
    setLabel(file.name.slice(0, 120));
    setReading(true);
    try {
      if (
        !file.name.toLowerCase().endsWith(".csv") ||
        file.size === 0 ||
        file.size > 256 * 1024
      )
        throw new Error("Choose a nonempty .csv file no larger than 256 KiB.");
      const bytes = await file.arrayBuffer();
      // Reject broken encoding rather than silently changing a bank transaction ID.
      const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      if (selection.current === version) setCsv(text);
    } catch (error) {
      if (selection.current === version)
        reportError(
          error instanceof TypeError
            ? "The CSV must use valid UTF-8 encoding."
            : error instanceof Error
              ? error.message
              : "Could not read the CSV.",
        );
    } finally {
      if (selection.current === version) setReading(false);
    }
  }

  function downloadExample() {
    const url = URL.createObjectURL(
      new Blob([sampleCsv], { type: "text/csv;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "bank-statement.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return (
    <>
      <p className="intro">
        Import statement evidence for the business bank. Importing does not
        record a payment, create an expense, or change the ledger.
      </p>
      <section className="card">
        <h2>Import a bank CSV</h2>
        <p>
          UTF-8 · Up to 256 KiB · 500 transactions. Use the headers{" "}
          <code>transaction_id,date,description,amount</code>. Dates use
          YYYY-MM-DD; positive amounts are incoming and negative amounts are
          outgoing.
        </p>
        <p>
          Keep the bank's stable transaction IDs. IDs with identical details are
          skipped; changed details reject the whole import. Bank-specific
          layouts need to be converted to this format first.
        </p>
        <button className="secondary" onClick={downloadExample}>
          Download example CSV
        </button>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            const request = { label, csv };
            const result = await preview(request);
            if (result) setChecked({ request, result });
          }}
        >
          <fieldset className="bank-fields" disabled={busy || reading}>
            <div className="form-row">
              <label>
                Import label
                <input
                  value={label}
                  maxLength={120}
                  required
                  onChange={(event) => {
                    setLabel(event.target.value);
                    setChecked(null);
                  }}
                />
              </label>
              <label>
                Bank CSV file
                <input
                  type="file"
                  accept=".csv,text/csv"
                  onChange={(event) => void chooseFile(event.target.files?.[0])}
                />
              </label>
            </div>
            <button disabled={!csv || busy || reading}>Preview import</button>
          </fieldset>
        </form>
        {reading && <p>Reading CSV…</p>}
      </section>
      {checked && (
        <section className="card">
          <h2>Import preview</h2>
          <p>
            {checked.result.added} new transaction{Number(checked.result.added) === 1 ? "" : "s"} ·{" "}
            {checked.result.duplicates} already imported. Every row is validated
            before saving. The import rechecks duplicates in case another tab
            has saved them since this preview.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Transaction ID</th>
                  <th>Date</th>
                  <th>Description</th>
                  <th>Amount</th>
                  <th>Import result</th>
                </tr>
              </thead>
              <tbody>
                {checked.result.rows.map((row) => (
                  <tr key={row.transaction.transactionId}>
                    <td>{row.transaction.transactionId}</td>
                    <td>{row.transaction.date}</td>
                    <td>{row.transaction.description}</td>
                    <td>{dollars(cents(row.transaction.amount))}</td>
                    <td>{row.duplicate ? "Skip duplicate" : "New"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <button
            disabled={busy}
            onClick={async () => {
              if (
                await act(
                  "/api/bank/imports",
                  checked.request,
                  "Bank CSV imported. The ledger is unchanged.",
                )
              )
                setChecked(null);
            }}
          >
            Confirm import
          </button>
        </section>
      )}
      <section className="card">
        <h2>Statement transactions</h2>
        <p>
          Imported rows are stored separately from accounting entries. They have
          not been reconciled.
        </p>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Transaction ID</th>
                <th>Description</th>
                <th>Amount</th>
                <th>Import</th>
              </tr>
            </thead>
            <tbody>
              {data.bankTransactions.map((row) => (
                <tr key={row.id}>
                  <td>{row.posted_on}</td>
                  <td>{row.external_id}</td>
                  <td>{row.description}</td>
                  <td>{dollars(cents(row.amount))}</td>
                  <td>{row.import_label}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!data.bankTransactions.length && (
          <p className="empty">No bank transactions imported yet.</p>
        )}
      </section>
      <section className="card">
        <h2>Import history</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Label</th>
                <th>Imported</th>
                <th>New rows</th>
                <th>Duplicates skipped</th>
              </tr>
            </thead>
            <tbody>
              {data.bankImports.map((row) => (
                <tr key={row.id}>
                  <td>{row.label}</td>
                  <td>{row.imported_at.replace("T", " ").slice(0, 19)}</td>
                  <td>{row.added_rows}</td>
                  <td>{row.duplicate_rows}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!data.bankImports.length && <p className="empty">No imports yet.</p>}
      </section>
    </>
  );
}
