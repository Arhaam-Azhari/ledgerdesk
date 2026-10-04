import { useState } from "react";

export function OwnPassword({ busy, save, cancel }: {
  busy: boolean;
  save: (currentPassword: string, password: string) => Promise<boolean>;
  cancel: () => void;
}) {
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  return (
    <section className="card" aria-label="Change your password">
      <h2>Change your password</h2>
      <p>Your workspace will lock after saving. Sign in with the new password.</p>
      <form onSubmit={async (event) => {
        event.preventDefault();
        setError("");
        if (password !== confirmation) {
          setError("The new passwords do not match.");
          return;
        }
        if (new TextEncoder().encode(password).length > 72) {
          setError("The new password must be at most 72 UTF-8 bytes.");
          return;
        }
        await save(current, password);
        // Clear secrets after either result; an uncertain save requires a fresh sign-in.
        setCurrent(""); setPassword(""); setConfirmation("");
      }}>
        <fieldset className="owner-fields" disabled={busy}>
          <label>Current login password
            <input type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
          </label>
          <label>New login password
            <input type="password" autoComplete="new-password" minLength={12} required value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
          <label>Confirm new login password
            <input type="password" autoComplete="new-password" minLength={12} required value={confirmation} onChange={(e) => setConfirmation(e.target.value)} />
          </label>
          <button>Save my password</button>
          <button type="button" className="secondary" onClick={cancel}>Cancel password change</button>
        </fieldset>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      <p>Use at least 12 characters and at most 72 UTF-8 bytes. If the connection fails after saving, lock the workspace and try signing in with your new password.</p>
    </section>
  );
}
