// Optional developer test only: PLAYWRIGHT_MODULE=/path/to/playwright node scripts/browser-smoke.mjs
// No Node dependency is needed to build or run the product.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const work = await mkdtemp(join(tmpdir(), "tactical-browser-"));
const bindAddress = process.env.BROWSER_BIND_ADDRESS || "127.0.0.1";
const server = spawn(
  "java",
  [
    "-Djava.net.preferIPv4Stack=true",
    "-jar",
    join(root, "tactical-server/target/tactical-server-0.1.0-SNAPSHOT.jar"),
    "--server.port=0",
    `--server.address=${bindAddress}`,
  ],
  { cwd: work },
);
let log = "",
  browser;
server.stdout.on("data", (b) => (log += b));
server.stderr.on("data", (b) => (log += b));
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
try {
  for (let i = 0; i < 300 && !/Tomcat started on port (\d+)/.test(log); i++) {
    if (server.exitCode !== null) throw new Error("Server failed to start");
    await pause(100);
  }
  const port = log.match(/Tomcat started on port (\d+)/)?.[1];
  assert.ok(port, "startup timeout");
  const base = `http://${bindAddress}:${port}`,
    token = await readFile(join(work, ".runtime/session.token"), "utf8");
  const http = async (path, method = "GET", body) => {
    const r = await fetch(base + "/api/v1" + path, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    assert.ok(r.ok, await r.clone().text());
    return r.json();
  };
  browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
  const page = await browser.newPage({
    viewport: { width: 1980, height: 1080 },
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(base);
  await page.locator("#token").fill(token);
  await page.locator("#auth-form button").first().click();
  await page.waitForFunction(() =>
    document.getElementById("connection").textContent.includes("已连接"),
  );
  const clickAndStatus = async (selector, text) => {
    await page.locator(selector).click();
    await page.waitForFunction(
      (t) => document.getElementById("status").textContent.includes(t),
      text,
    );
  };
  await clickAndStatus("#experiments-tab", "就绪");
  await clickAndStatus("#experiment-preset", "河谷配对输入已冻结");
  await page.locator("#experiment-threshold-b").fill("45");
  await clickAndStatus("#experiment-start", "配对实验已提交");
  await page.waitForFunction(() =>
    document
      .getElementById("experiment-progress")
      .textContent.startsWith("已完成"),
  );
  const id = await page.locator("#experiment-list").inputValue();
  const summary = await http(`/experiments/${id}`);
  assert.equal(summary.completed, 8);
  assert.equal(summary.paired.count, 4);
  assert.equal(
    summary.rows
      .filter((r) => r.variant === "A")
      .every((r) => r.dependencyDisorders === 0),
    true,
  );
  assert.ok(
    summary.rows.some((r) => r.variant === "B" && r.dependencyDisorders > 0),
  );
  const archive = await http(`/experiments/${id}/export`);
  assert.equal(
    archive.request.b.days[0].blue.find((o) => o.orderId === "cross").doctrine
      .withdrawBelowPercent,
    45,
  );
  assert.equal(
    archive.runs.every((r) => r.status === "COMPLETED"),
    true,
  );
  await page.locator("#experiment-next").click();
  assert.equal(await page.locator("#experiment-rows tr").count(), 2);
  await page.locator("#experiment-prev").click();
  await page.locator("#experiment-rows tr button").first().click();
  await page.waitForSelector("#experiment-detail-dialog[open]");
  assert.ok(
    (await page.locator("#experiment-detail").textContent()).includes(
      "blue-armor",
    ),
  );
  assert.ok(
    (await page.locator("#experiment-detail").textContent()).includes("FAILED"),
  );
  await page.locator('[data-close="experiment-detail-dialog"]').click();
  for (const viewport of [
    { width: 1980, height: 1080 },
    { width: 1920, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(viewport);
    const overflow = await page.evaluate(() =>
      [
        document.documentElement,
        ...document.querySelectorAll(
          "#experiments-view, .experiment-config, .paired-results, .experiment-table",
        ),
      ]
        .filter(
          (e) =>
            e.scrollHeight > e.clientHeight + 2 ||
            e.scrollWidth > e.clientWidth + 2,
        )
        .map((e) => ({
          tag: e.className || e.tagName,
          w: e.clientWidth,
          sw: e.scrollWidth,
          h: e.clientHeight,
          sh: e.scrollHeight,
        })),
    );
    assert.deepEqual(overflow, [], JSON.stringify(viewport));
  }
  await page.screenshot({
    path: join(root, "tactical-server/target/m6-experiments.png"),
  });
  await page
    .locator("#experiment-rows tr")
    .first()
    .getByText("回放", { exact: true })
    .click();
  await page.waitForFunction(() =>
    document.getElementById("status").textContent.includes("独立历史回放"),
  );
  assert.equal(await page.locator("#replay-exit").isVisible(), true);
  await page.locator("#experiments-tab").click();
  await page.waitForSelector("#experiments-view:not([hidden])");
  await page.locator("#experiment-advanced").click();
  await page.waitForSelector("#experiment-advanced-dialog[open]");
  const advanced = JSON.parse(
    await page.locator("#experiment-json").inputValue(),
  );
  advanced.seeds = [42];
  advanced.a.days.push({
    blue: [],
    red: [],
    blueOperation: null,
    redOperation: null,
  });
  advanced.b.days.push({
    blue: [],
    red: [],
    blueOperation: null,
    redOperation: null,
  });
  await page.locator("#experiment-json").fill(JSON.stringify(advanced));
  await page.locator("#experiment-json-save").click();
  await clickAndStatus("#experiment-start", "配对实验已提交");
  await page.waitForFunction(() =>
    document
      .getElementById("experiment-progress")
      .textContent.startsWith("已完成"),
  );
  const secondId = await page.locator("#experiment-list").inputValue();
  const second = await http(`/experiments/${secondId}/export`);
  assert.equal(second.runs[0].days.length, 2);
  await page.reload();
  await page.locator("#token").fill(token);
  await page.locator("#auth-form button").first().click();
  await page.waitForFunction(() =>
    document.getElementById("connection").textContent.includes("已连接"),
  );
  await page.locator("#experiments-tab").click();
  await page.waitForFunction(
    () => document.getElementById("experiment-list").options.length === 3,
  );
  assert.deepEqual(await http(`/experiments/${id}/export`), archive);
  // A 24-regiment, 30-day job must not block navigation or monopolize the HTTP service.
  const large = JSON.parse(
    await readFile(join(root, "scenarios/m6-scale-benchmark.json"), "utf8"),
  );
  const largeDraft = await http("/scenarios/import", "POST", large);
  const largeRevision = await http(
    `/scenarios/${largeDraft.id}/revisions`,
    "POST",
    { expectedVersion: 1 },
  );
  const daily = Array.from({ length: 30 }, () => ({
    blue: [],
    red: [],
    blueOperation: null,
    redOperation: null,
  }));
  const batch = await http("/experiments", "POST", {
    requestId: "browser-scale",
    revisionId: largeRevision.id,
    seeds: [1, 2],
    maxIterations: 6,
    a: { name: "A", days: daily },
    b: { name: "B", days: daily },
  });
  await page.locator("#experiment-refresh").click();
  await page.waitForFunction(
    () => !document.getElementById("experiment-refresh").disabled,
  );
  await page.locator("#experiment-list").selectOption(batch.id);
  await page.waitForFunction(
    () =>
      document
        .getElementById("experiment-provenance")
        .textContent.includes("24") ||
      document.getElementById("draft-meta").textContent.includes("24"),
  );
  assert.equal(await page.locator("#workspace-tab").isEnabled(), true);
  await page.locator("#workspace-tab").click();
  await page.waitForSelector(".game-board:not([hidden])");
  await page.locator("#experiments-tab").click();
  await page.waitForSelector("#experiments-view:not([hidden])");
  await page.waitForFunction(
    () =>
      document
        .getElementById("experiment-progress")
        .textContent.startsWith("已完成"),
    null,
    { timeout: 30000 },
  );
  assert.equal((await http(`/experiments/${batch.id}`)).completed, 4);
  assert.deepEqual(errors, []);
  console.log(
    "PASS M6: paired A/B configuration, per-company metrics, paging, three single-screen viewports, historical replay, advanced daily commands and reload; no page errors",
  );
} finally {
  await browser?.close();
  server.kill("SIGTERM");
  await new Promise((resolve) => {
    if (server.exitCode !== null) resolve();
    else {
      server.once("exit", resolve);
      setTimeout(() => {
        server.kill("SIGKILL");
        resolve();
      }, 5000).unref();
    }
  });
  await rm(work, { recursive: true, force: true });
}
