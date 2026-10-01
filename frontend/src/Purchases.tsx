import React, { useState } from "react";
import { cents, dollars, today } from "./money";

export type Vendor = {
  id: string;
  name: string;
  email: string;
  billed: string;
  paid: string;
  outstanding: string;
};
export type Bill = {
  id: string;
  vendor_id: string;
  vendor_name: string;
  reference: string;
  description: string;
  issued_on: string;
  due_on: string;
  account_code: string;
  category: string;
  amount: string;
  paid: string;
  status: string;
};
export type Expense = {
  id: string;
  vendor_id: string;
  vendor_name: string;
  description: string;
  spent_on: string;
  category: string;
  amount: string;
  status: string;
};
export type Receipt = {
  id: string;
  bill_id: string | null;
  expense_id: string | null;
  filename: string;
  media_type: string;
  size_bytes: number;
};
export type PurchaseState = {
  vendors: Vendor[];
  bills: Bill[];
  expenses: Expense[];
  billPayments: {
    id: string;
    bill_id: string;
    paid_on: string;
    amount: string;
    reference: string;
    vendor_name: string;
  }[];
  expenseCategories: { code: string; name: string }[];
  receipts: Receipt[];
};
type Props = {
  page: string;
  data: PurchaseState;
  busy: boolean;
  act: (
    path: string,
    body: object,
    success: string,
    form?: HTMLFormElement,
  ) => Promise<boolean>;
  upload: (
    type: string,
    id: string,
    file: File,
    form: HTMLFormElement,
  ) => Promise<void>;
  download: (receipt: Receipt) => Promise<void>;
};

export function Purchases({ page, data, busy, act, upload, download }: Props) {
  const [selectedVendor, setSelectedVendor] = useState("");
  const openBills = data.bills.filter(
    (b) => b.status === "POSTED" && cents(b.amount) > cents(b.paid),
  );
  const unpaidBills = openBills.filter((b) => cents(b.paid) === 0n);
  const currentExpenses = data.expenses.filter((e) => e.status === "POSTED");
  const vendorSelect = (
    <label>
      Vendor
      <select name="vendorId" required>
        {data.vendors.map((v) => (
          <option key={v.id} value={v.id}>
            {v.name}
          </option>
        ))}
      </select>
    </label>
  );
  const categorySelect = (
    <label>
      Expense category
      <select name="accountCode" required>
        {data.expenseCategories.map((c) => (
          <option key={c.code} value={c.code}>
            {c.name}
          </option>
        ))}
      </select>
    </label>
  );
  const amountInput = (
    <label>
      Amount (USD)
      <input
        name="amount"
        inputMode="decimal"
        pattern="[0-9]+(\.[0-9]{1,2})?"
        placeholder="600.00"
        required
      />
    </label>
  );
  const descriptionInput = (
    <label>
      Description
      <input name="description" maxLength={240} required />
    </label>
  );

  function billTable(bills: Bill[]) {
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Vendor / bill</th>
              <th>Category</th>
              <th>Due</th>
              <th>Amount</th>
              <th>Outstanding</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {bills.map((b) => (
              <tr key={b.id}>
                <td>
                  <strong>
                    {b.vendor_name} · {b.reference}
                  </strong>
                  <small>{b.description}</small>
                </td>
                <td>{b.category}</td>
                <td>{b.due_on}</td>
                <td>{dollars(cents(b.amount))}</td>
                <td>
                  {dollars(
                    b.status === "VOID" ? 0n : cents(b.amount) - cents(b.paid),
                  )}
                </td>
                <td>
                  <span
                    className={`badge ${b.status === "VOID" ? "muted" : ""}`}
                  >
                    {b.status === "VOID"
                      ? "Void"
                      : cents(b.paid) === cents(b.amount)
                        ? "Paid"
                        : cents(b.paid) > 0n
                          ? "Part paid"
                          : "Unpaid"}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!bills.length && <p className="empty">No bills recorded.</p>}
      </div>
    );
  }

  function receipts(type: "bills" | "expenses") {
    const records = type === "bills" ? data.bills : data.expenses;
    const files = data.receipts.filter((r) =>
      type === "bills" ? r.bill_id : r.expense_id,
    );
    return (
      <section className="card">
        <h2>Supporting receipts</h2>
        <p>
          PDF, PNG, or JPEG · Up to 2 MiB each · Five files per record. Static
          PDFs only; no encrypted files, actions, or forms.
        </p>
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const fields = new FormData(form);
            const file = fields.get("file");
            if (file instanceof File)
              void upload(type, String(fields.get("recordId")), file, form);
          }}
        >
          <label>
            Purchase record
            <select name="recordId" required>
              {records.map((record) => (
                <option key={record.id} value={record.id}>
                  {record.vendor_name} ·{" "}
                  {"reference" in record
                    ? record.reference
                    : record.description}
                  {record.status === "VOID" ? " · Void" : ""}
                </option>
              ))}
            </select>
          </label>
          <label>
            Receipt file
            <input
              name="file"
              type="file"
              accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
              required
            />
          </label>
          <button disabled={busy || !records.length}>Attach receipt</button>
        </form>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Record</th>
                <th>Filename</th>
                <th>Size</th>
                <th>Download</th>
              </tr>
            </thead>
            <tbody>
              {files.map((r) => {
                const record = records.find(
                  (d) => d.id === (r.bill_id ?? r.expense_id),
                );
                return (
                  <tr key={r.id}>
                    <td>
                      {record &&
                        ("reference" in record
                          ? record.reference
                          : record.description)}
                    </td>
                    <td>{r.filename}</td>
                    <td>{Math.ceil(Number(r.size_bytes) / 1024)} KiB</td>
                    <td>
                      <button
                        className="secondary"
                        disabled={busy}
                        aria-label={`Download receipt ${r.filename}`}
                        onClick={() => void download(r)}
                      >
                        Download ↓
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {!files.length && <p className="empty">No supporting files yet.</p>}
        </div>
      </section>
    );
  }

  if (page === "Vendors")
    return (
      <>
        <section className="card">
          <h2>Add a vendor</h2>
          <form
            className="inline-form"
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget;
              void act(
                "/api/vendors",
                Object.fromEntries(new FormData(form)),
                "Vendor added.",
                form,
              );
            }}
          >
            <label>
              Name
              <input name="name" maxLength={120} required />
            </label>
            <label>
              Email
              <input name="email" type="email" maxLength={200} required />
            </label>
            <button disabled={busy}>Add vendor</button>
          </form>
        </section>
        <section className="card">
          <h2>Vendor balances</h2>
          <p>
            All-time bill totals, payments, and the amount still owed. Direct
            expenses are already paid and are shown separately.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Vendor</th>
                  <th>Email</th>
                  <th>Net billed</th>
                  <th>Bill payments</th>
                  <th>Outstanding</th>
                </tr>
              </thead>
              <tbody>
                {data.vendors.map((v) => (
                  <tr key={v.id}>
                    <td>
                      <button
                        className="text-button"
                        onClick={() => setSelectedVendor(v.id)}
                      >
                        {v.name}
                      </button>
                    </td>
                    <td>{v.email}</td>
                    <td>{dollars(cents(v.billed))}</td>
                    <td>{dollars(cents(v.paid))}</td>
                    <td>{dollars(cents(v.outstanding))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
        {selectedVendor && (
          <>
            <section className="card">
              <h2>
                {data.vendors.find((v) => v.id === selectedVendor)?.name} ·
                Bills
              </h2>
              {billTable(
                data.bills.filter((b) => b.vendor_id === selectedVendor),
              )}
            </section>
            <section className="card">
              <h2>Direct expenses for this vendor</h2>
              {expenseTable(
                data.expenses.filter((e) => e.vendor_id === selectedVendor),
              )}
            </section>
          </>
        )}
      </>
    );

  function expenseTable(expenses: Expense[]) {
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Vendor / work</th>
              <th>Category</th>
              <th>Amount</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {expenses.map((e) => (
              <tr key={e.id}>
                <td>{e.spent_on}</td>
                <td>
                  <strong>{e.vendor_name}</strong>
                  <small>{e.description}</small>
                </td>
                <td>{e.category}</td>
                <td>{dollars(cents(e.amount))}</td>
                <td>
                  <span
                    className={`badge ${e.status === "VOID" ? "muted" : ""}`}
                  >
                    {e.status === "VOID" ? "Reversed" : "Paid"}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!expenses.length && (
          <p className="empty">No direct expenses recorded.</p>
        )}
      </div>
    );
  }

  if (page === "Bills")
    return (
      <>
        <p className="intro">
          Record what the business owes, then track each payment against the
          bill.
        </p>
        {!data.vendors.length && (
          <p className="notice">
            Add a vendor on the Vendors page before recording purchases.
          </p>
        )}
        <div className="two-columns">
          <section className="card">
            <h2>Post a vendor bill</h2>
            <p>Record the expense now and the amount payable to the vendor.</p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const form = e.currentTarget;
                void act(
                  "/api/bills",
                  Object.fromEntries(new FormData(form)),
                  "Bill posted. Expense and payable recorded.",
                  form,
                );
              }}
            >
              {vendorSelect}
              <label>
                Bill reference
                <input
                  name="reference"
                  maxLength={80}
                  placeholder="SUP-2026-104"
                  required
                />
              </label>
              {descriptionInput}
              {categorySelect}
              <div className="form-row">
                <label>
                  Bill date
                  <input
                    name="issuedOn"
                    type="date"
                    defaultValue={today()}
                    required
                  />
                </label>
                <label>
                  Due date
                  <input
                    name="dueOn"
                    type="date"
                    defaultValue={today()}
                    required
                  />
                </label>
              </div>
              {amountInput}
              <button disabled={busy || !data.vendors.length}>Post bill</button>
            </form>
          </section>
          <section className="card">
            <h2>Pay a bill</h2>
            <p>
              Payment reduces the bank balance and payable. The expense is not
              recorded again.
            </p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const form = e.currentTarget;
                const fields = new FormData(form);
                void act(
                  `/api/bills/${fields.get("billId")}/payments`,
                  {
                    paidOn: fields.get("paidOn"),
                    amount: fields.get("amount"),
                  },
                  "Bill payment recorded.",
                  form,
                );
              }}
            >
              <label>
                Open bill
                <select name="billId" required>
                  {openBills.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.vendor_name} · {b.reference} ·{" "}
                      {dollars(cents(b.amount) - cents(b.paid))}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Payment date
                <input
                  name="paidOn"
                  type="date"
                  defaultValue={today()}
                  required
                />
              </label>
              {amountInput}
              <button disabled={busy || !openBills.length}>
                Record bill payment
              </button>
            </form>
          </section>
        </div>
        <section className="card">
          <h2>All bills</h2>
          {billTable(data.bills)}
        </section>
        <section className="card">
          <h2>Correct an unpaid bill</h2>
          <p>
            Voiding posts a reversal and preserves the original. Paid bills need
            a future vendor-credit workflow.
          </p>
          <form
            className="inline-form"
            onSubmit={(e) => {
              e.preventDefault();
              const fields = new FormData(e.currentTarget);
              if (
                window.confirm(
                  "Void this unpaid bill and reverse its expense and payable?",
                )
              )
                void act(
                  `/api/bills/${fields.get("billId")}/void`,
                  { date: fields.get("date") },
                  "Bill voided. Original entries remain.",
                );
            }}
          >
            <label>
              Unpaid bill
              <select name="billId" required>
                {unpaidBills.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.vendor_name} · {b.reference}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Reversal date
              <input name="date" type="date" defaultValue={today()} required />
            </label>
            <button
              className="secondary"
              disabled={busy || !unpaidBills.length}
            >
              Void bill
            </button>
          </form>
        </section>
        <section className="card">
          <h2>Bill payment history</h2>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Vendor</th>
                  <th>Bill</th>
                  <th>Amount</th>
                </tr>
              </thead>
              <tbody>
                {data.billPayments.map((p) => (
                  <tr key={p.id}>
                    <td>{p.paid_on}</td>
                    <td>{p.vendor_name}</td>
                    <td>{p.reference}</td>
                    <td>{dollars(cents(p.amount))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
        {receipts("bills")}
      </>
    );

  return (
    <>
      <p className="intro">
        Record operating expenses paid immediately from the business bank.
      </p>
      {!data.vendors.length && (
        <p className="notice">Add a vendor before recording an expense.</p>
      )}
      <section className="card">
        <h2>Record a direct expense</h2>
        <p>
          Use this for a purchase paid immediately. Use Bills when payment is
          still owed.
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            void act(
              "/api/expenses",
              Object.fromEntries(new FormData(form)),
              "Expense recorded. Bank and expense balances updated.",
              form,
            );
          }}
        >
          {vendorSelect}
          {descriptionInput}
          {categorySelect}
          <div className="form-row">
            <label>
              Expense date
              <input
                name="spentOn"
                type="date"
                defaultValue={today()}
                required
              />
            </label>
            {amountInput}
          </div>
          <button disabled={busy || !data.vendors.length}>
            Record expense
          </button>
        </form>
      </section>
      <section className="card">
        <h2>Direct expenses</h2>
        {expenseTable(data.expenses)}
      </section>
      <section className="card">
        <h2>Reverse a mistaken expense</h2>
        <p>
          This corrects an incorrect entry in the books; it does not send money
          or process a vendor refund.
        </p>
        <form
          className="inline-form"
          onSubmit={(e) => {
            e.preventDefault();
            const fields = new FormData(e.currentTarget);
            if (
              window.confirm(
                "Reverse this recorded expense and restore its book bank balance?",
              )
            )
              void act(
                `/api/expenses/${fields.get("expenseId")}/reverse`,
                { date: fields.get("date") },
                "Expense reversed. Original entries remain.",
              );
          }}
        >
          <label>
            Recorded expense
            <select name="expenseId" required>
              {currentExpenses.map((expense) => (
                <option key={expense.id} value={expense.id}>
                  {expense.vendor_name} · {expense.description}
                </option>
              ))}
            </select>
          </label>
          <label>
            Reversal date
            <input name="date" type="date" defaultValue={today()} required />
          </label>
          <button
            className="secondary"
            disabled={busy || !currentExpenses.length}
          >
            Reverse expense
          </button>
        </form>
      </section>
      {receipts("expenses")}
    </>
  );
}
