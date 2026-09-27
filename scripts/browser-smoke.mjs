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
  // M2 browser orders are replayed verbatim through HTTP on an independent instance.
  const pursuit = JSON.parse(
    await readFile(join(root, "scenarios/m2-recon-pursuit.json"), "utf8"),
  );
  const pursuitDraft = await http("/scenarios/import", "POST", pursuit);
  const pursuitRevision = await http(
    `/scenarios/${pursuitDraft.id}/revisions`,
    "POST",
    { expectedVersion: 1 },
  );
  const pursuitGame = await http("/games", "POST", {
    revisionId: pursuitRevision.id,
    seed: 42,
  });
  const replayGame = await http("/games", "POST", {
    revisionId: pursuitRevision.id,
    seed: 42,
  });
  await clickAndStatus("#library-tab", "战场档案已载入");
  await page.locator("#library-search").fill(pursuit.name);
  await page
    .locator(`[data-scenario-id="${pursuitDraft.id}"]`)
    .getByRole("button", { name: "打开部署" })
    .click();
  await page.waitForFunction(
    () => !document.querySelector(".game-board").hidden,
  );
  await page
    .locator(".game")
    .first()
    .getByRole("button", { name: "进入推演" })
    .click();
  await page.waitForFunction(
    () =>
      document.getElementById("battle-tab").getAttribute("aria-pressed") ===
      "true",
  );
  assert.ok(await page.locator(".inspector").isHidden());
  await page.locator("#order-unit").selectOption("recon");
  for (let q = 2; q <= 11; q++) await selectCell(q, 1);
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  assert.ok(await page.locator("#submit-orders").isDisabled());
  assert.ok(await page.locator("#resolve-day").isDisabled());
  await page.locator("#side-red").click();
  await page.locator("#order-unit").selectOption("armor");
  for (let q = 1; q <= 10; q++) await selectCell(q, 1);
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await clickAndStatus("#resolve-day", "第 1 天结算完成");
  const firstDay = await http(`/games/${pursuitGame.id}/days/1`);
  for (const [id, q] of [
    ["recon", 7],
    ["armor", 5],
  ])
    assert.equal(
      firstDay.result.world.regiments.find((r) => r.id === id).position.q,
      q,
    );
  assert.equal(
    firstDay.result.events.filter((e) => e.kind === "CONTACT").length,
    0,
  );
  const visibleEventIds = await page
    .locator("#events li")
    .evaluateAll((nodes) => nodes.map((n) => n.dataset.eventId));
  assert.deepEqual(
    visibleEventIds,
    firstDay.result.events.slice(0, 6).map((e) => e.id),
  );
  for (const side of ["BLUE", "RED"]) {
    await http(`/games/${replayGame.id}/orders/${side}`, "PUT", {
      day: 1,
      expectedVersion: 0,
      orders: firstDay.manifest[side.toLowerCase()],
    });
    await http(`/games/${replayGame.id}/commit/${side}`, "POST", {
      day: 1,
      expectedVersion: 1,
    });
  }
  assert.deepEqual(
    await http(`/games/${replayGame.id}/resolve`, "POST", { day: 1 }),
    firstDay,
  );
  assert.deepEqual(
    await http(`/games/${pursuitGame.id}/resolve`, "POST", { day: 1 }),
    firstDay,
  );
  assert.equal((await http(`/games/${pursuitGame.id}`)).day, 1);
  const eventDownload = page.waitForEvent("download");
  await page.locator("#export-events").click();
  const eventFile = await eventDownload;
  assert.deepEqual(
    (await readFile(await eventFile.path(), "utf8"))
      .trim()
      .split("\n")
      .map(JSON.parse),
    firstDay.result.events,
  );
  const manifestDownload = page.waitForEvent("download");
  await page.locator("#export-day").click();
  const manifestFile = await manifestDownload;
  assert.deepEqual(
    JSON.parse(await readFile(await manifestFile.path(), "utf8")),
    firstDay,
  );
  for (const size of [
    { width: 1980, height: 1080 },
    { width: 1920, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(size);
    const overflow = await page.evaluate(() =>
      [
        "html",
        "body",
        ".game-board",
        "#turn-panel",
        "#turn-operations",
        "#battle-bar",
      ].filter((s) => {
        const e = document.querySelector(s);
        return (
          e.scrollHeight > e.clientHeight + 2 ||
          e.scrollWidth > e.clientWidth + 2
        );
      }),
    );
    assert.deepEqual(overflow, [], `M2 overflow ${size.width}x${size.height}`);
  }
  await page.screenshot({
    path: join(root, "tactical-server/target/m2-command.png"),
    fullPage: true,
  });
  await page.locator("#workspace-tab").click();
  await page.waitForFunction(
    () => !document.querySelector(".inspector").hidden,
  );
  assert.equal(
    (await http(`/scenarios/${pursuitDraft.id}`)).scenario.regiments.find(
      (r) => r.id === "recon",
    ).position.q,
    1,
  );
  // M3 end-to-end combat, doctrine override, six-company reports and stock-backed rest.
  await clickAndStatus("#preset-combat", "已从服务端载入");
  const combatDraftId = await page.locator("#draft-list").inputValue();
  await clickAndStatus("#freeze", "冻结成功");
  await clickAndStatus("#create-game", "独立实验已创建");
  const combatRevision = (
    await http(`/scenarios/${combatDraftId}/revisions`)
  )[0];
  const combatGame = (await http(`/revisions/${combatRevision.id}/games`))[0];
  await page
    .locator(".game")
    .first()
    .getByRole("button", { name: "进入推演" })
    .click();
  await page.waitForFunction(
    () =>
      document.getElementById("battle-tab").getAttribute("aria-pressed") ===
      "true",
  );
  await page.locator("#side-blue").click();
  await page.locator("#order-unit").selectOption("assault");
  await page.locator('[data-turn-tab="doctrine-page"]').click();
  await page.locator("#doctrine-template").selectOption("BREAKTHROUGH");
  await page.locator("#doctrine-supply").selectOption("rear");
  assert.ok(
    !(await page.locator("#doctrine-supply").textContent()).includes("hidden"),
  );
  await page.locator('[data-turn-tab="action-page"]').click();
  await selectCell(3, 1);
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await page.locator("#side-red").click();
  await page.locator("#order-unit").selectOption("line");
  await page.locator("#order-action").selectOption("DEFEND");
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await clickAndStatus("#resolve-day", "第 1 天结算完成");
  const battleResult = await http(`/games/${combatGame.id}/days/1`);
  const assaultReport = battleResult.result.units.find(
    (r) => r.id === "assault",
  );
  assert.deepEqual(assaultReport.after, { q: 1, r: 1 });
  assert.ok(assaultReport.companies.some((c) => c.hp < c.beforeHp));
  const combatReplay = await http("/games", "POST", {
    revisionId: combatRevision.id,
    seed: 42,
  });
  for (const side of ["BLUE", "RED"]) {
    await http(`/games/${combatReplay.id}/orders/${side}`, "PUT", {
      day: 1,
      expectedVersion: 0,
      orders: battleResult.manifest[side.toLowerCase()],
    });
    await http(`/games/${combatReplay.id}/commit/${side}`, "POST", {
      day: 1,
      expectedVersion: 1,
    });
  }
  assert.deepEqual(
    await http(`/games/${combatReplay.id}/resolve`, "POST", { day: 1 }),
    battleResult,
  );
  await page.locator("#side-blue").click();
  await page.locator("#order-unit").selectOption("assault");
  await page.locator('[data-turn-tab="report-page"]').click();
  assert.equal(await page.locator("#company-report tr").count(), 6);
  for (const c of assaultReport.companies) {
    const row = await page.locator(`[data-company-id="${c.id}"]`).textContent();
    assert.ok(row.includes(`${c.beforeHp} → ${c.hp}`));
    assert.ok(row.includes(`${(c.organization / 10).toFixed(1)}%`));
  }
  assert.ok(
    (await page.locator("#report-doctrine").textContent()).includes(
      "BREAKTHROUGH_FAILED",
    ),
  );
  const decisionIndex = battleResult.result.events.findIndex(
    (e) => e.regimentId === "assault" && e.decision,
  );
  for (let p = 0; p < Math.floor(decisionIndex / 6); p++)
    await page.locator("#events-next").click();
  await page
    .locator(
      `[data-event-id="${battleResult.result.events[decisionIndex].id}"]`,
    )
    .click();
  assert.ok(
    (await page.locator("#event-detail").textContent()).includes("rear"),
  );
  assert.ok(
    !(await page.locator("#event-detail").textContent()).includes("hidden"),
  );
  await page.locator('[data-close="event-dialog"]').click();
  for (const size of [
    { width: 1980, height: 1080 },
    { width: 1920, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(size);
    for (const tab of ["action-page", "doctrine-page", "report-page"]) {
      await page.locator(`[data-turn-tab="${tab}"]`).click();
      const overflow = await page.evaluate(() =>
        [
          "html",
          "body",
          ".game-board",
          "#turn-panel",
          ".turn-page:not([hidden])",
          "#turn-operations",
        ].filter((s) => {
          const e = document.querySelector(s);
          return (
            e.scrollHeight > e.clientHeight + 2 ||
            e.scrollWidth > e.clientWidth + 2
          );
        }),
      );
      assert.deepEqual(overflow, [], `M3 ${size.width}x${size.height} ${tab}`);
    }
  }
  await page.screenshot({
    path: join(root, "tactical-server/target/m3-combat.png"),
    fullPage: true,
  });
  await page.locator('[data-turn-tab="action-page"]').click();
  await page.locator("#order-action").selectOption("REST");
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await page.locator("#side-red").click();
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await clickAndStatus("#resolve-day", "第 2 天结算完成");
  const rested = await http(`/games/${combatGame.id}/days/2`);
  const recovered = rested.result.events
    .filter((e) => e.kind === "RECOVERED")
    .reduce((sum, e) => sum + e.damage.afterHp - e.damage.beforeHp, 0);
  assert.ok(recovered > 0);
  assert.equal(
    rested.result.world.supplies.find((s) => s.id === "rear").stock,
    200 - recovered,
  );
  assert.deepEqual(
    await http(`/games/${combatGame.id}/resolve`, "POST", { day: 2 }),
    rested,
  );
  await page.locator("#side-blue").click();
  await page.locator("#order-unit").selectOption("guns");
  await page.locator("#order-action").selectOption("BOMBARD");
  await selectCell(3, 1);
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await page.locator("#side-red").click();
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await clickAndStatus("#resolve-day", "第 3 天结算完成");
  const bombard = await http(`/games/${combatGame.id}/days/3`);
  assert.ok(
    bombard.result.events.some(
      (e) => e.kind === "DAMAGE" && e.regimentId === "line",
    ),
  );
  assert.ok(
    bombard.result.units
      .find((r) => r.id === "guns")
      .companies.every((c) => c.hp === c.beforeHp),
  );
  // M4: native operation form, DAG, report projection and relay editor.
  await page.locator("#workspace-tab").click();
  await page.locator("#preset-coordination").click();
  await page.waitForFunction(() =>
    document.getElementById("draft-meta").textContent.includes("河桥协作"),
  );
  await selectCell(4, 2);
  await page.locator('[data-tab="relay-panel"]').click();
  assert.equal(await page.locator("#relay-hp").inputValue(), "30");
  await page.locator("#relay-hp").fill("31");
  await page.locator("#relay-form button").first().click();
  await page.waitForFunction(() =>
    document.getElementById("draft-meta").textContent.includes("v2"),
  );
  const river = await http("/presets/coordination");
  const riverDraft = await http("/scenarios/import", "POST", river);
  const riverRevision = await http(
    `/scenarios/${riverDraft.id}/revisions`,
    "POST",
    { expectedVersion: 1 },
  );
  const riverGame = await http("/games", "POST", {
    revisionId: riverRevision.id,
    seed: 42,
  });
  await page.evaluate(
    (id) => localStorage.setItem("tactical-game-id", id),
    riverGame.id,
  );
  await page.reload();
  await page.locator("#token").fill(token);
  await page.locator("#auth-form button").first().click();
  await page.waitForFunction(() =>
    document.getElementById("connection").textContent.includes("已连接"),
  );
  await page.locator("#battle-tab").click();
  await page.waitForFunction(() =>
    document.getElementById("battle-name").textContent.includes("河桥协作"),
  );
  await page.locator("#side-blue").click();
  await page.locator('[data-turn-tab="operation-page"]').click();
  await page.locator("#op-demo").click();
  assert.equal(await page.locator("#operation-dag [data-order-id]").count(), 2);
  await page.locator("#op-task").selectOption("cross");
  assert.equal(await page.locator("#op-after").inputValue(), "bridge");
  await page.locator("#op-save").click();
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await page.locator("#side-red").click();
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#commit-orders", "命令已锁定");
  await clickAndStatus("#resolve-day", "第 1 天结算完成");
  await page.locator("#side-blue").click();
  const riverResult = await http(`/games/${riverGame.id}/days/1`);
  assert.equal(
    riverResult.result.operations.find((n) => n.orderId === "cross").status,
    "COMPLETED",
  );
  for (const perspective of ["DIVISION", "OMNISCIENT"]) {
    await page.locator("#perspective").selectOption(perspective);
    await page.waitForFunction(
      (p) =>
        document
          .getElementById("status")
          .textContent.includes(p === "DIVISION" ? "师部视图" : "全知验证视图"),
      perspective,
    );
    if (perspective === "DIVISION")
      assert.ok(
        !(await page.locator("#report-unit").textContent()).includes("red-hq"),
      );
    for (const size of [
      { width: 1980, height: 1080 },
      { width: 1920, height: 1080 },
      { width: 1980, height: 960 },
    ]) {
      await page.setViewportSize(size);
      for (const tab of [
        "operation-page",
        "action-page",
        "doctrine-page",
        "report-page",
      ]) {
        await page.locator(`[data-turn-tab="${tab}"]`).click();
        const overflow = await page.evaluate(() =>
          [
            "html",
            "body",
            ".game-board",
            "#turn-panel",
            ".turn-page:not([hidden])",
            "#battle-bar",
          ].filter((s) => {
            const e = document.querySelector(s);
            return (
              e.scrollHeight > e.clientHeight + 2 ||
              e.scrollWidth > e.clientWidth + 2
            );
          }),
        );
        assert.deepEqual(
          overflow,
          [],
          `M4 ${perspective} ${size.width}x${size.height} ${tab}`,
        );
      }
    }
    assert.deepEqual(await http(`/games/${riverGame.id}/days/1`), riverResult);
  }
  await page.locator('[data-turn-tab="operation-page"]').click();
  await page.locator('#operation-dag [data-order-id="cross"]').click();
  assert.ok(
    (await page.locator("#op-status").textContent()).includes("bridge"),
  );
  await page.screenshot({
    path: join(root, "tactical-server/target/m4-coordination.png"),
  });
  // M5: complete native UI authoring / blueprint reopen / historical observation loop.
  await page.locator("#workspace-tab").click();
  await clickAndStatus("#preset-lab", "已载入河谷实验");
  await page.locator('[data-tab="intel-panel"]').click();
  await page.locator("#intel-observer").selectOption("blue-engineers");
  await page.locator("#intel-units").fill("red-guard");
  await page.locator("#intel-supplies").fill("blue-rear");
  await clickAndStatus("#intel-form button", "保存成功");
  const labDraftId = await page.locator("#draft-list").inputValue();
  const labDraft = await http(`/scenarios/${labDraftId}`);
  assert.ok(
    labDraft.scenario.initialKnowledge.some(
      (k) =>
        k.observerId === "blue-engineers" &&
        k.regimentIds.includes("red-guard"),
    ),
  );
  assert.equal(
    await page
      .locator('[data-unit-marker="red-line"] [data-type-icon]')
      .count(),
    2,
  );
  for (const size of [
    { width: 1980, height: 1080 },
    { width: 1920, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(size);
    const overflow = await page.evaluate(() =>
      ["html", "body", ".inspector", "#intel-panel"].filter((s) => {
        const e = document.querySelector(s);
        return (
          e.scrollHeight > e.clientHeight + 2 ||
          e.scrollWidth > e.clientWidth + 2
        );
      }),
    );
    assert.deepEqual(
      overflow,
      [],
      `M5 intelligence editor ${size.width}x${size.height}`,
    );
  }
  await clickAndStatus("#freeze", "冻结成功");
  await clickAndStatus("#create-game", "独立实验已创建");
  await page
    .locator(".game")
    .first()
    .getByRole("button", { name: "进入推演" })
    .click();
  await page.waitForFunction(
    () => !document.getElementById("turn-panel").hidden,
  );
  await page.locator("#side-blue").click();
  await page.locator('[data-turn-tab="doctrine-page"]').click();
  await page.locator("#order-unit").selectOption("blue-armor");
  assert.equal(await page.locator("#doctrine-threshold").inputValue(), "35");
  await page.locator("#doctrine-threshold").fill("45");
  await page.locator("#doctrine-threshold").dispatchEvent("change");
  await clickAndStatus("#submit-orders", "命令已提交");
  await page.locator("#side-red").click();
  await clickAndStatus("#submit-orders", "命令已提交");
  await clickAndStatus("#save-blueprint", "实验方案已另存");
  const blueprintId = await page.locator("#draft-list").inputValue();
  const blueprint = await http(`/scenarios/${blueprintId}`);
  assert.equal(
    blueprint.scenario.setup.blue.find((o) => o.regimentId === "blue-armor")
      .doctrine.withdrawBelowPercent,
    45,
  );
  assert.deepEqual(
    blueprint.scenario.setup.blueOperation.nodes.find(
      (n) => n.orderId === "cross",
    ).after,
    ["bridge", "fire"],
  );
  assert.deepEqual(
    blueprint.scenario.initialKnowledge,
    labDraft.scenario.initialKnowledge,
  );
  await clickAndStatus("#reload", "已从服务端载入");
  await clickAndStatus("#freeze", "冻结成功");
  await clickAndStatus("#create-game", "独立实验已创建");
  const labRevision = (await http(`/scenarios/${blueprintId}/revisions`))[0];
  const labGame = (await http(`/revisions/${labRevision.id}/games`))[0];
  await page
    .locator(".game")
    .first()
    .getByRole("button", { name: "进入推演" })
    .click();
  await page.waitForFunction(
    () => !document.getElementById("turn-panel").hidden,
  );
  for (const side of ["blue", "red"]) {
    await page.locator(`#side-${side}`).click();
    await clickAndStatus("#submit-orders", "命令已提交");
    await clickAndStatus("#commit-orders", "命令已锁定");
  }
  await clickAndStatus("#resolve-day", "第 1 天结算完成");
  await page.locator("#side-blue").click();
  const labResult = await http(`/games/${labGame.id}/days/1`);
  assert.ok(labResult.result.events.some((e) => e.kind === "WITHDRAW"));
  assert.deepEqual(
    labResult.result.world.regiments.find((r) => r.id === "blue-armor")
      .position,
    { q: 1, r: 1 },
  );
  const labCopy = await http("/games", "POST", {
    revisionId: labRevision.id,
    seed: labGame.seed,
    maxIterations: 6,
  });
  for (const side of ["BLUE", "RED"]) {
    await http(`/games/${labCopy.id}/orders/${side}`, "PUT", {
      day: 1,
      expectedVersion: 0,
      orders: labResult.manifest[side.toLowerCase()],
      operation: labResult.manifest[side.toLowerCase() + "Operation"],
    });
    await http(`/games/${labCopy.id}/commit/${side}`, "POST", {
      day: 1,
      expectedVersion: 1,
    });
  }
  assert.deepEqual(
    await http(`/games/${labCopy.id}/resolve`, "POST", { day: 1 }),
    labResult,
  );
  // A slow replay read keeps map tools and inspection tabs usable.
  await page.route(
    "**/days/1/replay?**",
    async (route) => {
      await pause(600);
      await route.continue();
    },
    { times: 1 },
  );
  await page.locator("#replay-start").click();
  await page.waitForFunction(
    () => document.body.getAttribute("aria-busy") === "true",
  );
  assert.equal(await page.locator("#zoom-in").isDisabled(), false);
  assert.equal(
    await page.locator('[data-turn-tab="report-page"]').isDisabled(),
    false,
  );
  await page.locator("#zoom-in").click();
  await page.waitForFunction(
    () =>
      document.body.classList.contains("replaying") &&
      document.body.getAttribute("aria-busy") === "false",
  );
  assert.equal(await page.locator("#resolve-day").isDisabled(), true);
  await page.locator("#replay-play").click();
  await page.waitForFunction(
    () => Number(document.getElementById("replay-frame").value) > 0,
  );
  await page.locator("#replay-pause").click();
  const pausedFrame = await page.locator("#replay-frame").inputValue();
  await pause(800);
  assert.equal(await page.locator("#replay-frame").inputValue(), pausedFrame);
  const seekLast = async () => {
    await page.locator("#replay-frame").evaluate((e) => {
      e.value = e.max;
      e.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await page.waitForFunction(() =>
      document.getElementById("replay-time").textContent.includes("报告送达"),
    );
  };
  await seekLast();
  await page.locator("#event-unit").selectOption("blue-armor");
  await page.locator("#event-task").selectOption("cross");
  assert.ok((await page.locator("#events [data-event-id]").count()) > 0);
  for (const eventId of await page
    .locator("#events [data-event-id]")
    .evaluateAll((es) => es.map((e) => e.dataset.eventId)))
    assert.equal(
      labResult.result.events.find((e) => e.id === eventId).orderId,
      "cross",
    );
  await page.locator("#event-reset").click();
  await page.locator("#perspective").selectOption("DIVISION");
  await page.waitForFunction(
    () =>
      document.getElementById("replay-frame").value === "0" &&
      document.body.getAttribute("aria-busy") === "false",
  );
  assert.equal(await page.locator('[data-unit-marker="red-guard"]').count(), 0);
  await seekLast();
  assert.equal(await page.locator('[data-unit-marker="red-guard"]').count(), 1);
  for (const size of [
    { width: 1980, height: 1080 },
    { width: 1920, height: 1080 },
    { width: 1980, height: 960 },
  ]) {
    await page.setViewportSize(size);
    for (const tab of ["report-page", "operation-page"]) {
      await page.locator(`[data-turn-tab="${tab}"]`).click();
      const overflow = await page.evaluate(() =>
        [
          "html",
          "body",
          ".game-board",
          "#turn-panel",
          "#turn-operations",
          ".turn-page:not([hidden])",
        ].filter((s) => {
          const e = document.querySelector(s);
          return (
            e.scrollHeight > e.clientHeight + 2 ||
            e.scrollWidth > e.clientWidth + 2
          );
        }),
      );
      assert.deepEqual(
        overflow,
        [],
        `M5 replay ${size.width}x${size.height} ${tab}`,
      );
    }
  }
  await page.locator('#operation-dag [data-order-id="cross"]').click();
  assert.ok(
    (
      await page.locator('#operation-dag [data-order-id="cross"]').textContent()
    ).includes("失败"),
  );
  await page.screenshot({
    path: join(root, "tactical-server/target/m5-replay.png"),
  });
  await clickAndStatus("#replay-exit", "已返回当前指挥");
  assert.equal(await page.locator("#submit-orders").isDisabled(), false);
  assert.deepEqual(await http(`/games/${labGame.id}/days/1`), labResult);
  // Failed reads expose a safe reload entry without automatically repeating writes.
  await page.route("**/api/v1/games/*/turn", (route) => route.abort(), {
    times: 1,
  });
  await page.locator("#turn-reload").click();
  await page.waitForFunction(() =>
    document.getElementById("status").classList.contains("error"),
  );
  assert.equal(await page.locator("#retry-request").isVisible(), true);
  await clickAndStatus("#retry-request", "已重新连接并载入服务端状态");
  assert.deepEqual(errors, []);
  assert.ok(!log.includes(token));
  console.log(
    "PASS: browser blank map → HQ/engineer/armor/supply/edge/HP editing → freeze → two games → isolation → validation error → refresh → export/import hash equivalence; single-screen 1980×1080 / 1920×1080 / 1980×960, six-company paging, M1.2 management; M2 both-side orders, lock/resolve, HTTP replay equality, JSONL and manifest export; M3 combat/known-only retreat, six-company reports, rest/stock and bombardment; M4 relay editing, operation form/DAG, confirmed crossing, both projections, three viewport sizes and unchanged replay; M5 authored intelligence/blueprint reopen, pictorial markers, historical playback/pause/filters, busy interactivity and safe reload, no page errors",
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
