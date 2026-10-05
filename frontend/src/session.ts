export type AuthMode = "basic" | "session";

export async function authMode(): Promise<AuthMode> {
  const response = await fetch("/api/auth", { credentials: "same-origin", cache: "no-store" });
  if (!response.ok) throw new Error("Could not connect to the login service. Try again.");
  const result = await response.json();
  if (result.mode !== "basic" && result.mode !== "session") throw new Error("The login mode was not recognized.");
  return result.mode;
}

export async function sessionAction(path: "/api/session/login" | "/api/session/logout", form?: URLSearchParams) {
  const tokenResponse = await fetch("/api/csrf", { credentials: "same-origin", cache: "no-store" });
  if (!tokenResponse.ok) throw new Error("Could not obtain a request token. Try again.");
  const csrf = await tokenResponse.json();
  const response = await fetch(path, {
    method: "POST", credentials: "same-origin", cache: "no-store",
    headers: { [csrf.headerName]: csrf.token, ...(form ? { "Content-Type": "application/x-www-form-urlencoded" } : {}) },
    body: form,
  });
  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    throw new Error(result.message || "Could not confirm the login action. Try again.");
  }
}
