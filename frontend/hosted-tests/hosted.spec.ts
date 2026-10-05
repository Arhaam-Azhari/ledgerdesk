import { readFileSync } from "node:fs";
import { test, expect } from "@playwright/test";

test("hosted browser signs in over HTTPS and keeps its workspace after reload", async ({ page, context }) => {
  const username = process.env.LEDGERDESK_OWNER;
  if (!username) throw new Error("Set LEDGERDESK_OWNER for the disposable hosted check.");
  const password = readFileSync("../deploy/secrets/owner-password", "utf8").trim();
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeEnabled();
  await page.getByLabel("Username", { exact: true }).fill(username);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
  const session = (await context.cookies()).find(cookie => cookie.name === "JSESSIONID");
  expect(session).toMatchObject({ secure: true, httpOnly: true, sameSite: "Strict" });
  await page.reload();
  await expect(page.getByRole("heading", { name: "Overview", exact: true })).toBeVisible();
  await page.screenshot({ path: "hosted-results/hosted-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "hosted-results/hosted-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeVisible();
  expect((await page.request.get("/api/state")).status()).toBe(401);
});
