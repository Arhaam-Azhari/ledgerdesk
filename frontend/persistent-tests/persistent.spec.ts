import { test, expect } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

test("stored accounts and accounting data survive restart, recovery and backup restore", async ({
  page,
  request,
}) => {
  test.setTimeout(240000);
  const folder = await mkdtemp(join(tmpdir(), "ledgerdesk-accounts-"));
  const backend = resolve("../backend");
  let database = join(folder, "accounts");
  let server: ChildProcess | null = null;
  let logs = "";
  let spawnError: Error | null = null;
  const owner = "persistent-owner",
    reviewer = "persistent-reviewer",
    bookkeeper = "persistent-bookkeeper";
  let ownerPassword = "owner-first-password",
    reviewerPassword = "reviewer-first-password",
    bookkeeperPassword = "bookkeeper-first-password";
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
        `--spring.datasource.url=jdbc:h2:file:${database};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
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
    const accountResponse = await request.post("/api/accounts", {
      headers: { ...auth(owner, ownerPassword), [csrf.headerName]: csrf.token },
      data: { username: bookkeeper, password: bookkeeperPassword, role: "BOOKKEEPER" },
    });
    expect(accountResponse.ok()).toBe(true);
    const bookkeeperId = (await accountResponse.json()).id as string;
    const initialLogins = [
      { username: owner, initial: ownerPassword, replacement: "self-changed-owner-password", role: "OWNER" },
      { username: reviewer, initial: reviewerPassword, replacement: "self-changed-reviewer-password", role: "REVIEWER" },
      { username: bookkeeper, initial: bookkeeperPassword, replacement: "self-changed-bookkeeper-password", role: "BOOKKEEPER" },
    ];
    const beforePasswordChanges = await request.get("/api/state", { headers: auth(owner, ownerPassword) }).then((r) => r.json());
    for (const login of initialLogins) {
      const changed = await request.post("/api/me/password", {
        headers: { ...auth(login.username, login.initial), [csrf.headerName]: csrf.token },
        data: { currentPassword: login.initial, password: login.replacement },
      });
      expect(changed.ok()).toBe(true);
      expect((await identity(login.username, login.initial)).status()).toBe(401);
      const current = await identity(login.username, login.replacement);
      expect(current.ok()).toBe(true);
      expect((await current.json()).role).toBe(login.role);
    }
    ownerPassword = initialLogins[0].replacement;
    reviewerPassword = initialLogins[1].replacement;
    bookkeeperPassword = initialLogins[2].replacement;
    const afterPasswordChanges = await request.get("/api/state", { headers: auth(owner, ownerPassword) }).then((r) => r.json());
    expect(afterPasswordChanges.ledger).toEqual(beforePasswordChanges.ledger);
    for (const login of initialLogins) {
      expect(afterPasswordChanges.audit.filter((row: { actor: string; action: string }) => row.actor === login.username && row.action === "ACCOUNT_SELF_PASSWORD_CHANGED")).toHaveLength(1);
      expect(JSON.stringify(afterPasswordChanges)).not.toContain(login.initial);
      expect(JSON.stringify(afterPasswordChanges)).not.toContain(login.replacement);
    }
    async function retainedLogins(ownerSecret: string) {
      for (const login of initialLogins) {
        expect((await identity(login.username, login.initial)).status()).toBe(401);
        const retained = await identity(login.username, login.username === owner ? ownerSecret : login.replacement);
        expect(retained.ok()).toBe(true);
        expect((await retained.json()).role).toBe(login.role);
      }
    }
    const accountsBefore = await request.get("/api/accounts", {
      headers: auth(owner, ownerPassword),
    }).then((r) => r.json());
    const vendor = await request.post("/api/vendors", {
      headers: {
        ...auth(bookkeeper, bookkeeperPassword),
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": "persistent-vendor",
      },
      data: { name: "Retained supplier", email: "accounts@example.test" },
    });
    expect(vendor.ok()).toBe(true);
    const vendorId = (await vendor.json()).id;
    const opening = {
      asOf: "2026-09-30",
      balance: "1000.25",
      memo: "Cleared opening retained in local backup",
    };
    const openingResponse = await request.post("/api/opening-bank-balance", {
      headers: {
        ...auth(owner, ownerPassword),
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": "backup-bank-opening",
      },
      data: opening,
    });
    expect(openingResponse.ok()).toBe(true);
    const openingId = (await openingResponse.json()).id;
    const transfer = await request.post("/api/equity", {
      headers: {
        ...auth(owner, ownerPassword),
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": "backup-owner-funding",
      },
      data: {
        kind: "CONTRIBUTION",
        postedOn: "2026-10-01",
        memo: "Funds retained in backup",
        amount: "125.37",
      },
    });
    expect(transfer.ok()).toBe(true);
    async function postDocument(path: string, data: object, key: string) {
      const response = await request.post(path, {
        headers: {
          ...auth(owner, ownerPassword),
          [csrf.headerName]: csrf.token,
          "Idempotency-Key": key,
        },
        data,
      });
      expect(response.ok()).toBe(true);
      return (await response.json()).id as string;
    }
    const billId = await postDocument(
      "/api/bills",
      {
        vendorId,
        reference: "RESTORE-40",
        description: "Supplies supported by a receipt",
        issuedOn: "2026-10-01",
        dueOn: "2026-10-15",
        accountCode: "5000",
        amount: "40.00",
      },
      "restore-bill",
    );
    const expenseId = await postDocument(
      "/api/expenses",
      {
        vendorId,
        description: "Software supported by a receipt",
        spentOn: "2026-10-01",
        accountCode: "5100",
        amount: "25.00",
      },
      "restore-expense",
    );
    const attachments = [];
    for (const item of [
      {
        path: `/api/bills/${billId}/receipts`,
        name: "supply-receipt.png",
        mimeType: "image/png",
        key: "restore-bill-receipt",
      },
      {
        path: `/api/expenses/${expenseId}/receipts`,
        name: "software-receipt.jpg",
        mimeType: "image/jpeg",
        key: "restore-expense-receipt",
      },
      {
        path: `/api/expenses/${expenseId}/receipts`,
        name: "software-receipt.pdf",
        mimeType: "application/pdf",
        key: "restore-pdf-receipt",
      },
    ]) {
      const buffer = await readFile(resolve("tests/fixtures", item.name));
      const response = await request.post(item.path, {
        headers: {
          ...auth(owner, ownerPassword),
          [csrf.headerName]: csrf.token,
          "Idempotency-Key": item.key,
        },
        multipart: {
          file: { name: item.name, mimeType: item.mimeType, buffer },
        },
      });
      expect(response.ok()).toBe(true);
      const id = (await response.json()).id as string;
      const download = await request.get(`/api/receipts/${id}`, {
        headers: auth(owner, ownerPassword),
      });
      expect(download.ok()).toBe(true);
      if (item.mimeType === "application/pdf")
        expect(await download.body()).toEqual(buffer);
      // Compare the stored download: image validation can rewrite uploaded bytes.
      attachments.push({
        ...item,
        buffer,
        id,
        stored: await download.body(),
        headers: download.headers(),
      });
    }

    const statement = {
      startsOn: "2026-10-01",
      endsOn: "2026-10-31",
      openingBalance: "1000.25",
      closingBalance: "1000.25",
    };
    const bankId = await postDocument(
      "/api/bank/reconciliations",
      statement,
      "backup-statement",
    );
    const period = {
      endsOn: "2026-10-31",
      reviewNote: "Reviewed reports before local backup",
    };
    const firstPeriodId = await postDocument(
      "/api/accounting-periods",
      period,
      "backup-period-first",
    );
    const reopening = { version: 1, reason: "Second review before backup" };
    await postDocument(
      `/api/accounting-periods/${firstPeriodId}/reopen`,
      reopening,
      "backup-period-first-reopen",
    );
    const activePeriodId = await postDocument(
      "/api/accounting-periods",
      period,
      "backup-period-active",
    );
    const cashPath =
      "/api/reports/cash-activity?startsOn=2026-10-01&endsOn=2026-10-31";
    const reportsPath = "/api/reports?startsOn=2026-10-01&endsOn=2026-10-31";
    const cashBefore = await request
      .get(cashPath, { headers: auth(owner, ownerPassword) })
      .then((r) => r.json());
    expect(cashBefore).toMatchObject({
      openingCash: "1000.25",
      closingCash: "1100.62",
      receipts: "125.37",
      payments: "25.00",
    });
    const reportsBefore = await request
      .get(reportsPath, { headers: auth(owner, ownerPassword) })
      .then((r) => r.json());
    expect(reportsBefore.profitLoss.netProfit).toBe("-65.00");
    expect(reportsBefore.balanceSheet).toMatchObject({
      totalAssets: "1100.62",
      totalEquity: "1060.62",
      totalLiabilities: "40.00",
      difference: "0.00",
    });
    await stop();
    await start("changed-owner-password", "changed-reviewer-password");
    await retainedLogins(ownerPassword);
    expect((await identity(owner, "changed-owner-password")).status()).toBe(
      401,
    );
    expect(
      (await identity(reviewer, "changed-reviewer-password")).status(),
    ).toBe(401);
    const restartedBookkeeper = await identity(bookkeeper, bookkeeperPassword);
    expect(restartedBookkeeper.ok()).toBe(true);
    expect(await restartedBookkeeper.json()).toMatchObject({ role: "BOOKKEEPER", canWrite: true });
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
    await stop();
    let recoveredPassword = "offline-recovered-password";
    const recoveryProcess = spawn(
      "java",
      [
        "-jar",
        "target/ledgerdesk-0.1.0.jar",
        "--spring.profiles.active=demo",
        "--spring.main.web-application-type=none",
        "--app.accounts.persistent=true",
        "--app.recovery.enabled=true",
        `--spring.datasource.url=jdbc:h2:file:${database};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
      ],
      {
        cwd: backend,
        env: { ...process.env },
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
    server = recoveryProcess;
    let recoveryLog = "";
    for (const stream of [recoveryProcess.stdout, recoveryProcess.stderr])
      stream?.on("data", (chunk) => {
        recoveryLog += chunk.toString();
      });
    const recoveryExit = new Promise<number | null>((resolve, reject) => {
      const timer = setTimeout(() => {
        recoveryProcess.kill("SIGKILL");
        reject(new Error("Recovery command timed out."));
      }, 60000);
      recoveryProcess.once("error", (error) => {
        clearTimeout(timer);
        reject(error);
      });
      recoveryProcess.once("exit", (code) => {
        clearTimeout(timer);
        resolve(code);
      });
    });
    recoveryProcess.stdin!.end(
      `${owner}\n${recoveredPassword}\nForgotten owner password in isolated test\n`,
    );
    expect(await recoveryExit, recoveryLog).toBe(0);
    expect(recoveryLog).toContain("Owner access recovered");
    expect(recoveryLog).not.toContain(recoveredPassword);
    await start("changed-owner-password", "changed-reviewer-password");
    await retainedLogins(recoveredPassword);
    expect((await identity(owner, ownerPassword)).status()).toBe(401);
    const recovered = await identity(owner, recoveredPassword);
    expect(recovered.ok()).toBe(true);
    expect((await recovered.json()).canWrite).toBe(true);
    // Keep offline recovery in this scenario, then back up a password changed by the owner.
    const recoveredCsrf = await request.get("/api/csrf").then((r) => r.json());
    const finalOwnerPassword = "self-changed-recovered-owner-password";
    const finalOwnerChange = await request.post("/api/me/password", {
      headers: { ...auth(owner, recoveredPassword), [recoveredCsrf.headerName]: recoveredCsrf.token },
      data: { currentPassword: recoveredPassword, password: finalOwnerPassword },
    });
    expect(finalOwnerChange.ok()).toBe(true);
    expect((await identity(owner, recoveredPassword)).status()).toBe(401);
    recoveredPassword = finalOwnerPassword;
    await retainedLogins(recoveredPassword);
    const recoveredState = await request
      .get("/api/state", { headers: auth(owner, recoveredPassword) })
      .then((r) => r.json());
    expect(
      recoveredState.vendors.some((v: { id: string }) => v.id === vendorId),
    ).toBe(true);
    expect(
      recoveredState.audit.some(
        (a: { action: string }) => a.action === "ACCOUNT_OWNER_RECOVERED",
      ),
    ).toBe(true);
    expect(recoveredState.equityTransactions).toHaveLength(1);
    expect(recoveredState.ledger).toHaveLength(8);
    expect(recoveredState.openingBankBalances).toHaveLength(1);
    expect(recoveredState.bankReconciliations).toHaveLength(1);
    expect(recoveredState.receipts).toHaveLength(3);
    expect(recoveredState.accountingPeriodCloses).toHaveLength(2);
    await stop();
    const backupTool = resolve("../scripts/local_backup.py");
    const backupFolder = join(folder, "saved-backup");
    execFileSync("python3", [
      backupTool,
      "backup",
      `${database}.mv.db`,
      backupFolder,
      "--confirm-stopped",
    ]);
    database = join(folder, "restored");
    execFileSync("python3", [
      backupTool,
      "restore",
      backupFolder,
      `${database}.mv.db`,
      "--confirm-stopped",
    ]);
    // Opening a separate restored file proves more than reopening the original.
    await start("changed-owner-password", "changed-reviewer-password");
    await retainedLogins(recoveredPassword);
    expect((await identity(owner, ownerPassword)).status()).toBe(401);
    expect((await identity(owner, recoveredPassword)).ok()).toBe(true);
    const restoredReviewer = await identity(reviewer, reviewerPassword);
    expect(restoredReviewer.ok()).toBe(true);
    expect((await restoredReviewer.json()).canWrite).toBe(false);
    const restoredState = await request
      .get("/api/state", { headers: auth(owner, recoveredPassword) })
      .then((r) => r.json());
    expect(restoredState).toEqual(recoveredState);
    expect((await identity(owner, "offline-recovered-password")).status()).toBe(401);
    expect(JSON.stringify(restoredState)).not.toContain(recoveredPassword);
    const restoredBookkeeper = await identity(bookkeeper, bookkeeperPassword);
    expect(restoredBookkeeper.ok()).toBe(true);
    expect(await restoredBookkeeper.json()).toMatchObject({ role: "BOOKKEEPER", canWrite: true });
    expect((await identity(bookkeeper, "wrong-bookkeeper-password")).status()).toBe(401);
    expect(await request.get("/api/accounts", { headers: auth(owner, recoveredPassword) }).then((r) => r.json())).toEqual(accountsBefore);
    expect((await request.get("/api/accounts", { headers: auth(bookkeeper, bookkeeperPassword) })).status()).toBe(403);
    expect(await request.get("/api/state", { headers: auth(bookkeeper, bookkeeperPassword) }).then((r) => r.json())).toEqual(recoveredState);
    const bookkeeperCsrf = await request.get("/api/csrf").then((r) => r.json());
    async function bookkeeperPost(path: string, data: object, key: string) {
      return request.post(path, {
        headers: { ...auth(bookkeeper, bookkeeperPassword), [bookkeeperCsrf.headerName]: bookkeeperCsrf.token, "Idempotency-Key": key },
        data,
      });
    }
    const vendorRetry = await bookkeeperPost("/api/vendors", { name: "Retained supplier", email: "accounts@example.test" }, "persistent-vendor");
    expect(vendorRetry.ok()).toBe(true);
    expect((await vendorRetry.json()).id).toBe(vendorId);
    for (const path of ["/api/accounts", "/api/opening-bank-balance", "/api/equity", "/api/accounting-periods", `/api/bank/reconciliations/${bankId}/reopen`])
      expect((await bookkeeperPost(path, {}, "bookkeeper-blocked")).status()).toBe(403);
    expect((await bookkeeperPost("/api/expenses", { vendorId, description: "Closed-date bookkeeper expense", spentOn: "2026-10-15", accountCode: "5100", amount: "1.00" }, "bookkeeper-closed")).status()).toBe(400);
    expect(await request.get("/api/state", { headers: auth(owner, recoveredPassword) }).then((r) => r.json())).toEqual(recoveredState);
    for (const receipt of attachments) {
      for (const credentials of [
        auth(owner, recoveredPassword),
        auth(reviewer, reviewerPassword),
        auth(bookkeeper, bookkeeperPassword),
      ]) {
        const download = await request.get(`/api/receipts/${receipt.id}`, {
          headers: credentials,
        });
        expect(download.ok()).toBe(true);
        expect(await download.body()).toEqual(receipt.stored);
        for (const header of [
          "content-type",
          "content-disposition",
          "x-content-type-options",
          "cache-control",
        ])
          expect(download.headers()[header]).toBe(receipt.headers[header]);
      }
      expect((await request.get(`/api/receipts/${receipt.id}`)).status()).toBe(
        401,
      );
      const receiptCsrf = await request.get("/api/csrf").then((r) => r.json());
      const upload = {
        file: {
          name: receipt.name,
          mimeType: receipt.mimeType,
          buffer: receipt.buffer,
        },
      };
      const retry = await request.post(receipt.path, {
        headers: {
          ...auth(owner, recoveredPassword),
          [receiptCsrf.headerName]: receiptCsrf.token,
          "Idempotency-Key": receipt.key,
        },
        multipart: upload,
      });
      expect(retry.ok()).toBe(true);
      expect((await retry.json()).id).toBe(receipt.id);
      const denied = await request.post(receipt.path, {
        headers: {
          ...auth(reviewer, reviewerPassword),
          [receiptCsrf.headerName]: receiptCsrf.token,
          "Idempotency-Key": `blocked-${receipt.key}`,
        },
        multipart: upload,
      });
      expect(denied.status()).toBe(403);
    }
    expect(
      await request
        .get("/api/state", { headers: auth(owner, recoveredPassword) })
        .then((r) => r.json()),
    ).toEqual(recoveredState);

    const restoredCsrf = await request.get("/api/csrf").then((r) => r.json());
    const retry = await request.post("/api/equity", {
      headers: {
        ...auth(owner, recoveredPassword),
        [restoredCsrf.headerName]: restoredCsrf.token,
        "Idempotency-Key": "backup-owner-funding",
      },
      data: {
        kind: "CONTRIBUTION",
        postedOn: "2026-10-01",
        memo: "Funds retained in backup",
        amount: "125.37",
      },
    });
    expect(retry.ok()).toBe(true);
    async function restoredPost(
      path: string,
      data: object,
      key: string,
      asReviewer = false,
    ) {
      return request.post(path, {
        headers: {
          ...auth(
            asReviewer ? reviewer : owner,
            asReviewer ? reviewerPassword : recoveredPassword,
          ),
          [restoredCsrf.headerName]: restoredCsrf.token,
          "Idempotency-Key": key,
        },
        data,
      });
    }
    const openingRetry = await restoredPost(
      "/api/opening-bank-balance",
      opening,
      "backup-bank-opening",
    );
    expect(openingRetry.ok()).toBe(true);
    expect((await openingRetry.json()).id).toBe(openingId);
    expect(
      (
        await restoredPost(
          "/api/opening-bank-balance",
          opening,
          "second-opening",
        )
      ).status(),
    ).toBe(400);
    expect(
      (
        await restoredPost(
          "/api/opening-bank-balance",
          opening,
          "reviewer-opening",
          true,
        )
      ).status(),
    ).toBe(403);
    expect(
      (
        await restoredPost(
          "/api/equity",
          {
            kind: "CONTRIBUTION",
            postedOn: "2026-09-30",
            memo: "Before cutover",
            amount: "1.00",
          },
          "cutover-post",
        )
      ).status(),
    ).toBe(400);
    expect(
      (
        await request.get(
          "/api/reports/cash-activity?startsOn=2026-09-30&endsOn=2026-10-31",
          { headers: auth(owner, recoveredPassword) },
        )
      ).status(),
    ).toBe(400);
    expect(
      await request
        .get(cashPath, { headers: auth(owner, recoveredPassword) })
        .then((r) => r.json()),
    ).toEqual(cashBefore);
    expect(
      await request
        .get(reportsPath, { headers: auth(owner, recoveredPassword) })
        .then((r) => r.json()),
    ).toEqual(reportsBefore);
    const nextPreview = await restoredPost(
      "/api/bank/reconciliations/preview",
      { ...statement, startsOn: "2026-11-01", endsOn: "2026-11-30" },
      "restored-preview",
    );
    expect(nextPreview.ok()).toBe(true);
    expect(await nextPreview.json()).toMatchObject({
      bookDifference: "0.00",
      outstandingDeposits: "125.37",
      outstandingPayments: "25.00",
    });
    expect(
      await request
        .get("/api/state", { headers: auth(owner, recoveredPassword) })
        .then((r) => r.json()),
    ).toEqual(recoveredState);
    for (const [key, expected] of [
      ["backup-period-first", firstPeriodId],
      ["backup-period-active", activePeriodId],
    ]) {
      const response = await restoredPost(
        "/api/accounting-periods",
        period,
        key,
      );
      expect(response.ok()).toBe(true);
      expect((await response.json()).id).toBe(expected);
    }
    const originalReopen = await restoredPost(
      `/api/accounting-periods/${firstPeriodId}/reopen`,
      reopening,
      "backup-period-first-reopen",
    );
    expect(originalReopen.ok()).toBe(true);
    expect((await originalReopen.json()).id).toBe(firstPeriodId);
    expect(
      (
        await restoredPost(
          "/api/accounting-periods",
          period,
          "duplicate-period",
        )
      ).status(),
    ).toBe(400);
    expect(
      (
        await restoredPost(
          `/api/accounting-periods/${activePeriodId}/reopen`,
          reopening,
          "reviewer-period",
          true,
        )
      ).status(),
    ).toBe(403);
    expect(
      (
        await restoredPost(
          `/api/bank/reconciliations/${bankId}/reopen`,
          reopening,
          "protected-bank",
        )
      ).status(),
    ).toBe(400);
    expect(
      (
        await restoredPost(
          "/api/equity",
          {
            kind: "CONTRIBUTION",
            postedOn: "2026-10-15",
            memo: "Closed accounting date",
            amount: "1.00",
          },
          "protected-period",
        )
      ).status(),
    ).toBe(400);
    const reviewerHistory = await request.get("/api/accounting-periods", {
      headers: auth(reviewer, reviewerPassword),
    });
    expect(reviewerHistory.ok()).toBe(true);
    expect((await reviewerHistory.json()).accountingPeriodCloses).toEqual(
      recoveredState.accountingPeriodCloses,
    );
    expect(
      await request
        .get("/api/state", { headers: auth(owner, recoveredPassword) })
        .then((r) => r.json()),
    ).toEqual(recoveredState);
    const reopenPath = `/api/accounting-periods/${activePeriodId}/reopen`;
    expect(
      (
        await restoredPost(reopenPath, reopening, "restored-period-reopen")
      ).ok(),
    ).toBe(true);
    const afterReopen = await request
      .get("/api/state", { headers: auth(owner, recoveredPassword) })
      .then((r) => r.json());
    const repeatedReopen = await restoredPost(
      reopenPath,
      reopening,
      "restored-period-reopen",
    );
    expect(repeatedReopen.ok()).toBe(true);
    expect((await repeatedReopen.json()).id).toBe(activePeriodId);
    expect(
      (
        await restoredPost(
          "/api/accounting-periods",
          period,
          "backup-period-active",
        )
      ).ok(),
    ).toBe(true);
    expect(
      await request
        .get("/api/state", { headers: auth(owner, recoveredPassword) })
        .then((r) => r.json()),
    ).toEqual(afterReopen);
    expect(afterReopen.ledger).toEqual(recoveredState.ledger);
    for (const old of recoveredState.accountingPeriodCloses) {
      const current = afterReopen.accountingPeriodCloses.find(
        (row: { id: string }) => row.id === old.id,
      );
      expect(current.snapshot).toBe(old.snapshot);
      expect(current.status).toBe("REOPENED");
    }
    expect(
      (
        await request
          .get("/api/state", { headers: auth(owner, recoveredPassword) })
          .then((r) => r.json())
      ).equityTransactions,
    ).toHaveLength(1);
    const newVendor = { name: "Post-restore bookkeeper supplier", email: "new@example.test" };
    const added = await bookkeeperPost("/api/vendors", newVendor, "bookkeeper-new");
    expect(added.ok()).toBe(true);
    const addedId = (await added.json()).id;
    const afterNew = await request.get("/api/state", { headers: auth(owner, recoveredPassword) }).then((r) => r.json());
    const repeated = await bookkeeperPost("/api/vendors", newVendor, "bookkeeper-new");
    expect(repeated.ok()).toBe(true);
    expect((await repeated.json()).id).toBe(addedId);
    expect(await request.get("/api/state", { headers: auth(owner, recoveredPassword) }).then((r) => r.json())).toEqual(afterNew);
    expect(afterNew.ledger).toEqual(recoveredState.ledger);
    expect(afterNew.audit.some((row: { actor: string; record_id: string }) => row.actor === bookkeeper && row.record_id === addedId)).toBe(true);
    expect((await restoredPost(`/api/accounts/${bookkeeperId}/access`, { role: "REVIEWER", enabled: true }, "bookkeeper-demote")).ok()).toBe(true);
    expect((await bookkeeperPost("/api/vendors", newVendor, "bookkeeper-demoted")).status()).toBe(403);
    expect((await restoredPost(`/api/accounts/${bookkeeperId}/access`, { role: "BOOKKEEPER", enabled: false }, "bookkeeper-disable")).ok()).toBe(true);
    expect((await identity(bookkeeper, bookkeeperPassword)).status()).toBe(401);
  } finally {
    await stop();
    await rm(folder, { recursive: true, force: true });
  }
});
