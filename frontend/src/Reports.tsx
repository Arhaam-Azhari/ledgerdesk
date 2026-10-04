import { useEffect, useState } from "react";
import { cents, dollars, today } from "./money";
import { ProfitComparison, type ComparisonLoader } from "./ProfitComparison";
import { CustomerStatements, type StatementCustomer, type StatementLoader } from "./CustomerStatements";

type Account = { code: string; name: string; kind: string; amount: string };
type Aging = {
  items: {
    id: string;
    reference: string;
    party: string;
    dueOn: string;
    daysOverdue: string;
    bucket: string;
    outstanding: string;
  }[];
  buckets: Record<string, string>;
  total: string;
};
export type ReportData = {
  startsOn: string;
  endsOn: string;
  profitLoss: {
    accounts: Account[];
    revenue: string;
    expenses: string;
    netProfit: string;
  };
  trialBalance: {
    accounts: { code: string; name: string; debit: string; credit: string }[];
    debits: string;
    credits: string;
  };
  balanceSheet: {
    assets: Account[];
    liabilities: Account[];
    equityAccounts: Account[];
    totalAssets: string;
    totalLiabilities: string;
    postedEquity: string;
    accumulatedEarnings: string;
    totalEquity: string;
    liabilitiesAndEquity: string;
    difference: string;
  };
  receivables: Aging;
  payables: Aging;
};
const views = [
  "Profit and loss",
  "Balance sheet",
  "Trial balance report",
  "Receivables aging",
  "Payables aging",
] as const;
type View = (typeof views)[number];
type Cell = string | { amount: string };
const amount = (value: string): Cell => ({ amount: value });
function csvCell(cell: Cell): string {
  if (typeof cell !== "string") {
    cents(cell.amount);
    return `"${cell.amount}"`;
  }
  // Quoting handles commas; the prefix keeps user text from becoming a spreadsheet formula.
  const text = cell.replace(/[\u0000-\u001f\u007f]/g, " ");
  const safe = /^\s*[=+\-@]/.test(text) ? "'" + text : text;
  return `"${safe.replaceAll('"', '""')}"`;
}
function AccountTable({ accounts }: { accounts: Account[] }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Account</th>
            <th>USD</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((a) => (
            <tr key={a.code}>
              <td>{a.code}</td>
              <td>{a.name}</td>
              <td>{dollars(cents(a.amount))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Totals({ rows }: { rows: [string, string][] }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Calculation</th>
            <th>USD</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label}>
              <td>{label}</td>
              <td>{dollars(cents(value))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function Reports({
  busy,
  load,
  workspace,
  compare,
  customers,
  statement,
}: {
  workspace: object;
  busy: boolean;
  load: (start: string, end: string) => Promise<ReportData | null>;
  compare: ComparisonLoader;
  customers: StatementCustomer[];
  statement: StatementLoader;
}) {
  const [mode, setMode] = useState<"single" | "compare" | "statement">("single");
  const [start, setStart] = useState(today().slice(0, 4) + "-01-01");
  const [end, setEnd] = useState(today());
  const [result, setResult] = useState<ReportData | null>(null);
  useEffect(() => {
    setResult(null);
  }, [workspace]);
  const [view, setView] = useState<View>("Profit and loss");
  const p = result?.profitLoss,
    b = result?.balanceSheet;
  const aging =
    result &&
    (view === "Receivables aging" ? result.receivables : result.payables);
  function exportCsv() {
    if (!result) return;
    const rows: Cell[][] = [
      ["Ledgerdesk", view],
      ["Currency", "USD"],
      ["Period start", result.startsOn],
      ["Period end / as of", result.endsOn],
      [],
    ];
    if (view === "Profit and loss") {
      rows.push(
        ["Code", "Account", "Kind", "Amount USD"],
        ...result.profitLoss.accounts.map((a) => [
          a.code,
          a.name,
          a.kind,
          amount(a.amount),
        ]),
      );
      for (const [label, value] of [
        ["Revenue", result.profitLoss.revenue],
        ["Expenses", result.profitLoss.expenses],
        ["Net profit", result.profitLoss.netProfit],
      ])
        rows.push(["", label, "Total", amount(value)]);
    } else if (view === "Balance sheet") {
      rows.push(["Section", "Code", "Account", "Amount USD"]);
      for (const [section, accounts] of [
        ["Assets", result.balanceSheet.assets],
        ["Liabilities", result.balanceSheet.liabilities],
        ["Posted equity", result.balanceSheet.equityAccounts],
      ] as [string, Account[]][]) {
        for (const a of accounts)
          rows.push([section, a.code, a.name, amount(a.amount)]);
      }
      for (const [label, value] of [
        ["Total assets", result.balanceSheet.totalAssets],
        ["Total liabilities", result.balanceSheet.totalLiabilities],
        ["Accumulated earnings", result.balanceSheet.accumulatedEarnings],
        ["Total equity", result.balanceSheet.totalEquity],
        ["Liabilities and equity", result.balanceSheet.liabilitiesAndEquity],
        ["Equation difference", result.balanceSheet.difference],
      ])
        rows.push(["Total", "", label, amount(value)]);
    } else if (view === "Trial balance report") {
      rows.push(
        ["Code", "Account", "Debit USD", "Credit USD"],
        ...result.trialBalance.accounts.map((a) => [
          a.code,
          a.name,
          amount(a.debit),
          amount(a.credit),
        ]),
        [
          "",
          "Totals",
          amount(result.trialBalance.debits),
          amount(result.trialBalance.credits),
        ],
      );
    } else {
      const data =
        view === "Receivables aging" ? result.receivables : result.payables;
      rows.push(
        [
          "Reference",
          "Party",
          "Due date",
          "Days overdue",
          "Bucket",
          "Outstanding USD",
        ],
        ...data.items.map((a) => [
          a.reference,
          a.party,
          a.dueOn,
          a.daysOverdue,
          a.bucket,
          amount(a.outstanding),
        ]),
        ["", "Total outstanding", "", "", "", amount(data.total)],
      );
      rows.push(
        [],
        ["Bucket", "Outstanding USD"],
        ...Object.entries(data.buckets).map(([label, value]) => [
          label,
          amount(value),
        ]),
      );
    }
    const url = URL.createObjectURL(
      new Blob(
        [
          "\uFEFF" +
            rows.map((r) => r.map(csvCell).join(",")).join("\r\n") +
            "\r\n",
        ],
        { type: "text/csv;charset=utf-8" },
      ),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = `ledgerdesk-${view.toLowerCase().replaceAll(" ", "-")}-${result.startsOn}-${result.endsOn}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const modeSelector = <div className="report-tabs">
    <button className="secondary" disabled={busy} aria-pressed={mode === "single"} onClick={() => { setMode("single"); setResult(null); }}>Single period reports</button>
    <button className="secondary" disabled={busy} aria-pressed={mode === "compare"} onClick={() => { setMode("compare"); setResult(null); }}>Compare profit</button>
    <button className="secondary" disabled={busy} aria-pressed={mode === "statement"} onClick={() => { setMode("statement"); setResult(null); }}>Customer statements</button>
  </div>;
  if (mode === "statement") return <>{modeSelector}<CustomerStatements busy={busy} customers={customers} workspace={workspace} load={statement} /></>;
  if (mode === "compare") return <>{modeSelector}<ProfitComparison busy={busy} workspace={workspace} load={compare} /></>;
  return (
    <>
      {modeSelector}
      <p className="intro">
        Read the books for a chosen period and inspect what was owed at its end.
        Reports do not post entries or close periods.
      </p>
      <section className="card">
        <h2>Report dates</h2>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            setResult(null);
            setResult(await load(start, end));
          }}
        >
          <label>
            Report start
            <input
              type="date"
              required
              disabled={busy}
              value={start}
              onChange={(e) => {
                setStart(e.target.value);
                setResult(null);
              }}
            />
          </label>
          <label>
            Report end
            <input
              type="date"
              required
              disabled={busy}
              value={end}
              onChange={(e) => {
                setEnd(e.target.value);
                setResult(null);
              }}
            />
          </label>
          <button disabled={busy}>Run reports</button>
        </form>
        <p>
          Profit and loss covers both dates inclusively. The balance sheet,
          trial balance and aging include all posted history through the end
          date.
        </p>
      </section>
      {!result && (
        <p className="empty">
          Choose dates and run reports to view or export results.
        </p>
      )}
      {result && (
        <section className="card report-results">
          <h2>{view}</h2>
          <p>
            <strong>
              Period: {result.startsOn} to {result.endsOn} · As of:{" "}
              {result.endsOn} · USD
            </strong>
          </p>
          <div className="report-tabs">
            {views.map((label) => (
              <button
                key={label}
                className="secondary"
                disabled={busy}
                aria-pressed={view === label}
                onClick={() => setView(label)}
              >
                {label}
              </button>
            ))}
          </div>
          <button className="secondary" disabled={busy} onClick={exportCsv}>
            Export CSV
          </button>
          {view === "Profit and loss" && p && (
            <>
              <AccountTable accounts={p.accounts} />
              <Totals
                rows={[
                  ["Revenue", p.revenue],
                  ["Expenses", p.expenses],
                  ["Net profit", p.netProfit],
                ]}
              />
              <p>
                Payments change cash and outstanding balances; they do not
                recognize income or expense again.
              </p>
            </>
          )}
          {view === "Balance sheet" && b && (
            <>
              <h3>Assets</h3>
              <AccountTable accounts={b.assets} />
              <h3>Liabilities</h3>
              <AccountTable accounts={b.liabilities} />
              {b.equityAccounts.length > 0 && (
                <>
                  <h3>Posted equity</h3>
                  <AccountTable accounts={b.equityAccounts} />
                </>
              )}
              <Totals
                rows={[
                  ["Total assets", b.totalAssets],
                  ["Total liabilities", b.totalLiabilities],
                  ["Posted equity", b.postedEquity],
                  ["Accumulated earnings", b.accumulatedEarnings],
                  ["Total equity", b.totalEquity],
                  ["Liabilities and equity", b.liabilitiesAndEquity],
                  ["Equation difference", b.difference],
                ]}
              />
              <p>
                Accumulated earnings include all income and expenses through{" "}
                {result.endsOn}. Owner contributions and drawings are included
                in posted equity. Opening balance migration and year-end closing
                remain future work.
              </p>
              {cents(b.difference) !== 0n && (
                <p role="alert">
                  The balance sheet does not balance. Inspect the accounting
                  records before using this report.
                </p>
              )}
            </>
          )}
          {view === "Trial balance report" && (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Code</th>
                      <th>Account</th>
                      <th>Debit USD</th>
                      <th>Credit USD</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.trialBalance.accounts.map((a) => (
                      <tr key={a.code}>
                        <td>{a.code}</td>
                        <td>{a.name}</td>
                        <td>{dollars(cents(a.debit))}</td>
                        <td>{dollars(cents(a.credit))}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Totals
                rows={[
                  ["Debit total", result.trialBalance.debits],
                  ["Credit total", result.trialBalance.credits],
                ]}
              />
              <p>
                These are net account balances at the end date, rather than
                gross journal turnover.
              </p>
            </>
          )}
          {(view === "Receivables aging" || view === "Payables aging") &&
            aging && (
              <>
                <Totals
                  rows={[
                    ...Object.entries(aging.buckets),
                    ["Total outstanding", aging.total],
                  ]}
                />
                <div className="table-wrap">
                  <table className="aging-table">
                    <thead>
                      <tr>
                        <th>Reference</th>
                        <th>Party</th>
                        <th>Due</th>
                        <th>Days overdue</th>
                        <th>Bucket</th>
                        <th>Outstanding USD</th>
                      </tr>
                    </thead>
                    <tbody>
                      {aging.items.map((a) => (
                        <tr key={a.id}>
                          <td>{a.reference}</td>
                          <td>{a.party}</td>
                          <td>{a.dueOn}</td>
                          <td>{a.daysOverdue}</td>
                          <td>{a.bucket}</td>
                          <td>{dollars(cents(a.outstanding))}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {!aging.items.length && (
                  <p>No outstanding items at this cutoff.</p>
                )}
                <p>
                  Due today or later is Current. Payments and reversals dated
                  after the cutoff do not change this earlier view.
                </p>
              </>
            )}
          <p>
            Export the displayed results as a CSV file. Report dates are
            included.
          </p>
        </section>
      )}
    </>
  );
}
