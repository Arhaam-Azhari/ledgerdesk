import { useEffect, useState } from "react";
export type ManagedAccount = {
  id: string;
  username: string;
  role: "OWNER" | "BOOKKEEPER" | "REVIEWER";
  enabled: boolean;
};
type Props = {
  busy: boolean;
  username: string;
  workspace: object;
  load: () => Promise<ManagedAccount[] | null>;
  command: (
    path: string,
    body: object,
    success: string,
    lockAfter?: boolean,
  ) => Promise<boolean>;
};
export function Accounts({ busy, username, workspace, load, command }: Props) {
  const [accounts, setAccounts] = useState<ManagedAccount[] | null>(null);
  const [name, setName] = useState(""),
    [password, setPassword] = useState(""),
    [role, setRole] = useState("REVIEWER");
  useEffect(() => {
    setAccounts(null);
    void load().then(setAccounts);
  }, [workspace]);
  return (
    <>
      <p className="intro">
        Manage access to this business. Owners can post and manage accounts;
        bookkeepers handle routine accounting; reviewers can inspect reports and ledger activity. Keep at least one
        enabled owner.
      </p>
      <section className="card">
        <h2>Create account</h2>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            if (
              await command(
                "/api/accounts",
                { username: name, password, role },
                "Account created.",
              )
            ) {
              setName("");
              setPassword("");
            }
          }}
        >
          <fieldset className="owner-fields" disabled={busy}>
            <label>
              New account username
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoComplete="off"
                maxLength={100}
                required
              />
            </label>
            <label>
              New account password
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
                minLength={12}
                required
              />
            </label>
            <label>
              New account role
              <select aria-label="New account role" value={role} onChange={(e) => setRole(e.target.value)}>
                <option value="REVIEWER">Reviewer · read only</option>
                <option value="BOOKKEEPER">Bookkeeper · routine accounting</option>
                <option value="OWNER">
                  Owner · posting and administration
                </option>
              </select>
            </label>
            <button>Create account</button>
          </fieldset>
        </form>
        <p>
          Passwords need at least 12 characters and at most 72 UTF-8 bytes.
          Share credentials outside this app. Existing usernames cannot be
          overwritten.
        </p>
      </section>
      <section className="card">
        <h2>Business accounts</h2>
        <button
          className="secondary"
          disabled={busy}
          onClick={async () => {
            setAccounts(null);
            setAccounts(await load());
          }}
        >
          Reload accounts
        </button>
        {!accounts && (
          <p>
            Account list is unavailable or loading. Reload before making another
            change.
          </p>
        )}
        {accounts?.map((account) => (
          <AccountRow
            key={`${account.id}-${account.role}-${account.enabled}`}
            account={account}
            own={account.username === username}
            busy={busy}
            command={command}
          />
        ))}
      </section>
    </>
  );
}
function AccountRow({
  account,
  own,
  busy,
  command,
}: {
  account: ManagedAccount;
  own: boolean;
  busy: boolean;
  command: Props["command"];
}) {
  const [role, setRole] = useState(account.role),
    [enabled, setEnabled] = useState(account.enabled);
  const [reset, setReset] = useState(false),
    [password, setPassword] = useState(""),
    [current, setCurrent] = useState("");
  return (
    <article className="adjustment-record">
      <h3>
        {account.username}
        {own ? " · Your account" : ""}
      </h3>
      <p>
        {account.role} · {account.enabled ? "Enabled" : "Disabled"}
      </p>
      {own ? (
        <p>
          Ask another enabled owner to change your role or disable your account.
        </p>
      ) : (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            await command(
              `/api/accounts/${account.id}/access`,
              { role, enabled },
              "Account access saved.",
            );
          }}
        >
          <fieldset className="owner-fields" disabled={busy}>
            <label>
              Role for {account.username}
              <select
                aria-label={`Role for ${account.username}`}
                value={role}
                onChange={(e) =>
                  setRole(e.target.value as ManagedAccount["role"])
                }
              >
                <option value="REVIEWER">Reviewer</option>
                <option value="BOOKKEEPER">Bookkeeper</option>
                <option value="OWNER">Owner</option>
              </select>
            </label>
            <label>
              Enabled for {account.username}
              <input
                type="checkbox"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
              />
            </label>
            <button>Save access for {account.username}</button>
          </fieldset>
        </form>
      )}
      {!reset ? (
        <button
          className="secondary"
          disabled={busy}
          onClick={() => setReset(true)}
        >
          Change password for {account.username}
        </button>
      ) : (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            if (
              await command(
                `/api/accounts/${account.id}/password`,
                { password, ...(own ? { currentPassword: current } : {}) },
                "Password changed.",
                own,
              )
            ) {
              setPassword("");
              setCurrent("");
              setReset(false);
            }
          }}
        >
          <fieldset className="owner-fields" disabled={busy}>
            {own && (
              <label>
                Your current password
                <input
                  type="password"
                  value={current}
                  onChange={(e) => setCurrent(e.target.value)}
                  autoComplete="current-password"
                  required
                />
              </label>
            )}
            <label>
              Replacement password for {account.username}
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
                minLength={12}
                required
              />
            </label>
            <button>Confirm password change for {account.username}</button>
            <button
              type="button"
              className="secondary"
              onClick={() => {
                setReset(false);
                setPassword("");
                setCurrent("");
              }}
            >
              Cancel password change
            </button>
          </fieldset>
          <p>
            {own
              ? "Your workspace will lock after saving. Sign in with the new password."
              : "The old password stops working. Share the replacement outside this app."}
          </p>
        </form>
      )}
    </article>
  );
}
