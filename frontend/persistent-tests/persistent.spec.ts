import { test, expect } from "@playwright/test";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

test("stored logins, roles and business data survive a real backend restart", async ({
  page,
  request,
}) => {
  test.setTimeout(180000);
  const folder = await mkdtemp(join(tmpdir(), "ledgerdesk-accounts-"));
  const backend = resolve("../backend");
  let server: ChildProcess | null = null;
  let logs = "";
  let spawnError: Error | null = null;
  const owner = "persistent-owner",
    reviewer = "persistent-reviewer";
  const ownerPassword = "owner-first-password",
    reviewerPassword = "reviewer-first-password";
  const auth = (username: string, password: string) => ({
    Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`,
  });
  async function stop() {
    if (!server || server.exitCode !== null || server.signalCode !== null)
      return;
    const process = server;
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        process.kill("SIGKILL");
        reject(new Error("Backend did not stop gracefully."));
      }, 15000);
      process.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
      process.kill("SIGTERM");
    });
  }
  async function start(ownerSecret: string, reviewerSecret: string) {
    logs = "";
    spawnError = null;
    server = spawn(
      "java",
      [
        "-jar",
        "target/ledgerdesk-0.1.0.jar",
        "--spring.profiles.active=demo",
        "--server.port=8091",
        `--spring.datasource.url=jdbc:h2:file:${join(folder, "accounts")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
      ],
      {
        cwd: backend,
        env: {
          ...process.env,
          SPRING_APPLICATION_JSON: JSON.stringify({
            app: {
              accounts: { persistent: true },
              username: owner,
              password: ownerSecret,
              reviewer: { username: reviewer, password: reviewerSecret },
            },
          }),
        },
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    server.on("error", (error) => {
      spawnError = error;
    });
    for (const stream of [server.stdout, server.stderr])
      stream?.on("data", (data) => {
        logs = (logs + data.toString()).slice(-12000);
      });
    await expect
      .poll(
        async () => {
          if (spawnError) throw spawnError;
          if (server!.exitCode !== null)
            throw new Error(`Backend exited before readiness: ${logs}`);
          try {
            return (
              await request.get("http://127.0.0.1:8091/api/csrf", {
                timeout: 1000,
              })
            ).ok();
          } catch {
            return false;
          }
        },
        { timeout: 60000 },
      )
      .toBe(true);
  }
  async function identity(username: string, password: string) {
    return request.get("/api/access", { headers: auth(username, password) });
  }
  try {
    await start(ownerPassword, reviewerPassword);
    const firstOwner = await identity(owner, ownerPassword);
    expect(firstOwner.ok()).toBe(true);
    expect((await firstOwner.json()).canWrite).toBe(true);
    const csrf = await request.get("/api/csrf").then((r) => r.json());
    const vendor = await request.post("/api/vendors", {
      headers: {
        ...auth(owner, ownerPassword),
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": "persistent-vendor",
      },
      data: { name: "Retained supplier", email: "accounts@example.test" },
    });
    expect(vendor.ok()).toBe(true);
    const vendorId = (await vendor.json()).id;
    await stop();
    await start("changed-owner-password", "changed-reviewer-password");
    expect((await identity(owner, "changed-owner-password")).status()).toBe(
      401,
    );
    expect(
      (await identity(reviewer, "changed-reviewer-password")).status(),
    ).toBe(401);
    const storedOwner = await identity(owner, ownerPassword);
    expect(storedOwner.ok()).toBe(true);
    expect((await storedOwner.json()).role).toBe("OWNER");
    const storedReviewer = await identity(reviewer, reviewerPassword);
    expect(storedReviewer.ok()).toBe(true);
    expect((await storedReviewer.json()).canWrite).toBe(false);
    const state = await request
      .get("/api/state", { headers: auth(owner, ownerPassword) })
      .then((r) => r.json());
    expect(
      state.vendors.some(
        (v: { id: string; name: string }) =>
          v.id === vendorId && v.name === "Retained supplier",
      ),
    ).toBe(true);
    const afterCsrf = await request.get("/api/csrf").then((r) => r.json());
    const denied = await request.post("/api/vendors", {
      headers: {
        ...auth(reviewer, reviewerPassword),
        [afterCsrf.headerName]: afterCsrf.token,
        "Idempotency-Key": "blocked-after-restart",
      },
      data: { name: "Blocked supplier", email: "blocked@example.test" },
    });
    expect(denied.status()).toBe(403);
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/");
    await page.getByLabel("Username").fill(reviewer);
    await page.getByLabel("Password", { exact: true }).fill(reviewerPassword);
    await page.getByRole("button", { name: "Open workspace" }).click();
    await expect(
      page.getByRole("heading", { name: "Read-only reviewer workspace" }),
    ).toBeVisible();
    await expect(
      page.getByText(`Signed in as ${reviewer}.`, { exact: false }),
    ).toBeVisible();
    await page.screenshot({
      path: "persistent-results/reviewer-after-restart.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: "Lock workspace" }).click();
    await page.getByLabel("Username").fill(owner);
    await page.getByLabel("Password", { exact: true }).fill(ownerPassword);
    await page.getByRole("button", { name: "Open workspace" }).click();
    await page.getByRole("button", { name: "Vendors", exact: true }).click();
    await expect(
      page.getByText("Retained supplier", { exact: true }),
    ).toBeVisible();
    await page.screenshot({
      path: "persistent-results/owner-data-after-restart.png",
      fullPage: true,
    });
  } finally {
    await stop();
    await rm(folder, { recursive: true, force: true });
  }
});
