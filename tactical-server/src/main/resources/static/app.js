const $ = (id) => document.getElementById(id);
let token = "",
  draft = null,
  selected = null,
  revisions = [],
  revision = null,
  busy = false;
const names = {
  INFANTRY: "步兵",
  ARMOR: "装甲",
  ANTI_TANK: "反坦克",
  ARTILLERY: "炮兵",
  ENGINEER: "工兵",
  RECON: "侦察",
  SIGNAL: "通信",
};
const equipment = {
  FOOT: "徒步",
  MOTORIZED: "摩托化",
  MECHANIZED: "机械化",
  TRACKED: "履带",
  TOWED: "牵引",
};
const symbols = {
  INFANTRY: "步",
  ARMOR: "甲",
  ANTI_TANK: "反",
  ARTILLERY: "炮",
  ENGINEER: "工",
  RECON: "侦",
  SIGNAL: "通",
};
const colors = {
  PLAIN: "#456253",
  FOREST: "#244d40",
  HILL: "#726c4e",
  MOUNTAIN: "#575e63",
  CITY: "#696b65",
};
const same = (a, b) => a && b && a.q === b.q && a.r === b.r;
const key = (p) => `${p.q},${p.r}`;
const pos = (text) => {
  const [q, r] = text.split(",").map(Number);
  return { q, r };
};
const center = (p) => ({
  x: 42 + Math.sqrt(3) * 30 * (p.q + p.r / 2),
  y: 42 + 45 * p.r,
});
// getRandomValues also works on explicitly configured HTTP IP origins (non-secure contexts).
const uid = (prefix) =>
  `${prefix}-${Array.from(crypto.getRandomValues(new Uint32Array(2)), (n) => n.toString(16).padStart(8, "0")).join("")}`;
let activeCompany = 0,
  gameRows = [],
  eventRows = [],
  gamePage = 0,
  eventPage = 0;
let camera = null,
  mapBounds = null,
  drag = null,
  dragged = false;
const gamePageSize = () => (innerWidth > 1500 ? 3 : innerWidth > 1100 ? 2 : 1);
function selectPanel(id) {
  document
    .querySelectorAll(".inspector-page")
    .forEach((p) => (p.hidden = p.id !== id));
  document
    .querySelectorAll("[data-tab]")
    .forEach((b) =>
      b.setAttribute("aria-selected", String(b.dataset.tab === id)),
    );
}
function showCompany(index) {
  const items = [...$("companies").children];
  activeCompany = Math.max(0, Math.min(index, items.length - 1));
  items.forEach((item, i) => (item.hidden = i !== activeCompany));
  $("company-tabs").replaceChildren(
    ...items.map((item, i) => {
      const button = document.createElement("button");
      button.type = "button";
      button.setAttribute("role", "tab");
      button.setAttribute("aria-selected", String(i === activeCompany));
      button.textContent = `${i + 1} ${symbols[item.querySelector('[data-field="type"]').value]}`;
      button.addEventListener("click", () => showCompany(i));
      return button;
    }),
  );
  $("company-count").textContent = `${items.length} / 6`;
  $("add-company").disabled = busy || !selected || !token || items.length >= 6;
}
function paging() {
  const gp = Math.ceil(gameRows.length / gamePageSize()),
    ep = Math.ceil(eventRows.length / 6);
  $("games-page").textContent = gp ? `${gamePage + 1} / ${gp}` : "0 / 0";
  $("events-page").textContent = ep ? `${eventPage + 1} / ${ep}` : "0 / 0";
  for (const [id, disabled] of [
    ["games-prev", gamePage === 0],
    ["games-next", gamePage + 1 >= gp],
    ["events-prev", eventPage === 0],
    ["events-next", eventPage + 1 >= ep],
  ])
    $(id).disabled = busy || disabled;
}
function renderEvents() {
  const labels = {
    DRAFT_CREATED: "建立战场",
    DRAFT_UPDATED: "调整部署",
    SCENARIO_FROZEN: "冻结部署",
    GAME_CREATED: "创建独立实验",
  };
  $("events").replaceChildren(
    ...eventRows.slice(eventPage * 6, eventPage * 6 + 6).map((e) => {
      const li = document.createElement("li");
      if (e.category) {
        li.textContent = `D${e.day} · t${e.tick} ${eventNames[e.kind] || e.kind} · ${e.regimentId}`;
        li.title = `${li.textContent} / ${key(e.from)} → ${key(e.to)} / ${e.reason} / 路段速度 ${e.speed}`;
        li.dataset.eventId = e.id;
        li.addEventListener("click", () => {
          selected = e.to;
          renderMap();
          message(li.title);
        });
        return li;
      }
      li.textContent = `${String(e.sequence).padStart(3, "0")}  ${labels[e.kind] || e.kind} · ${e.entityId.slice(0, 8)}`;
      li.title = `${e.kind} / ${e.entityId} / ${e.contentHash}`;
      return li;
    }),
  );
  paging();
}
function applyCamera() {
  if (camera)
    $("map").setAttribute(
      "viewBox",
      `${camera.x} ${camera.y} ${camera.w} ${camera.h}`,
    );
}
function zoom(factor, point) {
  if (!camera) return;
  const width = Math.max(
      mapBounds.w * 0.25,
      Math.min(mapBounds.w * 1.5, camera.w * factor),
    ),
    ratio = width / camera.w;
  const anchor = point || {
    x: camera.x + camera.w / 2,
    y: camera.y + camera.h / 2,
  };
  camera = {
    x: anchor.x + (camera.x - anchor.x) * ratio,
    y: anchor.y + (camera.y - anchor.y) * ratio,
    w: width,
    h: camera.h * ratio,
  };
  applyCamera();
}
function mapPoint(event) {
  return new DOMPoint(event.clientX, event.clientY).matrixTransform(
    $("map").getScreenCTM().inverse(),
  );
}

function message(text, error = false) {
  $("status").textContent = text;
  $("status").classList.toggle("error", error);
  $("status").title = text;
  document.querySelectorAll(".dialog-status,#auth-status").forEach((e) => {
    e.textContent = text;
    e.classList.toggle("error", error);
  });
}
function controls() {
  document.querySelectorAll("button").forEach((e) => (e.disabled = busy));
  document
    .querySelectorAll("[data-auth]")
    .forEach((e) => (e.disabled = busy || !token));
  document
    .querySelectorAll("[data-draft]")
    .forEach((e) => (e.disabled = busy || !draft || !token));
  document
    .querySelectorAll("[data-cell]")
    .forEach((e) => (e.disabled = busy || !selected || !token));
  document
    .querySelectorAll("[data-revision]")
    .forEach((e) => (e.disabled = busy || !revision || !token));
  $("draft-list").disabled = busy || !token;
  $("revision-list").disabled = busy || !draft;
  $("connection").textContent = token ? "已连接" : "未连接";
  $("add-company").disabled =
    busy || !selected || !token || $("companies").children.length >= 6;
  paging();
  libraryControls();
  battleControls();
}
async function run(action) {
  if (busy) return;
  busy = true;
  controls();
  message("请求处理中…");
  try {
    await action();
  } catch (error) {
    message(
      error.message || "服务不可用，请检查服务后重新连接或重新载入。",
      true,
    );
  } finally {
    busy = false;
    controls();
  }
}
async function api(path, method = "GET", body) {
  let response;
  try {
    response = await fetch("/api/v1" + path, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    throw new Error("无法连接服务或请求超时；请确认服务正在运行，再重新载入。");
  }
  const result = response.status === 204 ? null : await response.json();
  if (!response.ok) {
    if (response.status === 401) {
      token = "";
      controls();
    }
    throw new Error(`${response.status} · ${result.message || result.code}`);
  }
  return result;
}
function optionList(select, entries, current) {
  select.replaceChildren(
    ...entries.map(([value, label]) => new Option(label, value)),
  );
  if (current !== undefined) select.value = current;
}
async function listDrafts() {
  const list = await api("/scenarios");
  libraryRows = list;
  renderLibrary();
  optionList(
    $("draft-list"),
    [
      ["", "请选择草稿"],
      ...list
        .filter((d) => !d.archived)
        .map((d) => [d.id, `${d.name} · v${d.version}`]),
    ],
    draft?.id || "",
  );
}
async function loadDraft(id) {
  if (draft?.id !== id) camera = null;
  draft = await api(`/scenarios/${id}`);
  localStorage.setItem("tactical-draft-id", id);
  if (
    !selected ||
    !draft.scenario.cells.some((c) => same(c.position, selected))
  )
    selected = { q: 0, r: 0 };
  await listDrafts();
  renderMap();
  renderInspector();
  await loadHistory();
  message(`已从服务端载入「${draft.scenario.name}」草稿 v${draft.version}`);
}
async function loadHistory(preferred) {
  revisions = await api(`/scenarios/${draft.id}/revisions`);
  revision =
    revisions.find((r) => r.id === (preferred || revision?.id)) ||
    revisions.at(-1) ||
    null;
  optionList(
    $("revision-list"),
    revisions.length
      ? revisions.map((r) => [
          r.id,
          `草稿 v${r.draftVersion} · ${r.contentHash.slice(0, 10)}`,
        ])
      : [["", "尚未冻结"]],
    revision?.id || "",
  );
  await renderGames();
  eventRows = (await api(`/scenarios/${draft.id}/events`)).reverse();
  eventPage = 0;
  renderEvents();
}
async function save(edit) {
  const scenario = structuredClone(draft.scenario);
  edit(scenario);
  draft = await api(`/scenarios/${draft.id}`, "PUT", {
    expectedVersion: draft.version,
    scenario,
  });
  renderMap();
  renderInspector();
  await listDrafts();
  await loadHistory();
  message(`保存成功 · 草稿 v${draft.version}。已有冻结版本和实验保持原输入。`);
}
function svg(tag, attributes = {}, text) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [name, value] of Object.entries(attributes))
    node.setAttribute(name, value);
  if (text !== undefined) node.textContent = text;
  return node;
}
function renderMap() {
  const s = activeView === "battle" ? turn.world : draft.scenario,
    map = $("map");
  map.replaceChildren();
  mapBounds = {
    x: 0,
    y: 0,
    w: 85 + Math.sqrt(3) * 30 * (s.width - 1 + (s.height - 1) / 2),
    h: 84 + 45 * (s.height - 1),
  };
  if (!camera) camera = { ...mapBounds };
  applyCamera();
  const blue = s.regiments.filter((r) => r.side === "BLUE").length,
    red = s.regiments.length - blue;
  $("force-summary").textContent =
    `蓝方 ${blue} 团 / 红方 ${red} 团 · 补给 ${s.supplies.length} 处`;
  $("draft-meta").textContent =
    activeView === "battle"
      ? `${s.name} · 第 ${turn.day} 天待命`
      : `${s.name} · v${draft.version} · ${s.width}×${s.height}`;
  for (const cell of s.cells) {
    const c = center(cell.position),
      points = Array.from({ length: 6 }, (_, i) => {
        const a = ((30 + i * 60) * Math.PI) / 180;
        return `${c.x + 30 * Math.cos(a)},${c.y + 30 * Math.sin(a)}`;
      }).join(" ");
    const hex = svg("polygon", {
      points,
      fill: colors[cell.terrain],
      class: `hex ${same(cell.position, selected) ? "selected" : ""}`,
      tabindex: 0,
      role: "button",
      "aria-label": `格子 ${key(cell.position)} ${cell.terrain}`,
      "data-cell-key": key(cell.position),
    });
    const choose = () => {
      if (busy || dragged) return;
      if (activeView === "battle") {
        chooseWaypoint(cell.position);
        return;
      }
      selected = cell.position;
      renderMap();
      renderInspector();
      controls();
    };
    hex.addEventListener("click", choose);
    hex.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        choose();
      }
    });
    map.append(hex);
  }
  const overlays = svg("g", { "pointer-events": "none" });
  map.append(overlays);
  for (const e of s.edges) {
    const a = center(e.a),
      b = center(e.b),
      mx = (a.x + b.x) / 2,
      my = (a.y + b.y) / 2,
      dx = b.x - a.x,
      dy = b.y - a.y,
      d = Math.hypot(dx, dy);
    if (e.road)
      overlays.append(
        svg("line", {
          x1: a.x,
          y1: a.y,
          x2: b.x,
          y2: b.y,
          stroke: "#ddc69c",
          "stroke-width": 3,
          "stroke-dasharray": "5 4",
        }),
      );
    if (e.river)
      overlays.append(
        svg("line", {
          x1: mx - (dy / d) * 15,
          y1: my + (dx / d) * 15,
          x2: mx + (dy / d) * 15,
          y2: my - (dx / d) * 15,
          stroke: "#67bad9",
          "stroke-width": 5,
        }),
      );
    if (e.bridge !== "NONE")
      overlays.append(
        svg("line", {
          x1: mx - (dx / d) * 10,
          y1: my - (dy / d) * 10,
          x2: mx + (dx / d) * 10,
          y2: my + (dy / d) * 10,
          stroke: e.bridge === "INTACT" ? "#ffe0a3" : "#ef9583",
          "stroke-width": 6,
          "stroke-dasharray": e.bridge === "INTACT" ? "none" : "3 3",
        }),
      );
  }
  if (activeView === "battle") {
    const unit = turn.world.regiments.find(
      (r) => r.id === $("order-unit").value,
    );
    const route =
      localOrders[battleSide].find((o) => o.regimentId === unit?.id)?.route ||
      [];
    if (unit && route.length)
      overlays.append(
        svg("polyline", {
          points: [unit.position, ...route]
            .map((p) => {
              const c = center(p);
              return `${c.x},${c.y}`;
            })
            .join(" "),
          fill: "none",
          stroke: battleSide === "BLUE" ? "#8dd9ff" : "#ffb59a",
          "stroke-width": 4,
          "stroke-dasharray": "5 3",
        }),
      );
  }
  for (const cell of s.cells) {
    const c = center(cell.position);
    overlays.append(
      svg(
        "text",
        { x: c.x, y: c.y + 23, "text-anchor": "middle", class: "coord" },
        key(cell.position),
      ),
    );
    if (cell.terrain === "FOREST") {
      for (const [dx, dy] of [
        [-12, -7],
        [0, -10],
        [11, -4],
      ])
        overlays.append(
          svg("path", {
            d: `M ${c.x + dx - 5} ${c.y + dy + 5} l 5 -12 l 5 12 Z`,
            fill: "#183c2a",
            stroke: "#62835b",
            "stroke-width": 0.5,
          }),
        );
    }
    if (cell.terrain === "HILL" || cell.terrain === "MOUNTAIN")
      overlays.append(
        svg("path", {
          d: `M ${c.x - 17} ${c.y + 3} l 12 -16 l 9 12 l 6 -8 l 10 12`,
          fill: "#404f42",
          stroke: "#9b9b71",
          "stroke-width": 1,
        }),
      );
    if (cell.terrain === "CITY")
      overlays.append(svg("text", { x: c.x - 21, y: c.y - 12 }, "▣"));
    if (cell.fortification)
      overlays.append(
        svg("text", { x: c.x + 12, y: c.y - 12 }, `▤${cell.fortification}`),
      );
    if (s.supplies.some((v) => same(v.position, cell.position)))
      overlays.append(svg("text", { x: c.x - 21, y: c.y + 10 }, "＋"));
  }
  for (const r of s.regiments) {
    const c = center(r.position),
      counts = {};
    for (const company of r.companies)
      counts[company.type] = (counts[company.type] || 0) + 1;
    const main = Object.keys(counts).sort(
      (a, b) => counts[b] - counts[a] || a.localeCompare(b),
    )[0];
    overlays.append(
      svg("circle", {
        cx: c.x,
        cy: c.y - 4,
        r: 12,
        fill: r.side === "BLUE" ? "#366ea4" : "#a5574c",
        stroke: "#e6e4c7",
        "stroke-width": 1,
      }),
    );
    overlays.append(
      svg(
        "text",
        { x: c.x, y: c.y, "text-anchor": "middle" },
        r.role === "DIVISION_HQ"
          ? "师"
          : r.role === "BRIGADE_HQ"
            ? "旅"
            : symbols[main],
      ),
    );
    overlays.append(
      svg(
        "text",
        { x: c.x, y: c.y + 13, "text-anchor": "middle", class: "unit-label" },
        r.name.length > 7 ? r.name.slice(0, 7) + "…" : r.name,
      ),
    );
  }
}
function renderInspector() {
  if (!draft || !selected) return;
  const s = draft.scenario,
    cell = s.cells.find((c) => same(c.position, selected));
  $("selection").textContent =
    `${String(selected.q).padStart(2, "0")} / ${String(selected.r).padStart(2, "0")}`;
  const unit = s.regiments.find((r) => same(r.position, selected));
  const terrainNames = {
    PLAIN: "平原",
    FOREST: "森林",
    HILL: "高地",
    MOUNTAIN: "山地",
    CITY: "城市",
  };
  $("selection-summary").textContent = unit
    ? `${unit.side === "BLUE" ? "蓝方" : "红方"} · ${unit.name} · ${unit.companies.length} 连`
    : `${terrainNames[cell.terrain]} · 空置地块`;

  $("terrain").value = cell.terrain;
  $("fortification").value = cell.fortification;
  const neighbors = [
    [1, 0],
    [-1, 0],
    [0, 1],
    [0, -1],
    [1, -1],
    [-1, 1],
  ]
    .map(([q, r]) => ({ q: selected.q + q, r: selected.r + r }))
    .filter((p) => s.cells.some((c) => same(c.position, p)));
  optionList(
    $("neighbor"),
    neighbors.map((p) => [key(p), key(p)]),
  );
  renderEdge();
  const supply = s.supplies.find((v) => same(v.position, selected));
  $("supply-id").value = supply?.id || `supply-${selected.q}-${selected.r}`;
  $("supply-side").value = supply?.side || "BLUE";
  $("stock").value = supply?.stock ?? 500;
  const r = s.regiments.find((v) => same(v.position, selected));
  $("regiment-id").value = r?.id || `reg-${selected.q}-${selected.r}`;
  $("regiment-name").value = r?.name || "新编团";
  $("side").value = r?.side || "BLUE";
  $("role").value = r?.role || "REGIMENT";
  renderBrigades(r?.brigadeId || "");
  $("companies").replaceChildren();
  for (const c of r?.companies || [
    {
      id: uid("company"),
      type: "INFANTRY",
      equipment: "FOOT",
      maxHp: 100,
      hp: 100,
    },
  ])
    addCompany(c);
  showCompany(0);
}
function renderBrigades(current = "") {
  optionList(
    $("brigade"),
    [
      ["", "无"],
      ...draft.scenario.regiments
        .filter((r) => r.role === "BRIGADE_HQ" && r.side === $("side").value)
        .map((r) => [r.id, r.name]),
    ],
    current,
  );
}
function renderEdge() {
  if (!selected || !draft) return;
  const neighbor = pos($("neighbor").value);
  const e = draft.scenario.edges.find(
    (e) =>
      (same(e.a, selected) && same(e.b, neighbor)) ||
      (same(e.b, selected) && same(e.a, neighbor)),
  );
  $("river").checked = e?.river || false;
  $("road").checked = e?.road || false;
  $("bridge").value = e?.bridge || "NONE";
}
function addCompany(
  c = {
    id: uid("company"),
    type: "INFANTRY",
    equipment: "FOOT",
    maxHp: 100,
    hp: 100,
  },
) {
  const div = document.createElement("div");
  div.className = "company";
  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "secondary";
  remove.textContent = "移除此连";
  remove.addEventListener("click", () => {
    div.remove();
    showCompany(activeCompany);
  });
  div.append(remove);
  const field = (label, name, node) => {
    const l = document.createElement("label");
    l.textContent = label;
    node.dataset.field = name;
    l.append(node);
    div.append(l);
    return node;
  };
  const id = document.createElement("input");
  id.type = "hidden";
  id.dataset.field = "id";
  id.value = c.id;
  div.append(id);
  const type = field("类型", "type", document.createElement("select"));
  optionList(type, Object.entries(names), c.type);
  type.addEventListener("change", () => showCompany(activeCompany));
  const eq = field("装备形态", "equipment", document.createElement("select"));
  optionList(eq, Object.entries(equipment), c.equipment);
  for (const [name, label] of [
    ["maxHp", "最大 HP"],
    ["hp", "当前 HP"],
  ]) {
    const input = field(label, name, document.createElement("input"));
    input.type = "number";
    input.min = name === "hp" ? 0 : 1;
    input.max = 1000;
    input.required = true;
    input.value = c[name];
  }
  $("companies").append(div);
  showCompany($("companies").children.length - 1);
}
async function renderGames() {
  $("game-detail").textContent = "选择实验查看其冻结初始输入。";
  if (!revision) {
    $("revision-info").textContent = "双方城市师部齐备后可冻结部署。";
    gameRows = [];
    renderGamePage();
    return;
  }
  $("revision-info").textContent =
    `部署 v${revision.draftVersion} · 指纹 ${revision.contentHash.slice(0, 16)}${revision.draftVersion !== draft.version ? " · 当前草稿已有后续调整" : ""}`;
  $("revision-info").title = `${revision.id} / SHA-256 ${revision.contentHash}`;
  gameRows = await api(`/revisions/${revision.id}/games`);
  gamePage = 0;
  renderGamePage();
}
function renderGamePage() {
  const size = gamePageSize();
  gamePage = Math.min(
    gamePage,
    Math.max(0, Math.ceil(gameRows.length / size) - 1),
  );
  $("games").replaceChildren();
  for (const [i, game] of gameRows
    .slice(gamePage * size, gamePage * size + size)
    .entries()) {
    const box = document.createElement("div");
    box.className = "game";
    const title = document.createElement("strong");
    title.textContent = `实验 ${String(gamePage * size + i + 1).padStart(2, "0")} · 待命`;
    const id = document.createElement("p");
    id.textContent = `部署 v${revision.draftVersion}`;
    id.title = game.id;
    const seed = document.createElement("code");
    seed.textContent = `种子 ${game.seed} / 第 ${game.day} 天`;
    const button = document.createElement("button");
    button.className = "secondary";
    button.textContent = "档案";
    button.addEventListener("click", () =>
      run(async () => {
        const state = await api(`/games/${game.id}`);
        $("game-detail").textContent = JSON.stringify(state, null, 2);
        $("game-dialog").showModal();
        message("已读取实验部署档案。");
      }),
    );
    const enter = document.createElement("button");
    enter.textContent = "进入推演";
    enter.addEventListener("click", () => run(() => openBattle(game.id)));
    const actions = document.createElement("div");
    actions.className = "game-actions";
    actions.append(button, enter);
    box.append(title, id, seed, actions);
    $("games").append(box);
  }
  paging();
}
function download(value, filename) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
$("auth-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(async () => {
    token = $("token").value.trim();
    $("token").value = "";
    await api("/system");
    await listDrafts();
    const remembered = localStorage.getItem("tactical-draft-id");
    if ([...$("draft-list").options].some((o) => o.value === remembered))
      await loadDraft(remembered);
    else message("连接成功，请创建地图或载入预置场景。");
    $("auth-dialog").close();
  });
});
$("disconnect").addEventListener("click", () => {
  token = "";
  controls();
  message("已清除本页令牌，重新输入后可继续。");
});
$("create-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(async () => {
    const result = await api("/scenarios", "POST", {
      name: $("create-name").value,
      width: Number($("width").value),
      height: Number($("height").value),
    });
    selected = null;
    revision = null;
    await loadDraft(result.id);
    $("scenario-dialog").close();
  });
});
$("draft-list").addEventListener("change", () => {
  if ($("draft-list").value) run(() => loadDraft($("draft-list").value));
});
$("reload").addEventListener("click", () =>
  run(async () => {
    if (draft) await loadDraft(draft.id);
    else {
      await listDrafts();
      message("已刷新草稿列表。");
    }
  }),
);
$("preset").addEventListener("click", () =>
  run(async () => {
    const preset = await api("/presets/river-valley");
    const result = await api("/scenarios/import", "POST", preset);
    selected = null;
    revision = null;
    await loadDraft(result.id);
    $("scenario-dialog").close();
  }),
);
$("preset-recon").addEventListener("click", () =>
  run(async () => {
    const preset = await api("/presets/recon-pursuit");
    const result = await api("/scenarios/import", "POST", preset);
    selected = null;
    revision = null;
    await loadDraft(result.id);
  }),
);
$("import-file").addEventListener("change", () =>
  run(async () => {
    const file = $("import-file").files[0];
    if (!file) return;
    try {
      if (file.size > 65536) throw new Error("导入文件不能超过 64 KiB");
      const scenario = JSON.parse(await file.text());
      const result = await api("/scenarios/import", "POST", scenario);
      selected = null;
      revision = null;
      await loadDraft(result.id);
      $("scenario-dialog").close();
    } finally {
      $("import-file").value = "";
    }
  }),
);
$("export").addEventListener("click", () =>
  run(async () => {
    download(await api(`/scenarios/${draft.id}/export`), "scenario.json");
    message("已导出服务端草稿，可再次导入。");
  }),
);
$("cell-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(() =>
    save((s) => {
      const cell = s.cells.find((c) => same(c.position, selected));
      cell.terrain = $("terrain").value;
      cell.fortification = Number($("fortification").value);
    }),
  );
});
$("neighbor").addEventListener("change", renderEdge);
$("edge-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(() =>
    save((s) => {
      const b = pos($("neighbor").value);
      s.edges = s.edges.filter(
        (e) =>
          !(
            (same(e.a, selected) && same(e.b, b)) ||
            (same(e.b, selected) && same(e.a, b))
          ),
      );
      s.edges.push({
        a: selected,
        b,
        river: $("river").checked,
        bridge: $("bridge").value,
        road: $("road").checked,
      });
    }),
  );
});
$("supply-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(() =>
    save((s) => {
      s.supplies = s.supplies.filter((v) => !same(v.position, selected));
      s.supplies.push({
        id: $("supply-id").value,
        position: selected,
        side: $("supply-side").value,
        stock: Number($("stock").value),
      });
    }),
  );
});
$("remove-supply").addEventListener("click", () =>
  run(() =>
    save(
      (s) =>
        (s.supplies = s.supplies.filter((v) => !same(v.position, selected))),
    ),
  ),
);
$("side").addEventListener("change", () => {
  if (draft) renderBrigades();
});
$("add-company").addEventListener("click", () => {
  addCompany();
});
$("regiment-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const invalid = [...e.target.elements].find(
    (f) => f.willValidate && !f.checkValidity(),
  );
  if (invalid) {
    const company = invalid.closest(".company");
    if (company) showCompany([...$("companies").children].indexOf(company));
    invalid.reportValidity();
    return;
  }

  run(() =>
    save((s) => {
      const companies = [...$("companies").children].map((div) => {
        const c = {};
        div
          .querySelectorAll("[data-field]")
          .forEach(
            (f) =>
              (c[f.dataset.field] =
                f.type === "number" ? Number(f.value) : f.value),
          );
        return c;
      });
      s.regiments = s.regiments.filter((r) => !same(r.position, selected));
      s.regiments.push({
        id: $("regiment-id").value,
        name: $("regiment-name").value,
        side: $("side").value,
        role: $("role").value,
        position: selected,
        brigadeId: $("brigade").value,
        companies,
      });
    }),
  );
});
$("remove-regiment").addEventListener("click", () =>
  run(() =>
    save(
      (s) =>
        (s.regiments = s.regiments.filter((r) => !same(r.position, selected))),
    ),
  ),
);
$("freeze").addEventListener("click", () =>
  run(async () => {
    revision = await api(`/scenarios/${draft.id}/revisions`, "POST", {
      expectedVersion: draft.version,
    });
    await loadHistory(revision.id);
    message("冻结成功。可以多次启动独立实验，或继续编辑原草稿。");
  }),
);
$("revision-list").addEventListener("change", () =>
  run(async () => {
    revision = revisions.find((r) => r.id === $("revision-list").value);
    await renderGames();
    message("已切换冻结版本。");
  }),
);
$("export-revision").addEventListener("click", () =>
  run(async () => {
    download(
      await api(`/revisions/${revision.id}/export`),
      "frozen-scenario.json",
    );
    message("已导出冻结输入。");
  }),
);
$("create-game").addEventListener("click", () =>
  run(async () => {
    const seed = Number($("seed").value);
    if (!Number.isSafeInteger(seed))
      throw new Error("种子必须是 JavaScript 可精确表示的整数");
    const maxIterations = Number($("max-iterations").value);
    if (
      !Number.isInteger(maxIterations) ||
      maxIterations < 1 ||
      maxIterations > 8
    )
      throw new Error("迭代上限必须为 1–8");
    await api("/games", "POST", {
      revisionId: revision.id,
      seed,
      maxIterations,
    });
    await loadHistory();
    message("独立实验已创建；点击「进入推演」下达双方命令。");
  }),
);
$("openapi").addEventListener("click", (e) => {
  e.preventDefault();
  run(async () => {
    download(await api("/openapi"), "openapi.json");
    message("已读取代码生成的 OpenAPI。");
  });
});
document
  .querySelectorAll("[data-tab]")
  .forEach((b) =>
    b.addEventListener("click", () => selectPanel(b.dataset.tab)),
  );
document
  .querySelectorAll("[data-close]")
  .forEach((b) =>
    b.addEventListener("click", () => $(b.dataset.close).close()),
  );
$("connect-menu").addEventListener("click", () => $("auth-dialog").showModal());
$("new-scenario").addEventListener("click", () =>
  $("scenario-dialog").showModal(),
);
$("fullscreen").addEventListener("click", async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch {
    message("全屏请求未获浏览器允许，可使用 F11。", true);
  }
});
for (const [id, fn] of [
  [
    "games-prev",
    () => {
      gamePage--;
      renderGamePage();
    },
  ],
  [
    "games-next",
    () => {
      gamePage++;
      renderGamePage();
    },
  ],
  [
    "events-prev",
    () => {
      eventPage--;
      renderEvents();
    },
  ],
  [
    "events-next",
    () => {
      eventPage++;
      renderEvents();
    },
  ],
])
  $(id).addEventListener("click", fn);
$("zoom-in").addEventListener("click", () => zoom(0.8));
$("zoom-out").addEventListener("click", () => zoom(1.25));
$("zoom-reset").addEventListener("click", () => {
  if (mapBounds) {
    camera = { ...mapBounds };
    applyCamera();
  }
});
$("map").addEventListener(
  "wheel",
  (e) => {
    if (!camera) return;
    e.preventDefault();
    zoom(e.deltaY < 0 ? 0.9 : 1.1, mapPoint(e));
  },
  { passive: false },
);
$("map").addEventListener("pointerdown", (e) => {
  if (!camera || e.button !== 0) return;
  drag = {
    point: mapPoint(e),
    x: e.clientX,
    y: e.clientY,
    initial: { ...camera },
  };
  dragged = false;
});
$("map").addEventListener("pointermove", (e) => {
  if (!drag) return;
  if (Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 5) {
    dragged = true;
    const p = mapPoint(e);
    camera.x += drag.point.x - p.x;
    camera.y += drag.point.y - p.y;
    applyCamera();
  }
});
window.addEventListener("pointerup", () => {
  drag = null;
  setTimeout(() => (dragged = false), 0);
});
window.addEventListener("resize", () => renderGamePage());

let libraryRows = [],
  libraryPage = 0,
  renameTarget = null;
function switchHeader(library) {
  activeView = library ? "library" : "workspace";
  document.querySelector(".game-board").hidden = library;
  $("library-view").hidden = !library;
  $("workspace-tab").setAttribute("aria-pressed", String(!library));
  $("library-tab").setAttribute("aria-pressed", String(library));
  $("battle-tab").setAttribute("aria-pressed", "false");
  document.querySelector(".scenario-bar").hidden = false;
  $("battle-bar").hidden = true;
  $("turn-panel").hidden = true;
  $("turn-operations").hidden = true;
  document.querySelector(".inspector").hidden = false;
  document.querySelector(".operations").hidden = false;
  document.querySelector(".operation-title > span").textContent =
    "军事学说实验场 / 初始部署";
  document.querySelector(".map-caption small").textContent = "DEPLOYMENT";
  document.querySelector(".map-footer > span").textContent =
    "点击选格 · 滚轮缩放 · 按住拖动平移";
  if (!draft && !library) {
    selected = null;
    $("map").replaceChildren();
  }
  if (draft && !library) {
    if (!draft.scenario.cells.some((c) => same(c.position, selected)))
      selected = draft.scenario.cells[0].position;
    camera = null;
    renderMap();
    renderInspector();
  }
}
function filteredLibrary() {
  const query = $("library-search").value.trim().toLowerCase();
  return libraryRows.filter(
    (d) =>
      ($("show-archived").checked || !d.archived) &&
      d.name.toLowerCase().includes(query),
  );
}
function libraryControls() {
  const pages = Math.ceil(filteredLibrary().length / 6);
  $("library-prev").disabled = busy || libraryPage === 0;
  $("library-next").disabled = busy || libraryPage + 1 >= pages;
}
function renderLibrary() {
  const rows = filteredLibrary(),
    pages = Math.ceil(rows.length / 6);
  libraryPage = Math.min(libraryPage, Math.max(0, pages - 1));
  $("library-page").textContent = pages
    ? `${libraryPage + 1} / ${pages}`
    : "0 / 0";
  $("library-cards").replaceChildren(
    ...rows.slice(libraryPage * 6, libraryPage * 6 + 6).map((d) => {
      const card = document.createElement("article");
      card.className = "library-card";
      card.dataset.scenarioId = d.id;
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.textContent = d.archived ? "已归档" : "部署草稿";
      const title = document.createElement("h3");
      title.textContent = d.name;
      const meta = document.createElement("p");
      meta.textContent = `${d.width} × ${d.height} · v${d.version} · ${d.revisions} 冻结版本 · ${d.games} 实验`;
      const actions = document.createElement("div");
      actions.className = "toolbar";
      const action = (label, fn) => {
        const b = document.createElement("button");
        b.className = "secondary";
        b.textContent = label;
        b.disabled = !token;
        b.addEventListener("click", fn);
        actions.append(b);
      };
      action("打开部署", () =>
        run(async () => {
          await loadDraft(d.id);
          switchHeader(false);
        }),
      );
      action("重命名", () => {
        renameTarget = d;
        $("rename-name").value = d.name;
        $("rename-dialog").showModal();
      });
      action("复制", () =>
        run(async () => {
          await api(`/scenarios/${d.id}/copy`, "POST", {
            expectedVersion: d.version,
          });
          await listDrafts();
          message("已复制为独立草稿，不继承实验状态。");
        }),
      );
      action(d.archived ? "恢复" : "归档", () =>
        run(async () => {
          const updated = await api(`/scenarios/${d.id}/archive`, "POST", {
            expectedVersion: d.version,
            archived: !d.archived,
          });
          if (draft?.id === d.id) draft = updated;
          await listDrafts();
          message(
            d.archived ? "战场已恢复。" : "战场已归档，冻结版本和实验保留。",
          );
        }),
      );
      action("删除", () => {
        if (!confirm(`删除「${d.name}」草稿？已有冻结版本的战场只能归档。`))
          return;
        run(async () => {
          await api(
            `/scenarios/${d.id}?expectedVersion=${d.version}`,
            "DELETE",
          );
          if (draft?.id === d.id) {
            draft = null;
            selected = null;
            revision = null;
            gameRows = [];
            eventRows = [];
            $("map").replaceChildren();
            $("draft-meta").textContent = "尚未部署战场";
            localStorage.removeItem("tactical-draft-id");
            renderGamePage();
            renderEvents();
          }
          await listDrafts();
          message("已删除未冻结草稿。");
        });
      });
      card.append(badge, title, meta, actions);
      return card;
    }),
  );
  libraryControls();
}
$("library-tab").addEventListener("click", () => {
  switchHeader(true);
  if (token)
    run(async () => {
      await listDrafts();
      message("战场档案已载入。");
    });
});
$("workspace-tab").addEventListener("click", () => {
  switchHeader(false);
  if (draft) run(() => loadHistory());
});
$("library-refresh").addEventListener("click", () =>
  run(async () => {
    await listDrafts();
    message("战场列表已刷新。");
  }),
);
for (const id of ["library-search", "show-archived"])
  $(id).addEventListener("input", () => {
    libraryPage = 0;
    renderLibrary();
  });
$("library-prev").addEventListener("click", () => {
  libraryPage--;
  renderLibrary();
});
$("library-next").addEventListener("click", () => {
  libraryPage++;
  renderLibrary();
});
$("rename-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(async () => {
    const updated = await api(`/scenarios/${renameTarget.id}/name`, "PATCH", {
      expectedVersion: renameTarget.version,
      name: $("rename-name").value,
    });
    if (draft?.id === updated.id) {
      draft = updated;
      renderMap();
    }
    await listDrafts();
    $("rename-dialog").close();
    message("战场名称已更新；冻结版本保留原名称。");
  });
});

let activeView = "workspace",
  battleId = null,
  turn = null,
  battleSide = "BLUE",
  localOrders = { BLUE: [], RED: [] },
  lastDay = null;
const eventNames = {
  MOVED: "移动",
  CONTACT: "接触",
  EXECUTING: "执行",
  COMPLETED: "完成",
  BLOCKED: "受阻",
  IMPASSABLE: "不可通行",
  DEFERRED: "待续",
};
const batchFor = (side) => turn?.[side.toLowerCase()];
function ordersDirty() {
  const sorted = (orders) =>
    [...orders].sort((a, b) => a.regimentId.localeCompare(b.regimentId));
  return (
    JSON.stringify(sorted(localOrders[battleSide])) !==
    JSON.stringify(sorted(batchFor(battleSide)?.orders || []))
  );
}
function battleControls() {
  const batch = batchFor(battleSide),
    locked = batch?.committed || turn?.status === "LIMIT_REACHED";
  for (const id of ["route-undo", "route-clear", "order-unit"])
    $(id).disabled = busy || !turn || locked || !$("order-unit").value;
  $("submit-orders").disabled = busy || !turn || locked;
  $("commit-orders").disabled =
    busy || !batch?.submitted || locked || ordersDirty();
  $("resolve-day").disabled = busy || turn?.status !== "LOCKED";
  $("export-day").disabled = busy || !lastDay;
  $("export-events").disabled = busy || !lastDay;
  $("turn-reload").disabled = busy || !battleId;
}
async function openBattle(id) {
  battleId = id;
  turn = await api(`/games/${id}/turn`);
  const game = await api(`/games/${id}`);
  localOrders = {
    BLUE: structuredClone(turn.blue.orders),
    RED: structuredClone(turn.red.orders),
  };
  lastDay = null;
  if (turn.day > 1) lastDay = await api(`/games/${id}/days/${turn.day - 1}`);
  activeView = "battle";
  document.querySelector(".operation-title > span").textContent =
    "军事学说实验场 / 运行战场";
  document.querySelector(".map-caption small").textContent = "LIVE OPERATIONS";
  document.querySelector(".map-footer > span").textContent =
    "选定团 · 点击相邻格规划路线 · 滚轮缩放 · 拖动平移";
  document.querySelector(".game-board").hidden = false;
  $("library-view").hidden = true;
  document.querySelector(".scenario-bar").hidden = true;
  $("battle-bar").hidden = false;
  document.querySelector(".inspector").hidden = true;
  document.querySelector(".operations").hidden = true;
  $("turn-panel").hidden = false;
  $("turn-operations").hidden = false;
  for (const name of ["workspace", "library", "battle"])
    $(name + "-tab").setAttribute("aria-pressed", String(name === "battle"));
  $("battle-name").textContent = turn.world.name;
  $("battle-meta").textContent =
    `实验 ${id.slice(0, 8)} · 种子 ${game.seed} · ${game.rulesVersion}`;
  optionList(
    $("day-list"),
    turn.day > 1
      ? Array.from({ length: turn.day - 1 }, (_, i) => [
          String(i + 1),
          `第 ${i + 1} 天`,
        ])
      : [["", "尚无结算"]],
    lastDay ? String(lastDay.result.day) : "",
  );
  camera = null;
  renderTurn();
  showDayEvents();
  localStorage.setItem("tactical-game-id", id);
  message(`已进入推演 · 第 ${turn.day} 天 · 依次为双方下令并确认锁定。`);
}
function renderTurn() {
  $("turn-day").textContent = `第 ${turn.day} 天`;
  $("turn-phase").textContent =
    turn.status === "LIMIT_REACHED"
      ? "已达 60 天实验上限"
      : turn.status === "LOCKED"
        ? "双方已锁定"
        : "制定命令";
  const batch = batchFor(battleSide);
  $("side-status").textContent = batch.committed
    ? "命令已锁定，等待当日结算"
    : `命令版本 ${batch.version} · ${ordersDirty() ? "有未提交修改" : batch.submitted ? "已提交，待确认" : "尚未提交"}`;
  for (const side of ["BLUE", "RED"]) {
    const b = batchFor(side);
    $("side-" + side.toLowerCase()).setAttribute(
      "aria-pressed",
      String(side === battleSide),
    );
    $(side.toLowerCase() + "-lock").textContent =
      `${side === "BLUE" ? "蓝方" : "红方"} · ${b.committed ? "已锁定" : b.submitted ? "待确认" : "待提交"} · ${b.orders.length} 条命令`;
  }
  const units = turn.world.regiments.filter(
    (r) => r.side === battleSide && r.role !== "DIVISION_HQ",
  );
  const previous = $("order-unit").value;
  optionList(
    $("order-unit"),
    units.length
      ? units.map((r) => [r.id, `${r.name} · ${key(r.position)}`])
      : [["", "无可移动部队"]],
    units.some((r) => r.id === previous) ? previous : units[0]?.id || "",
  );
  renderRoute();
  renderMap();
  battleControls();
}
function renderRoute() {
  const unit = turn.world.regiments.find((r) => r.id === $("order-unit").value);
  const route =
    localOrders[battleSide].find((o) => o.regimentId === unit?.id)?.route || [];
  $("order-unit-info").textContent = unit
    ? unit.companies
        .map((c) => `${names[c.type]} / ${equipment[c.equipment]} ${c.hp}HP`)
        .join(" · ")
    : "本方可提交空命令表，全体原地待命。";
  $("order-route").textContent = route.length
    ? `${key(unit.position)} → ${route.map(key).join(" → ")}`
    : "原地待命。依次点击相邻地图格添加路径。";
  $("order-route").title = $("order-route").textContent;
}
function currentLocalOrder() {
  const id = $("order-unit").value;
  if (!id) return null;
  let order = localOrders[battleSide].find((o) => o.regimentId === id);
  if (!order) {
    order = { orderId: uid("move"), regimentId: id, route: [] };
    localOrders[battleSide].push(order);
  }
  return order;
}
function chooseWaypoint(point) {
  selected = point;
  if (
    !batchFor(battleSide).committed &&
    turn.status !== "LIMIT_REACHED" &&
    $("order-unit").value
  ) {
    const order = currentLocalOrder();
    const from =
      order.route.at(-1) ||
      turn.world.regiments.find((r) => r.id === order.regimentId).position;
    const dq = point.q - from.q,
      dr = point.r - from.r;
    if (Math.max(Math.abs(dq), Math.abs(dr), Math.abs(dq + dr)) !== 1) {
      message("请点击路线末端的相邻格；撤销后可重新规划。", true);
      renderMap();
      return;
    }
    if (order.route.length >= 64) {
      message("单条路线最多 64 步。", true);
      return;
    }
    order.route.push(point);
  }
  renderTurn();
}
function showDayEvents() {
  eventRows = lastDay?.result.events || [];
  eventPage = 0;
  renderEvents();
  $("turn-result").textContent = lastDay
    ? `第 ${lastDay.result.day} 天已结算 · ${eventRows.length} 条记录 · 状态指纹 ${lastDay.result.stateHash.slice(0, 20)}`
    : "双方均须提交并确认；重复结算请求不会多推进一天。";
}
$("battle-tab").addEventListener("click", () =>
  run(async () => {
    const id =
      battleId ||
      gameRows.at(-1)?.id ||
      localStorage.getItem("tactical-game-id");
    if (!id) {
      message("请先在部署战场冻结部署并启动独立实验。");
      return;
    }
    await openBattle(id);
  }),
);
$("turn-reload").addEventListener("click", () =>
  run(() => openBattle(battleId)),
);
for (const side of ["BLUE", "RED"])
  $("side-" + side.toLowerCase()).addEventListener("click", () => {
    battleSide = side;
    renderTurn();
  });
$("order-unit").addEventListener("change", () => {
  renderRoute();
  renderMap();
  battleControls();
});
$("route-undo").addEventListener("click", () => {
  currentLocalOrder()?.route.pop();
  renderTurn();
});
$("route-clear").addEventListener("click", () => {
  const order = currentLocalOrder();
  if (order) order.route = [];
  renderTurn();
});
$("submit-orders").addEventListener("click", () =>
  run(async () => {
    const side = battleSide;
    turn = await api(`/games/${battleId}/orders/${side}`, "PUT", {
      day: turn.day,
      expectedVersion: batchFor(side).version,
      orders: localOrders[side],
    });
    localOrders[side] = structuredClone(batchFor(side).orders);
    renderTurn();
    message("本方命令已提交，请确认并锁定。");
  }),
);
$("commit-orders").addEventListener("click", () =>
  run(async () => {
    turn = await api(`/games/${battleId}/commit/${battleSide}`, "POST", {
      day: turn.day,
      expectedVersion: batchFor(battleSide).version,
    });
    renderTurn();
    message("本方命令已锁定。");
  }),
);
$("resolve-day").addEventListener("click", () =>
  run(async () => {
    const day = turn.day;
    await api(`/games/${battleId}/resolve`, "POST", { day });
    await openBattle(battleId);
    message(`第 ${day} 天结算完成，已进入第 ${turn.day} 天命令阶段。`);
  }),
);
$("day-list").addEventListener("change", () =>
  run(async () => {
    if (!$("day-list").value) return;
    lastDay = await api(`/games/${battleId}/days/${$("day-list").value}`);
    showDayEvents();
    message("已载入历史结算记录；地图保持当前部署位置。");
  }),
);
$("export-day").addEventListener("click", () =>
  download(lastDay, `day-${lastDay.result.day}-run.json`),
);
$("export-events").addEventListener("click", () =>
  run(async () => {
    const response = await fetch(
      `/api/v1/games/${battleId}/days/${lastDay.result.day}/events`,
      {
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(10000),
      },
    );
    if (!response.ok) throw new Error("事件导出失败，请重新连接后重试。");
    const url = URL.createObjectURL(await response.blob()),
      a = document.createElement("a");
    a.href = url;
    a.download = `day-${lastDay.result.day}-events.jsonl`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }),
);
controls();
$("auth-dialog").showModal();
