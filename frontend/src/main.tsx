import {
  AccountingPeriods,
  type PeriodState,
  type PeriodPreview,
} from "./AccountingPeriods";
import { OpeningBankBalance, type OpeningState } from "./OpeningBankBalance";
import { OwnPassword } from "./OwnPassword";
import { Accounts, type ManagedAccount } from "./Accounts";
import { FixedAssets, type AssetState } from "./FixedAssets";
import { Prepaids, type PrepaidState } from "./Prepaids";
import { Accruals, type AccrualState } from "./Accruals";
import { Adjustments, type AdjustmentState } from "./Adjustments";
import { OwnerEquity, type EquityState } from "./OwnerEquity";
import { Reports, type ReportData } from "./Reports";
import { CashActivity, type CashData } from "./CashActivity";
import {
  BankReconciliation,
  type ReconciliationState,
  type ReconciliationPreview,
  type Statement,
} from "./BankReconciliation";
import {
  BankMatching,
  type MatchState,
  type BankCandidate,
} from "./BankMatching";
import React, { useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";
import { Purchases, type PurchaseState, type Receipt } from "./Purchases";
import {
  Bank,
  type BankState,
  type BankPreview,
  type BankRequest,
} from "./Bank";
import { cents, dollars, today } from "./money";

type Customer = {
  id: string;
  name: string;
  email: string;
  invoiced: string;
  paid: string;
  outstanding: string;
};
type Invoice = {
  id: string;
  customer_id: string;
  number_value: number;
  customer_name: string;
  description: string;
  issued_on: string;
  due_on: string;
  amount: string;
  paid: string;
  status: string;
};
type Trial = { code: string; name: string; debits: string; credits: string };
type Line = {
  entry_id: string;
  entry_date: string;
  memo: string;
  name: string;
  debit: string;
  credit: string;
};
type Audit = {
  id: string;
  occurred_at: string;
  actor: string;
  action: string;
  record_id: string;
};
type Payment = {
  id: string;
  invoice_id: string;
  paid_on: string;
  amount: string;
};
type Draft = {
  id: string;
  customer_id: string;
  customer_name: string;
  description: string;
  issued_on: string;
  due_on: string;
  amount: string;
  version: number;
};
const invoiceNumber = (n: number) => `INV-${String(n).padStart(6, "0")}`;
type State = PeriodState &
  OpeningState &
  AssetState &
  PrepaidState &
  AccrualState &
  AdjustmentState &
  EquityState &
  PurchaseState &
  BankState &
  MatchState &
  ReconciliationState & {
    business: string;
    currency: string;
    customers: Customer[];
    invoices: Invoice[];
    drafts: Draft[];
    trialBalance: Trial[];
    ledger: Line[];
    audit: Audit[];
    payments: Payment[];
  };

function App() {
  const [credentials, setCredentials] = useState("");
  const [access, setAccess] = useState<{
    username: string;
    role: string;
    canWrite: boolean;
    persistentAccounts: boolean;
  } | null>(null);
  const [data, setData] = useState<State | null>(null);
  const [page, setPage] = useState("Overview");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  const [editing, setEditing] = useState<Draft | null>(null);
  const [customerId, setCustomerId] = useState("");
  const requests = useRef(new Map<string, string>());

  async function api(path: string, body?: object, auth = credentials) {
    const headers: Record<string, string> = { Authorization: `Basic ${auth}` };
    if (body) {
      const csrfResponse = await fetch("/api/csrf", {
        credentials: "same-origin",
      });
      if (!csrfResponse.ok)
        throw new Error("Could not obtain a request token. Try again.");
      const csrf = await csrfResponse.json();
      headers[csrf.headerName] = csrf.token;
      headers["Content-Type"] = "application/json";
      if (!path.startsWith("/api/accounts") && path !== "/api/me/password") {
        const signature = path + JSON.stringify(body);
        // Keep the same key after a connection failure, when posting may have succeeded.
        if (!requests.current.has(signature))
          requests.current.set(signature, crypto.randomUUID());
        headers["Idempotency-Key"] = requests.current.get(signature)!;
      }
    }
    const response = await fetch(path, {
      method: body ? "POST" : "GET",
      headers,
      credentials: "same-origin",
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
      const details = await response.json().catch(() => ({}));
      throw new Error(
        response.status === 401
          ? "Check your username and password."
          : details.message || "The request could not be completed.",
      );
    }
    const result = await response.json();
    return result;
  }

  async function refresh(auth = credentials) {
    const identity = await api("/api/access", undefined, auth);
    const workspace = await api("/api/state", undefined, auth);
    setAccess(identity);
    setData(workspace);
    if (identity.role !== "OWNER" && ["Accounts", "Opening bank balance", "Owner transfers"].includes(page))
      setPage("Reports");
    if (!identity.canWrite)
      setPage((current) =>
        [
          "Reports",
          "Cash activity",
          "Period close",
          "General ledger",
          "Trial balance",
          "Activity",
        ].includes(current)
          ? current
          : "Reports",
      );
  }
  async function act(
    path: string,
    body: object,
    success: string,
    form?: HTMLFormElement,
  ) {
    if (!access?.canWrite) {
      setError("This reviewer workspace is read-only.");
      return false;
    }
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await api(path, body);
      await refresh();
      requests.current.delete(path + JSON.stringify(body));
      setNotice(success);
      form?.reset();
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function bankCandidates(id: string): Promise<BankCandidate[] | null> {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      return await api(`/api/bank/transactions/${id}/candidates`);
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : "Could not load recorded entries.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function loadAccounts(): Promise<ManagedAccount[] | null> {
    setBusy(true);
    setError("");
    try {
      return await api("/api/accounts");
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "Could not load accounts.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }
  async function changeOwnPassword(currentPassword: string, password: string) {
    if (!access?.persistentAccounts) return false;
    setBusy(true); setError(""); setNotice("");
    try {
      await api("/api/me/password", { currentPassword, password });
      setData(null); setCredentials(""); setAccess(null); setPage("Overview");
      setEditing(null); setCustomerId(""); setChangingPassword(false);
      requests.current.clear();
      setNotice("Password changed. Sign in with your new password.");
      return true;
    } catch (error) {
      setError(error instanceof Error ? error.message : "Could not change your password.");
      return false;
    } finally { setBusy(false); }
  }
  async function manageAccount(
    path: string,
    body: object,
    success: string,
    lockAfter = false,
  ) {
    if (access?.role !== "OWNER" || !access.persistentAccounts) return false;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await api(path, body);
      if (lockAfter) {
        setChangingPassword(false);
        setData(null);
        setCredentials("");
        setAccess(null);
        setPage("Overview");
        requests.current.clear();
        setNotice("Password changed. Sign in with your new password.");
      } else {
        setNotice(success);
        try {
          await refresh();
        } catch {
          setError(
            "The account change was saved, but the workspace refresh failed. Reload to see it.",
          );
        }
      }
      return true;
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : "Could not change the account.",
      );
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function loadReports(
    start: string,
    end: string,
  ): Promise<ReportData | null> {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      return await api(
        `/api/reports?startsOn=${encodeURIComponent(start)}&endsOn=${encodeURIComponent(end)}`,
      );
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "Could not load reports.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function previewPeriod(end: string): Promise<PeriodPreview | null> {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      return await api(
        `/api/accounting-periods/preview?endsOn=${encodeURIComponent(end)}`,
      );
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : "Could not preview the accounting period.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function loadCashActivity(
    start: string,
    end: string,
  ): Promise<CashData | null> {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      return await api(
        `/api/reports/cash-activity?startsOn=${encodeURIComponent(start)}&endsOn=${encodeURIComponent(end)}`,
      );
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "Could not load reports.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function previewReconciliation(
    body: Statement,
  ): Promise<ReconciliationPreview | null> {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await api("/api/bank/reconciliations/preview", body);
      requests.current.delete(
        "/api/bank/reconciliations/preview" + JSON.stringify(body),
      );
      return result;
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : "Could not preview the statement.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function previewBank(body: BankRequest): Promise<BankPreview | null> {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await api("/api/bank/imports/preview", body);
      requests.current.delete(
        "/api/bank/imports/preview" + JSON.stringify(body),
      );
      return result;
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "Could not preview the CSV.",
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function uploadReceipt(
    type: string,
    id: string,
    file: File,
    form: HTMLFormElement,
  ) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      if (file.size === 0 || file.size > 2 * 1024 * 1024)
        throw new Error("Choose a nonempty receipt no larger than 2 MiB.");
      const hash = Array.from(
        new Uint8Array(
          await crypto.subtle.digest("SHA-256", await file.arrayBuffer()),
        ),
        (n) => n.toString(16).padStart(2, "0"),
      ).join("");
      const path = `/api/${type}/${id}/receipts`;
      const signature = path + file.name + file.type + hash;
      if (!requests.current.has(signature))
        requests.current.set(signature, crypto.randomUUID());
      const csrf = await fetch("/api/csrf", {
        credentials: "same-origin",
      }).then((response) => {
        if (!response.ok) throw new Error("Could not obtain a request token.");
        return response.json();
      });
      const body = new FormData();
      body.append("file", file);
      const response = await fetch(path, {
        method: "POST",
        credentials: "same-origin",
        headers: {
          Authorization: `Basic ${credentials}`,
          [csrf.headerName]: csrf.token,
          "Idempotency-Key": requests.current.get(signature)!,
        },
        body,
      });
      if (!response.ok) {
        const details = await response.json().catch(() => ({}));
        throw new Error(
          details.message || "The receipt could not be uploaded.",
        );
      }
      await refresh();
      requests.current.delete(signature);
      setNotice("Receipt attached. The ledger is unchanged.");
      form.reset();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function downloadReceipt(receipt: Receipt) {
    setBusy(true);
    setError("");
    try {
      const response = await fetch(`/api/receipts/${receipt.id}`, {
        headers: { Authorization: `Basic ${credentials}` },
        credentials: "same-origin",
      });
      if (!response.ok) throw new Error("The receipt could not be downloaded.");
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      const extension =
        receipt.media_type === "application/pdf"
          ? "pdf"
          : receipt.media_type === "image/png"
            ? "png"
            : "jpg";
      link.download = `receipt-${receipt.id}.${extension}`;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function downloadInvoice(invoice: Invoice) {
    setBusy(true);
    setError("");
    try {
      const response = await fetch(`/api/invoices/${invoice.id}/pdf`, {
        headers: { Authorization: `Basic ${credentials}` },
        credentials: "same-origin",
      });
      if (!response.ok)
        throw new Error(
          "Could not download this invoice. Try refreshing the workspace.",
        );
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = `${invoiceNumber(invoice.number_value)}.pdf`;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!data)
    return (
      <main className="login">
        <div className="login-card">
          <div className="logo">
            L<span>Ledgerdesk</span>
          </div>
          <p className="eyebrow">BUSINESS WORKSPACE</p>
          <h1>
            Books that explain
            <br />
            where the money went.
          </h1>
          <p>Invoices, payments, and the entries behind every balance.</p>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              setError("");
              const f = new FormData(e.currentTarget);
              const bytes = new TextEncoder().encode(
                `${f.get("username")}:${f.get("password")}`,
              );
              const auth = btoa(
                Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""),
              );
              try {
                await refresh(auth);
                setCredentials(auth);
              } catch (err) {
                setError((err as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <label>
              Username
              <input name="username" autoComplete="username" required />
            </label>
            <label>
              Password
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                required
              />
            </label>
            <button disabled={busy}>
              {busy ? "Opening workspace…" : "Open workspace →"}
            </button>
          </form>
          {notice && <p role="status">{notice}</p>}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <small>Local demo: demo / demo-local-only. Use fictional data.</small>
        </div>
      </main>
    );

  const balance = (code: string) => {
    const a = data.trialBalance.find((a) => a.code === code);
    return a ? cents(a.debits) - cents(a.credits) : 0n;
  };
  const nav = [
    "Overview",
    "Invoices",
    "Customers",
    "Vendors",
    "Bills",
    "Expenses",
    "Bank imports",
    "Bank matching",
    "Reconciliation",
    "Period close",
    "Reports",
    "Cash activity",
    ...(access?.role === "OWNER" ? ["Owner transfers", "Opening bank balance"] : []),
    "Adjustments",
    "Accruals",
    "Prepaid expenses",
    "Fixed assets",
    "General ledger",
    "Trial balance",
    "Activity",
    ...(access?.role === "OWNER" && access.persistentAccounts ? ["Accounts"] : []),
  ];
  const unpaid = data.invoices.filter(
    (i) => i.status === "POSTED" && cents(i.amount) > cents(i.paid),
  );
  function invoiceTable(items: Invoice[]) {
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Number</th>
              <th>Customer / work</th>
              <th>Due</th>
              <th>Amount</th>
              <th>Outstanding</th>
              <th>Status</th>
              <th>Copy</th>
            </tr>
          </thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.id}>
                <td>
                  <code>{invoiceNumber(i.number_value)}</code>
                </td>
                <td>
                  <strong>{i.customer_name}</strong>
                  <small>{i.description}</small>
                </td>
                <td>{i.due_on}</td>
                <td>{dollars(cents(i.amount))}</td>
                <td>
                  {dollars(
                    i.status === "VOID" ? 0n : cents(i.amount) - cents(i.paid),
                  )}
                </td>
                <td>
                  <span
                    className={`badge ${i.status === "VOID" ? "muted" : ""}`}
                  >
                    {i.status === "VOID"
                      ? "Void"
                      : cents(i.paid) === cents(i.amount)
                        ? "Paid"
                        : cents(i.paid) > 0n
                          ? "Part paid"
                          : "Unpaid"}
                  </span>
                </td>
                <td>
                  <button
                    className="secondary"
                    disabled={busy}
                    aria-label={`Download ${invoiceNumber(i.number_value)} PDF`}
                    onClick={() => void downloadInvoice(i)}
                  >
                    PDF ↓
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!items.length && (
          <p className="empty">
            No invoices yet. Post your first invoice to begin.
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="shell">
      <aside>
        <div className="logo">
          L<span>Ledgerdesk</span>
        </div>
        <p className="workspace">NORTHLINE DESIGN STUDIO</p>
        <nav>
          {nav
            .filter(
              (n) =>
                access?.canWrite ||
                [
                  "Reports",
                  "Cash activity",
                  "Period close",
                  "General ledger",
                  "Trial balance",
                  "Activity",
                ].includes(n),
            )
            .map((n) => (
              <button
                key={n}
                className={page === n ? "active" : ""}
                onClick={() => {
                  setPage(n);
                  setNotice("");
                  setError("");
                }}
              >
                {n}
              </button>
            ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="dot" />
          Local workspace
          <br />
          <small>USD · Accrual accounting</small>
        </div>
      </aside>
      <main>
        <header>
          <div>
            <p className="eyebrow">NORTHLINE / ACCOUNTING</p>
            <h1>{page}</h1>
          </div>
          <div className="header-tools">
            <button
              disabled={busy}
              onClick={() => {
                setData(null);
                setCredentials("");
                setAccess(null);
                setPage("Overview");
                setEditing(null);
                setCustomerId("");
                setChangingPassword(false);
                requests.current.clear();
              }}
            >
              Lock workspace
            </button>
            {access?.persistentAccounts && (
              <button className="secondary" disabled={busy} onClick={() => { setChangingPassword(true); setError(""); setNotice(""); }}>
                Change my password
              </button>
            )}
            <span className="demo-tag">Fictional business · USD</span>
            <button
              className="secondary"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                setError("");
                try {
                  await refresh();
                  setEditing(null);
                  setNotice("Workspace reloaded.");
                } catch (e) {
                  setError((e as Error).message);
                } finally {
                  setBusy(false);
                }
              }}
            >
              Reload workspace
            </button>
          </div>
        </header>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        {notice && (
          <div className="notice" role="status">
            {notice}
          </div>
        )}
        {changingPassword && access?.persistentAccounts && (
          <OwnPassword busy={busy} save={changeOwnPassword} cancel={() => { setChangingPassword(false); setError(""); }} />
        )}
        {access?.role === "BOOKKEEPER" && (
          <section className="note" aria-label="Bookkeeper access">
            <h2>Bookkeeper workspace</h2>
            <p>Signed in as {access.username}. Handle routine accounting and bank reconciliation.
              Ask an owner to manage accounts, record opening balances or owner transfers,
              close accounting periods, or reopen protected statements.</p>
          </section>
        )}
        {!access?.canWrite && (
          <section className="note" aria-label="Reviewer access">
            <h2>Read-only reviewer workspace</h2>
            <p>
              Signed in as {access?.username}. You can inspect reports, download
              report CSVs, and review ledger activity. Posting and editing are
              available to owners and bookkeepers.
            </p>
          </section>
        )}
        {page === "Overview" && (
          <>
            <p className="intro">
              A clear view of recorded sales, purchases, and outstanding
              balances.
            </p>
            <section className="metrics">
              <article>
                <span>Recorded bank balance</span>
                <h2>{dollars(balance("1000"))}</h2>
                <small>
                  Customer receipts and owner transfers, less recorded spending
                </small>
              </article>
              <article>
                <span>Accounts receivable</span>
                <h2>{dollars(balance("1100"))}</h2>
                <small>
                  {unpaid.length} {unpaid.length === 1 ? "invoice" : "invoices"}{" "}
                  with an open balance
                </small>
              </article>
              <article>
                <span>Recorded service revenue</span>
                <h2>{dollars(-balance("4000"))}</h2>
                <small>All posted invoices, less reversals</small>
              </article>
            </section>
            <section className="metrics purchases-metrics">
              <article>
                <span>Accounts payable</span>
                <h2>{dollars(-balance("2000"))}</h2>
                <small>Unpaid vendor bills</small>
              </article>
              <article>
                <span>Recorded operating expenses</span>
                <h2>
                  {dollars(
                    data.trialBalance
                      .filter((a) =>
                        data.expenseCategories.some((c) => c.code === a.code),
                      )
                      .reduce(
                        (sum, a) => sum + cents(a.debits) - cents(a.credits),
                        0n,
                      ),
                  )}
                </h2>
                <small>Bills and direct expenses, less reversals</small>
              </article>
            </section>
            <section className="card">
              <div className="section-heading">
                <div>
                  <h2>Waiting for payment</h2>
                  <p>Follow the balance from invoice to payment.</p>
                </div>
                <button onClick={() => setPage("Invoices")}>
                  Manage invoices →
                </button>
              </div>
              {invoiceTable(unpaid)}
            </section>
            <section className="note">
              <h3>Cash and revenue tell different stories.</h3>
              <p>
                Post a $1,200 invoice and record a $700 payment: revenue is
                $1,200, bank increases by $700, and the customer still owes
                $500. These are all-time ledger balances, not bank-statement
                reconciliation or period financial statements.
              </p>
            </section>
          </>
        )}
        {page === "Invoices" && (
          <>
            <div className="two-columns">
              <section className="card">
                <h2>{editing ? "Edit draft" : "Create an invoice"}</h2>
                <p>
                  {editing
                    ? "Only the saved draft changes. Posting is a separate action."
                    : "Save a complete draft for review, or post it now to record receivables and revenue."}
                </p>
                <form
                  key={editing ? `${editing.id}-${editing.version}` : "new"}
                  onSubmit={(e) => {
                    e.preventDefault();
                    const form = e.currentTarget;
                    const body = Object.fromEntries(new FormData(form));
                    const intent = (
                      e.nativeEvent as SubmitEvent
                    ).submitter?.getAttribute("value");
                    const path = editing
                      ? `/api/drafts/${editing.id}`
                      : intent === "draft"
                        ? "/api/drafts"
                        : "/api/invoices";
                    const payload = editing
                      ? { invoice: body, version: editing.version }
                      : body;
                    void act(
                      path,
                      payload,
                      editing
                        ? "Draft updated. The ledger is unchanged."
                        : intent === "draft"
                          ? "Draft saved. The ledger is unchanged."
                          : "Invoice posted. The ledger is updated.",
                      form,
                    ).then((ok) => {
                      if (ok) setEditing(null);
                    });
                  }}
                >
                  <label>
                    Customer
                    <select
                      name="customerId"
                      defaultValue={editing?.customer_id}
                      required
                    >
                      {data.customers.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Description
                    <input
                      name="description"
                      maxLength={240}
                      defaultValue={editing?.description}
                      placeholder="Brand identity design"
                      required
                    />
                  </label>
                  <div className="form-row">
                    <label>
                      Invoice date
                      <input
                        type="date"
                        name="issuedOn"
                        defaultValue={editing?.issued_on ?? today()}
                        required
                      />
                    </label>
                    <label>
                      Due date
                      <input
                        type="date"
                        name="dueOn"
                        defaultValue={editing?.due_on ?? today()}
                        required
                      />
                    </label>
                  </div>
                  <label>
                    Amount (USD)
                    <input
                      name="amount"
                      inputMode="decimal"
                      defaultValue={editing?.amount}
                      placeholder="1200.00"
                      pattern="[0-9]+(\.[0-9]{1,2})?"
                      required
                    />
                  </label>
                  <div className="button-row">
                    {editing ? (
                      <>
                        <button disabled={busy}>Save changes</button>
                        <button
                          type="button"
                          className="secondary"
                          disabled={busy}
                          onClick={() => setEditing(null)}
                        >
                          Cancel edit
                        </button>
                      </>
                    ) : (
                      <>
                        <button name="intent" value="post" disabled={busy}>
                          Post invoice
                        </button>
                        <button
                          name="intent"
                          value="draft"
                          className="secondary"
                          disabled={busy}
                        >
                          Save draft
                        </button>
                      </>
                    )}
                  </div>
                </form>
              </section>
              <section className="card">
                <h2>Record a payment</h2>
                <p>
                  A payment reduces receivables; it does not earn revenue twice.
                </p>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const form = e.currentTarget;
                    const f = new FormData(form);
                    void act(
                      `/api/invoices/${f.get("invoiceId")}/payments`,
                      { amount: f.get("amount"), paidOn: f.get("paidOn") },
                      "Payment recorded.",
                      form,
                    );
                  }}
                >
                  <label>
                    Open invoice
                    <select name="invoiceId" required>
                      {unpaid.map((i) => (
                        <option key={i.id} value={i.id}>
                          {invoiceNumber(i.number_value)} · {i.customer_name} ·{" "}
                          {i.description} ·{" "}
                          {dollars(cents(i.amount) - cents(i.paid))}
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
                  <label>
                    Amount (USD)
                    <input
                      name="amount"
                      inputMode="decimal"
                      placeholder="700.00"
                      pattern="[0-9]+(\.[0-9]{1,2})?"
                      required
                    />
                  </label>
                  <button disabled={busy || !unpaid.length}>
                    Record payment
                  </button>
                </form>
              </section>
            </div>
            <section className="card">
              <h2>Saved drafts</h2>
              <p>
                Drafts have no invoice number or ledger entries. Posting uses
                the saved version.
              </p>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Customer / work</th>
                      <th>Amount</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.drafts.map((d) => (
                      <tr key={d.id}>
                        <td>
                          <strong>{d.customer_name}</strong>
                          <small>{d.description}</small>
                        </td>
                        <td>{dollars(cents(d.amount))}</td>
                        <td>
                          <div className="button-row">
                            <button
                              className="secondary"
                              disabled={busy}
                              onClick={() => {
                                setEditing(d);
                                setError("");
                                setNotice("");
                                window.scrollTo({ top: 0, behavior: "smooth" });
                              }}
                            >
                              Edit draft
                            </button>
                            <button
                              disabled={busy || editing?.id === d.id}
                              onClick={() => {
                                if (
                                  window.confirm(
                                    "Post this saved draft and record it in the ledger?",
                                  )
                                )
                                  void act(
                                    `/api/drafts/${d.id}/post`,
                                    { version: d.version },
                                    "Invoice posted from the saved draft.",
                                  );
                              }}
                            >
                              Post draft
                            </button>
                            <button
                              className="secondary"
                              disabled={busy}
                              onClick={() => {
                                if (
                                  window.confirm(
                                    "Discard this draft? Its saved history will remain.",
                                  )
                                )
                                  void act(
                                    `/api/drafts/${d.id}/discard`,
                                    { version: d.version },
                                    "Draft discarded. The ledger is unchanged.",
                                  ).then((ok) => {
                                    if (ok && editing?.id === d.id)
                                      setEditing(null);
                                  });
                              }}
                            >
                              Discard draft
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!data.drafts.length && (
                  <p className="empty">No saved drafts.</p>
                )}
              </div>
            </section>
            <section className="card">
              <h2>All invoices</h2>
              {invoiceTable(data.invoices)}
            </section>
            <section className="card">
              <h2>Correct an unpaid invoice</h2>
              <p>
                Voiding keeps the original entry and posts a reversal. Paid
                invoices require a future credit/refund workflow.
              </p>
              <form
                className="inline-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  const f = new FormData(e.currentTarget);
                  if (window.confirm("Void this invoice and post a reversal?"))
                    void act(
                      `/api/invoices/${f.get("invoiceId")}/void`,
                      { date: f.get("date") },
                      "Invoice voided. Original entries remain in the ledger.",
                    );
                }}
              >
                <label>
                  Unpaid invoice
                  <select name="invoiceId" required>
                    {unpaid
                      .filter((i) => cents(i.paid) === 0n)
                      .map((i) => (
                        <option key={i.id} value={i.id}>
                          {i.customer_name} · {i.description}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  Reversal date
                  <input
                    name="date"
                    type="date"
                    defaultValue={today()}
                    required
                  />
                </label>
                <button
                  className="secondary"
                  disabled={busy || !unpaid.some((i) => cents(i.paid) === 0n)}
                >
                  Void invoice
                </button>
              </form>
            </section>
            <section className="card">
              <h2>Payment history</h2>
              <table>
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Invoice</th>
                    <th>Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {data.payments.map((p) => (
                    <tr key={p.id}>
                      <td>{p.paid_on}</td>
                      <td>
                        {
                          data.invoices.find((i) => i.id === p.invoice_id)
                            ?.description
                        }
                      </td>
                      <td>{dollars(cents(p.amount))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          </>
        )}
        {page === "Customers" && (
          <>
            <section className="card">
              <h2>Add a customer</h2>
              <form
                className="inline-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  const form = e.currentTarget;
                  void act(
                    "/api/customers",
                    Object.fromEntries(new FormData(form)),
                    "Customer added.",
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
                <button disabled={busy}>Add customer</button>
              </form>
            </section>
            <section className="card">
              <table>
                <thead>
                  <tr>
                    <th>Customer</th>
                    <th>Email</th>
                    <th>Net invoiced</th>
                    <th>Payments</th>
                    <th>Outstanding</th>
                  </tr>
                </thead>
                <tbody>
                  {data.customers.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <button
                          className="text-button"
                          onClick={() => setCustomerId(c.id)}
                        >
                          {c.name}
                        </button>
                      </td>
                      <td>{c.email}</td>
                      <td>{dollars(cents(c.invoiced))}</td>
                      <td>{dollars(cents(c.paid))}</td>
                      <td>{dollars(cents(c.outstanding))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p>
                All-time totals exclude drafts and voided invoices. Payments
                reduce the amount owed.
              </p>
            </section>
            {customerId && (
              <section className="card">
                <h2>
                  {data.customers.find((c) => c.id === customerId)?.name} ·
                  Invoices
                </h2>
                {invoiceTable(
                  data.invoices.filter((i) => i.customer_id === customerId),
                )}
              </section>
            )}
          </>
        )}
        {page === "General ledger" && (
          <section className="card">
            <h2>Posted entries</h2>
            <p>
              Invoices, bills, payments, expenses, and reversals have equal
              debits and credits.
            </p>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Description</th>
                    <th>Account</th>
                    <th>Debit</th>
                    <th>Credit</th>
                  </tr>
                </thead>
                <tbody>
                  {data.ledger.map((l, index) => (
                    <tr key={`${l.entry_id}-${index}`}>
                      <td>{l.entry_date}</td>
                      <td>{l.memo}</td>
                      <td>{l.name}</td>
                      <td>{dollars(cents(l.debit))}</td>
                      <td>{dollars(cents(l.credit))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!data.ledger.length && (
              <p className="empty">
                Post an invoice to create the first journal entry.
              </p>
            )}
          </section>
        )}
        {page === "Trial balance" && (
          <section className="card">
            <h2>All-time trial balance</h2>
            <p>
              Net account balances from every posted entry in this workspace.
            </p>
            <table>
              <thead>
                <tr>
                  <th>Account</th>
                  <th>Debit balance</th>
                  <th>Credit balance</th>
                </tr>
              </thead>
              <tbody>
                {data.trialBalance.map((a) => {
                  const net = cents(a.debits) - cents(a.credits);
                  return (
                    <tr key={a.code}>
                      <td>
                        {a.code} · {a.name}
                      </td>
                      <td>{dollars(net > 0n ? net : 0n)}</td>
                      <td>{dollars(net < 0n ? -net : 0n)}</td>
                    </tr>
                  );
                })}
              </tbody>
              <tfoot>
                <tr>
                  <th>Total</th>
                  <th>
                    {dollars(
                      data.trialBalance.reduce(
                        (s, a) =>
                          s +
                          (cents(a.debits) > cents(a.credits)
                            ? cents(a.debits) - cents(a.credits)
                            : 0n),
                        0n,
                      ),
                    )}
                  </th>
                  <th>
                    {dollars(
                      data.trialBalance.reduce(
                        (s, a) =>
                          s +
                          (cents(a.credits) > cents(a.debits)
                            ? cents(a.credits) - cents(a.debits)
                            : 0n),
                        0n,
                      ),
                    )}
                  </th>
                </tr>
              </tfoot>
            </table>
          </section>
        )}
        {["Vendors", "Bills", "Expenses"].includes(page) && (
          <Purchases
            page={page}
            data={data}
            busy={busy}
            act={act}
            upload={uploadReceipt}
            download={downloadReceipt}
          />
        )}
        {page === "Fixed assets" && (
          <FixedAssets
            data={data}
            busy={busy}
            act={act}
            openExpenses={() => setPage("Expenses")}
          />
        )}
        {page === "Prepaid expenses" && (
          <Prepaids
            data={data}
            busy={busy}
            act={act}
            openExpenses={() => setPage("Expenses")}
          />
        )}
        {page === "Accruals" && (
          <Accruals
            data={data}
            busy={busy}
            act={act}
            openBills={() => setPage("Bills")}
          />
        )}
        {page === "Adjustments" && (
          <Adjustments data={data} busy={busy} act={act} />
        )}
        {page === "Opening bank balance" && access?.role === "OWNER" && (
          <OpeningBankBalance data={data} busy={busy} act={act} />
        )}
        {page === "Owner transfers" && access?.role === "OWNER" && (
          <OwnerEquity data={data} busy={busy} act={act} />
        )}
        {page === "Bank imports" && (
          <Bank
            data={data}
            busy={busy}
            preview={previewBank}
            act={act}
            reportError={(message) => {
              setError(message);
              setNotice("");
            }}
          />
        )}
        {page === "Bank matching" && (
          <BankMatching
            data={data}
            busy={busy}
            candidates={bankCandidates}
            act={act}
          />
        )}
        {page === "Reconciliation" && (
          <BankReconciliation
            data={data}
            busy={busy}
            canReopen={access?.role === "OWNER"}
            preview={previewReconciliation}
            act={act}
          />
        )}
        {page === "Period close" && (
          <AccountingPeriods
            data={data}
            busy={busy}
            canWrite={access?.role === "OWNER"}
            preview={previewPeriod}
            act={act}
          />
        )}
        {page === "Cash activity" && (
          <CashActivity busy={busy} load={loadCashActivity} workspace={data} />
        )}
        {page === "Accounts" &&
          access?.role === "OWNER" &&
          access.persistentAccounts && (
            <Accounts
              busy={busy}
              username={access.username}
              workspace={data}
              load={loadAccounts}
              command={manageAccount}
            />
          )}
        {page === "Reports" && (
          <Reports busy={busy} load={loadReports} workspace={data} />
        )}
        {page === "Activity" && (
          <section className="card">
            <h2>Recorded actions</h2>
            <p>Created within the same transaction as the accounting change.</p>
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>Action</th>
                  <th>Record</th>
                </tr>
              </thead>
              <tbody>
                {data.audit.map((a) => (
                  <tr key={a.id}>
                    <td>{a.occurred_at.replace("T", " ").slice(0, 19)}</td>
                    <td>{a.actor}</td>
                    <td>{a.action.toLowerCase().replaceAll("_", " ")}</td>
                    <td>
                      <code>{a.record_id.slice(0, 8)}</code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </main>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
