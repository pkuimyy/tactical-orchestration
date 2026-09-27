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
  const selectCell = async (q, r) =>
    page.locator(`[data-cell-key="${q},${r}"]`).click();
  const terrain = async (q, r, value) => {
    await page.locator('[data-tab="terrain-panel"]').click();
    await selectCell(q, r);
    await page.locator("#terrain").selectOption(value);
    await clickAndStatus("#cell-form button", "保存成功");
  };
  const unit = async (q, r, name, role, type) => {
    await page.locator('[data-tab="unit-panel"]').click();
    await selectCell(q, r);
    await page.locator("#regiment-name").fill(name);
    await page.locator("#role").selectOption(role);
    await page
      .locator('#companies [data-field="type"]')
      .first()
      .selectOption(type);
    await clickAndStatus(
      '#regiment-form button[type="submit"], #regiment-form .toolbar button:not([type])',
      "保存成功",
    );
  };
  await page.locator("#new-scenario").click();
  await page.locator("#create-name").fill("浏览器验收场景");
  await clickAndStatus("#create-form button", "已从服务端载入");
  assert.equal(await page.locator("[data-cell-key]").count(), 35);
  await terrain(0, 2, "CITY");
  await unit(0, 2, "蓝方师部", "DIVISION_HQ", "SIGNAL");
  await terrain(6, 2, "CITY");
  await selectCell(6, 2);
  await page.locator('[data-tab="unit-panel"]').click();
  await page.locator("#side").selectOption("RED");
  // unit() reselects the cell and reloads the inspector, so configure this HQ in-place.
  await page.locator("#regiment-name").fill("红方师部");
  await page.locator("#role").selectOption("DIVISION_HQ");
  await page.locator('#companies [data-field="type"]').selectOption("SIGNAL");
  await clickAndStatus(
    "#regiment-form .toolbar button:not([type])",
    "保存成功",
  );
  await unit(1, 1, "工兵团", "REGIMENT", "ENGINEER");
  await unit(2, 2, "装甲团", "REGIMENT", "ARMOR");
  await page
    .locator('#companies [data-field="equipment"]')
    .selectOption("TRACKED");
  await page.locator('#companies [data-field="hp"]').fill("80");
  await clickAndStatus(
    "#regiment-form .toolbar button:not([type])",
    "保存成功",
  );
  await page.locator('[data-tab="terrain-panel"]').click();
  await page.locator("#neighbor").selectOption("3,2");
  await page.locator("#river").check();
  await page.locator("#road").check();
  await page.locator("#bridge").selectOption("INTACT");
  await clickAndStatus("#edge-form button", "保存成功");
  await selectCell(1, 3);
  await page.locator('[data-tab="supply-panel"]').click();
  await page.locator("#stock").fill("300");
  await clickAndStatus("#supply-form button:not([type])", "保存成功");
  const id = await page.evaluate(() =>
    localStorage.getItem("tactical-draft-id"),
  );
  const before = await http(`/scenarios/${id}`);
  assert.equal(before.scenario.regiments.length, 4);
  assert.equal(before.scenario.supplies[0].stock, 300);
  await clickAndStatus("#freeze", "冻结成功");
  await clickAndStatus("#create-game", "独立实验已创建");
  await clickAndStatus("#create-game", "独立实验已创建");
  await page.waitForFunction(
    () => document.querySelectorAll(".game").length === 2,
  );
  const [revision] = await http(`/scenarios/${id}/revisions`),
    games = await http(`/revisions/${revision.id}/games`);
  assert.notEqual(games[0].id, games[1].id);
  assert.deepEqual(games[0].initialState, games[1].initialState);
  await terrain(3, 4, "FOREST");
  assert.equal(
    (await http(`/games/${games[0].id}`)).contentHash,
    revision.contentHash,
  );
  // Server validation, surfaced by the page: moving a city underneath an HQ must be rejected.
  await selectCell(0, 2);
  await page.locator("#terrain").selectOption("PLAIN");
  await clickAndStatus("#cell-form button", "师部必须部署在城市格");
  assert.ok(
    await page
      .locator("#status")
      .evaluate((e) => e.classList.contains("error")),
  );
  assert.equal(
    (await http(`/scenarios/${id}`)).scenario.cells.find(
      (c) => c.position.q === 0 && c.position.r === 2,
    ).terrain,
    "CITY",
  );
  await page.reload();
  assert.equal(await page.locator("#token").inputValue(), "");
  await page.locator("#token").fill(token);
  await clickAndStatus("#auth-form button:first-of-type", "已从服务端载入");
  await selectCell(3, 4);
  assert.equal(await page.locator("#terrain").inputValue(), "FOREST");
  assert.equal(await page.locator(".game").count(), 2);
  // Export via UI, import into a distinct draft, freeze and compare canonical content hashes.
  const downloadPromise = page.waitForEvent("download");
  await clickAndStatus("#export", "已导出");
  const download = await downloadPromise,
    path = await download.path();
  const exported = JSON.parse(await readFile(path, "utf8"));
  assert.equal(exported.name, "浏览器验收场景");
  await page.locator("#import-file").setInputFiles(path);
  await page.waitForFunction(
    (old) => localStorage.getItem("tactical-draft-id") !== old,
    id,
  );
  await page.waitForFunction(() =>
    document.getElementById("status").textContent.includes("已从服务端载入"),
  );
  const importedId = await page.evaluate(() =>
    localStorage.getItem("tactical-draft-id"),
  );
  await clickAndStatus("#freeze", "冻结成功");
  const original = await http(`/scenarios/${id}/revisions`, "POST", {
    expectedVersion: (await http(`/scenarios/${id}`)).version,
  });
  assert.equal(
    (await http(`/scenarios/${importedId}/revisions`))[0].contentHash,
    original.contentHash,
  );
  // Six-company dossiers and repeated runs must fit without hidden/clipped controls.
  await page.locator('[data-tab="unit-panel"]').click();
  await selectCell(2, 2);
  for (let i = 1; i < 6; i++) await page.locator("#add-company").click();
  assert.equal(await page.locator(".company:not([hidden])").count(), 1);
  await clickAndStatus(
    "#regiment-form .toolbar button:not([type])",
    "保存成功",
  );
  for (let i = 0; i < 6; i++) {
    await page.locator("#company-tabs button").nth(i).click();
    assert.equal(await page.locator(".company:not([hidden])").count(), 1);
  }
  await clickAndStatus("#freeze", "冻结成功");
  for (let i = 0; i < 5; i++)
    await clickAndStatus("#create-game", "独立实验已创建");
  assert.equal(await page.locator(".game").count(), 3);
  await page.locator("#games-next").click();
  assert.equal(await page.locator(".game").count(), 2);
  await page.locator("#games-prev").click();
  const beforeZoom = await page.locator("#map").getAttribute("viewBox");
  await page.locator("#zoom-in").click();
  assert.notEqual(
    await page.locator("#map").getAttribute("viewBox"),
    beforeZoom,
  );
  await page.locator("#zoom-reset").click();
  assert.equal(await page.locator("#map").getAttribute("viewBox"), beforeZoom);
  for (const size of [
    { width: 1980, height: 1080 },
    { width: 1920, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(size);
    for (const tab of ["terrain-panel", "unit-panel", "supply-panel"]) {
      await page.locator(`[data-tab="${tab}"]`).click();
      const overflow = await page.evaluate(() => {
        const roots = [
          "html",
          "body",
          ".game-board",
          ".inspector",
          ".inspector-page:not([hidden])",
          ".operations",
          ".journal",
          ".map-scroll",
        ];
        return roots.filter((selector) => {
          const e = document.querySelector(selector);
          return (
            e.scrollHeight > e.clientHeight + 2 ||
            e.scrollWidth > e.clientWidth + 2
          );
        });
      });
      assert.deepEqual(
        overflow,
        [],
        `layout overflow at ${size.width}x${size.height} ${tab}`,
      );
      const bottom = await page.locator(`#${tab} form`).last().boundingBox();
      assert.ok(
        bottom.y + bottom.height <= size.height - 30,
        "inspector form clipped",
      );
    }
  }
  await page.setViewportSize({ width: 1980, height: 1080 });
  await page.locator('[data-tab="unit-panel"]').click();
  await page.screenshot({
    path: join(root, "tactical-server/target/m1.1-game.png"),
    fullPage: true,
  });
  // M1.2 management operates through a separate header tab.
  await clickAndStatus("#library-tab", "战场档案已载入");
  assert.ok(await page.locator(".game-board").isHidden());
  await page.locator("#library-search").fill("浏览器验收场景");
  let card = page.locator(`[data-scenario-id="${importedId}"]`);
  await card.getByRole("button", { name: "重命名", exact: true }).click();
  await page.locator("#rename-name").fill("M1.2 管理验收");
  await clickAndStatus("#rename-form button:not([type])", "战场名称已更新");
  await page.locator("#library-search").fill("M1.2 管理验收");
  await card.getByRole("button", { name: "复制", exact: true }).click();
  await page.waitForFunction(
    () => document.querySelectorAll(".library-card").length === 2,
  );
  const copyCard = page.locator(".library-card").filter({ hasText: "副本" });
  const copyId = await copyCard.getAttribute("data-scenario-id");
  assert.equal((await http(`/scenarios/${copyId}/revisions`)).length, 0);
  await copyCard.getByRole("button", { name: "归档", exact: true }).click();
  await page.waitForFunction(
    () => document.querySelectorAll(".library-card").length === 1,
  );
  await page.locator("#show-archived").check();
  await copyCard.getByRole("button", { name: "恢复", exact: true }).click();
  await page.waitForFunction(() =>
    document.getElementById("status").textContent.includes("已恢复"),
  );
  page.once("dialog", (d) => d.accept());
  await copyCard.getByRole("button", { name: "删除", exact: true }).click();
  await page.waitForFunction(
    () => document.querySelectorAll(".library-card").length === 1,
  );
  for (const size of [
    { width: 1980, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(size);
    assert.ok(
      await page.evaluate(
        () =>
          document.body.scrollHeight <= innerHeight &&
          document.querySelector(".library-view").scrollHeight <=
            document.querySelector(".library-view").clientHeight + 2,
      ),
    );
  }
  await page.screenshot({
    path: join(root, "tactical-server/target/m1.2-library.png"),
  });
  await card.getByRole("button", { name: "打开部署", exact: true }).click();
  await page.waitForFunction(
    () => !document.querySelector(".game-board").hidden,
  );
  assert.deepEqual(errors, []);
  assert.ok(!log.includes(token));
  console.log(
    "PASS: browser blank map → HQ/engineer/armor/supply/edge/HP editing → freeze → two games → isolation → validation error → refresh → export/import hash equivalence; single-screen 1980×1080 / 1920×1080 / 1980×960, six-company paging and no page errors",
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
