const $ = (id) => document.getElementById(id);
let companyProfiles = {},
  draftMarkers = {};
let replay = null,
  replayDay = null,
  replayTimer = null,
  replayPlaying = false,
  replayRequest = 0,
  mapInspect = false;
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
const key = (p) => (p ? `${p.q},${p.r}` : "—");
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
          focusEvent(e);
          showEvent(e);
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
  for (const e of document.querySelectorAll(
    "#zoom-in,#zoom-out,#zoom-reset,#fullscreen,[data-turn-tab],[data-tab],#replay-pause",
  ))
    e.disabled = false;
  if (replay)
    for (const id of ["action-page", "doctrine-page"])
      document.querySelector(`[data-turn-tab="${id}"]`).disabled = true;
  document.body.setAttribute("aria-busy", String(busy));
  $("retry-request").disabled = busy;
}
async function run(action) {
  if (busy) return;
  busy = true;
  controls();
  message("请求处理中…");
  $("retry-request").hidden = true;
  try {
    await action();
  } catch (error) {
    if (replayPlaying) stopReplay();
    $("retry-request").hidden = false;
    message(
      error.message || "服务不可用，请检查服务后重新连接或重新载入。",
      true,
    );
  } finally {
    busy = false;
    controls();
    if ($("status").textContent === "请求处理中…") message("就绪。");
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
  draftMarkers = await api(`/scenarios/${id}/markers`);
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
  draftMarkers = await api(`/scenarios/${draft.id}/markers`);
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
  const s = activeView === "battle" ? battleWorld() : draft.scenario,
    map = $("map");
  map.replaceChildren();
  $("map-inspect").hidden = activeView !== "battle";
  $("map-inspect").setAttribute("aria-pressed", String(mapInspect || !!replay));
  if (activeView === "battle")
    document.querySelector(".map-footer > span").textContent =
      replay || mapInspect
        ? "检视模式 · 点击部队定位详情与日志 · 滚轮缩放 / 拖动平移"
        : "规划模式 · 点击相邻格编排路线 · 打开检视查看部队";
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
      ? replay
        ? `${s.name} · 回放 D${replay.view.day} / t${replay.tick}`
        : `${s.name} · 第 ${turn.day} 天待命`
      : `${s.name} · v${draft.version} · ${s.width}×${s.height}`;
  document.querySelector(".map-caption small").textContent =
    activeView === "battle"
      ? replay
        ? "HISTORICAL REPLAY"
        : "LIVE OPERATIONS"
      : "DEPLOYMENT";
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
        const observedUnit = s.regiments.find((r) =>
          same(r.position, cell.position),
        );
        if (
          observedUnit &&
          (replay || mapInspect || !$("report-page").hidden)
        ) {
          inspectUnit(observedUnit.id);
          return;
        }
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
  if (activeView === "battle" && !replay) {
    const unit = battleWorld().regiments.find(
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
    const shownUnit = s.regiments.find((r) => same(r.position, cell.position));
    const contact =
      activeView === "battle" && $("perspective").value === "DIVISION"
        ? playerView?.knowledge.contacts[shownUnit?.id]
        : null;
    overlays.append(
      svg(
        "text",
        { x: c.x, y: c.y + 23, "text-anchor": "middle", class: "coord" },
        contact
          ? `${key(cell.position)} · D${contact.observedDay}`
          : key(cell.position),
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
  renderCommunications(s, overlays);
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
    const marker = (
      activeView === "battle" ? playerView?.markers : draftMarkers
    )?.[r.id];
    const unitMark = svg("g", {
      "data-unit-marker": r.id,
      "pointer-events": "none",
      tabindex: 0,
      role: "button",
      "aria-label": `${r.name} · ${names[marker?.main || main]}`,
    });
    if (r.role !== "REGIMENT")
      unitMark.append(
        svg(
          "text",
          { x: c.x, y: c.y, "text-anchor": "middle" },
          r.role === "DIVISION_HQ" ? "师" : "旅",
        ),
      );
    else unitMark.append(unitIcon(marker?.main || main, c.x - 10, c.y - 15));
    if (marker?.bottleneck) {
      unitMark.append(
        svg("rect", {
          x: c.x + 10,
          y: c.y - 11,
          width: 16,
          height: 16,
          rx: 3,
          fill: "#202e32",
          stroke: "#e4c876",
          "stroke-width": 0.6,
        }),
      );
      unitMark.append(
        unitIcon(
          marker.bottleneckEquipment === "FOOT" ? "FOOT" : marker.bottleneck,
          c.x + 11,
          c.y - 10,
          0.7,
        ),
      );
    }
    unitMark.append(
      svg(
        "title",
        {},
        `${r.name} · ${r.id}\n主体：${names[marker?.main || main]}${marker?.bottleneck ? "；瓶颈：" + names[marker.bottleneck] + " / " + equipment[marker.bottleneckEquipment] : ""}\n当前位置基础速度：${marker?.speed ?? "—"}`,
      ),
    );
    const inspect = () => {
      if (activeView === "battle") inspectUnit(r.id);
      else {
        selected = r.position;
        renderMap();
        renderInspector();
        selectPanel("unit-panel");
      }
    };
    unitMark.addEventListener("click", inspect);
    unitMark.addEventListener("keydown", (e) => {
      if (e.key === "Enter") inspect();
    });
    overlays.append(unitMark);
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
  renderRelay();
  renderInitialKnowledge();
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
      maxHp: companyProfiles.INFANTRY.suggestedHp,
      hp: companyProfiles.INFANTRY.suggestedHp,
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
    maxHp: companyProfiles.INFANTRY.suggestedHp,
    hp: companyProfiles.INFANTRY.suggestedHp,
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
    companyProfiles = await api("/rules/company-profiles");
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
  stopReplay();
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
  lastDay = null,
  playerView = null,
  localOperations = { BLUE: null, RED: null };
const eventNames = {
  SUCCEEDED: "任务达成",
  FAILED: "任务失败",
  MOVED: "移动",
  CONTACT: "接触",
  EXECUTING: "执行",
  COMPLETED: "完成",
  BLOCKED: "受阻",
  IMPASSABLE: "不可通行",
  DEFERRED: "待续",
  WAITING: "等待条件",
  DAMAGE: "连队战损",
  DESTROYED: "部队被歼灭",
  DISPLACED: "被驱离",
  ADVANCED: "占领目标",
  WITHDRAW: "学说撤退",
  HOLD: "学说防御",
  RECOVERED: "恢复 HP",
  SUPPLY_USED: "消耗补给",
  REST_FAILED: "休整失败",
  BOMBARD_MISSED: "炮击落空",
};
const batchFor = (side) => turn?.[side.toLowerCase()];
function ordersDirty(side = battleSide) {
  const sorted = (orders) =>
    [...orders].sort((a, b) => a.regimentId.localeCompare(b.regimentId));
  return (
    JSON.stringify(sorted(localOrders[side])) !==
      JSON.stringify(sorted(batchFor(side)?.orders || [])) ||
    JSON.stringify(localOperations[side]) !==
      JSON.stringify(batchFor(side)?.operation || null)
  );
}
function battleControls() {
  const batch = batchFor(battleSide),
    locked = !!replay || batch?.committed || turn?.status === "LIMIT_REACHED";
  for (const id of [
    "route-undo",
    "route-clear",
    "order-unit",
    "order-action",
    "doctrine-template",
    "doctrine-threshold",
    "doctrine-supply",
  ])
    $(id).disabled = busy || !turn || locked || !$("order-unit").value;
  for (const id of [
    "op-mode",
    "op-brigade",
    "op-theater",
    "op-task",
    "op-fallback",
    "op-after",
    "op-save",
    "op-demo",
  ])
    $(id).disabled = busy || !turn || locked;
  $("submit-orders").disabled = busy || !turn || locked;
  $("commit-orders").disabled =
    busy || !batch?.submitted || locked || ordersDirty();
  $("resolve-day").disabled = busy || !!replay || turn?.status !== "LOCKED";
  $("export-day").disabled = busy || !lastDay;
  $("export-events").disabled = busy || !lastDay;
  $("turn-reload").disabled = busy || !battleId;
  $("day-list").disabled = busy || !lastDay;
  replayControls();
}
async function openBattle(id) {
  stopReplay();
  replay = null;
  replayDay = null;
  mapInspect = false;
  document.body.classList.remove("replaying");
  const commandTab = document.querySelector('[data-turn-tab="action-page"]');
  commandTab.disabled = false;
  commandTab.click();
  battleId = id;
  $("event-unit").value = "";
  $("event-task").value = "";
  turn = await api(`/games/${id}/turn`);
  const game = await api(`/games/${id}`);
  await refreshProjection();
  localOperations = {
    BLUE: structuredClone(turn.blue.operation),
    RED: structuredClone(turn.red.operation),
  };
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
  document.querySelector("#turn-panel h2").textContent = replay
    ? "历史观察"
    : "当日作战命令";
  $("turn-day").textContent = replay
    ? `回放 D${replay.view.day}`
    : `第 ${turn.day} 天`;
  $("turn-phase").textContent =
    turn.status === "LIMIT_REACHED"
      ? "已达 60 天实验上限"
      : turn.status === "LOCKED"
        ? "双方已锁定"
        : "制定命令";
  const batch = batchFor(battleSide);
  $("side-status").textContent = replay
    ? "只读回放 · 当前命令保持不变"
    : batch.committed
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
  const units = battleWorld().regiments.filter(
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
  renderOperation();
  renderMap();
  battleControls();
}
function renderRoute() {
  if (replay) {
    const reports = battleReports();
    optionList(
      $("report-unit"),
      reports.map((r) => [r.id, r.name]),
      $("report-unit").value || reports[0]?.id,
    );
    renderCombatReport();
    return;
  }
  const unit = battleWorld().regiments.find(
    (r) => r.id === $("order-unit").value,
  );
  const order = localOrders[battleSide].find((o) => o.regimentId === unit?.id);
  const route = order?.route || [];
  const report = battleReports().find((r) => r.id === unit?.id);
  $("order-unit-info").textContent = report
    ? `组织度 ${(report.organization / 10).toFixed(1)}% · ${unit.companies.length} 个正式连位`
    : "本方可提交空命令表，全体待命。";
  $("order-action").value = order?.action || "MOVE";
  $("order-route").textContent = [
    "BOMBARD",
    "BUILD_BRIDGE",
    "STRIKE_RELAY",
  ].includes(order?.action)
    ? order.target
      ? `行动目标 ${key(order.target)}`
      : "点击地图选择行动目标。"
    : order?.action === "REST"
      ? "原地休整：消耗当地补给库存恢复有效连 HP。"
      : order?.action === "DEFEND"
        ? "展开防御：准备接敌，保持当前位置。"
        : route.length
          ? `${key(unit.position)} → ${route.map(key).join(" → ")}`
          : "原地待命。依次点击相邻地图格添加路径。";
  $("order-route").title = $("order-route").textContent;
  const memory =
      $("perspective").value === "DIVISION"
        ? {
            doctrine: order?.doctrine ||
              turn.memory[unit?.id]?.doctrine || {
                template: "HOLD",
                withdrawBelowPercent: 35,
                supplyId: "",
              },
            supplies: Object.values(playerView?.knowledge.supplies || {}),
          }
        : turn.memory[unit?.id],
    doctrine = order?.doctrine || memory?.doctrine;
  if (doctrine) {
    $("doctrine-template").value = doctrine.template;
    $("doctrine-threshold").value = doctrine.withdrawBelowPercent;
    const known = memory.supplies.filter((s) => s.side === battleSide);
    optionList(
      $("doctrine-supply"),
      [
        ["", "自动选择已知可达补给点"],
        ...known.map((s) => [
          s.id,
          `${s.id} · ${key(s.position)} · 库存 ${s.stock}`,
        ]),
        ...(!known.some((s) => s.id === doctrine.supplyId) && doctrine.supplyId
          ? [[doctrine.supplyId, `${doctrine.supplyId} · 尚未知晓`]]
          : []),
      ],
      doctrine.supplyId,
    );
    $("doctrine-knowledge").textContent =
      `本团已知己方补给点 ${known.length} 处 · 本地观察与通信报告更新，库存可能过时。`;
  }
  const reports = battleReports();
  optionList(
    $("report-unit"),
    reports.map((r) => [r.id, `${r.name}${r.destroyed ? " · 已被歼灭" : ""}`]),
    reports.some((r) => r.id === unit?.id) ? unit.id : reports[0]?.id,
  );
  renderCombatReport();
}
function currentLocalOrder() {
  const id = $("order-unit").value;
  if (!id) return null;
  let order = localOrders[battleSide].find((o) => o.regimentId === id);
  if (!order) {
    order = {
      orderId: uid("move"),
      regimentId: id,
      route: [],
      action: "MOVE",
      target: null,
      doctrine: null,
    };
    localOrders[battleSide].push(order);
  }
  return order;
}
function chooseWaypoint(point) {
  selected = point;
  if (replay) {
    const unit = battleWorld().regiments.find((r) => same(r.position, point));
    if (unit) inspectUnit(unit.id);
    else renderMap();
    return;
  }
  if (
    !batchFor(battleSide).committed &&
    turn.status !== "LIMIT_REACHED" &&
    $("order-unit").value
  ) {
    const order = currentLocalOrder();
    if (["BOMBARD", "BUILD_BRIDGE", "STRIKE_RELAY"].includes(order.action)) {
      order.target = point;
      renderTurn();
      return;
    }
    if (order.action && order.action !== "MOVE") {
      message("当前行动无需规划行军路线。");
      return;
    }
    const from =
      order.route.at(-1) ||
      battleWorld().regiments.find((r) => r.id === order.regimentId).position;
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
  refreshEventFilters();
  applyEventFilters();
  const reports = battleReports();
  const selectedReport = $("report-unit").value;
  optionList(
    $("report-unit"),
    reports.map((r) => [r.id, `${r.name}${r.destroyed ? " · 已被歼灭" : ""}`]),
    reports.some((r) => r.id === selectedReport)
      ? selectedReport
      : reports[0]?.id,
  );
  renderCombatReport();
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
  $("side-" + side.toLowerCase()).addEventListener("click", () =>
    run(async () => {
      battleSide = side;
      await refreshProjection();
      renderTurn();
      showDayEvents();
    }),
  );
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
  if (order) {
    order.route = [];
    order.target = null;
  }
  renderTurn();
});
$("submit-orders").addEventListener("click", () =>
  run(async () => {
    const side = battleSide;
    turn = await api(`/games/${battleId}/orders/${side}`, "PUT", {
      day: turn.day,
      expectedVersion: batchFor(side).version,
      orders: localOrders[side],
      operation: localOperations[side],
    });
    localOrders[side] = structuredClone(batchFor(side).orders);
    localOperations[side] = structuredClone(batchFor(side).operation);
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
    stopReplay();
    lastDay = await api(`/games/${battleId}/days/${$("day-list").value}`);
    await loadReplay(Number($("day-list").value), 0);
    message("已载入历史回放；可播放、逐帧查看或返回当前指挥。");
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
$("preset-combat").addEventListener("click", () =>
  run(async () => {
    const result = await api(
      "/scenarios/import",
      "POST",
      await api("/presets/combat-line"),
    );
    selected = null;
    revision = null;
    await loadDraft(result.id);
  }),
);
for (const button of document.querySelectorAll("[data-turn-tab]"))
  button.addEventListener("click", () => {
    document
      .querySelectorAll(".turn-page")
      .forEach((p) => (p.hidden = p.id !== button.dataset.turnTab));
    document
      .querySelectorAll("[data-turn-tab]")
      .forEach((b) => b.setAttribute("aria-pressed", String(b === button)));
  });
$("order-action").addEventListener("change", () => {
  const o = currentLocalOrder();
  if (!o) return;
  o.action = $("order-action").value;
  o.route = [];
  o.target = null;
  renderTurn();
});
for (const id of ["doctrine-template", "doctrine-threshold", "doctrine-supply"])
  $(id).addEventListener("change", () => {
    const threshold = Number($("doctrine-threshold").value);
    if (
      !$("doctrine-threshold").value ||
      !Number.isInteger(threshold) ||
      threshold < 0 ||
      threshold > 100
    ) {
      message("组织度阈值必须为 0–100 整数。", true);
      return;
    }
    const o = currentLocalOrder();
    if (!o) return;
    o.doctrine = {
      schemaVersion: 1,
      template: $("doctrine-template").value,
      withdrawBelowPercent: threshold,
      supplyId: $("doctrine-supply").value,
    };
    renderTurn();
  });
function renderCombatReport() {
  if (!turn) return;
  const report = battleReports().find((r) => r.id === $("report-unit").value);
  $("company-report").replaceChildren();
  if (!report) return;
  $("report-position").textContent =
    `${replay ? "回放 D" + replay.view.day + " · t" + replay.tick : lastDay ? "第 " + lastDay.result.day + " 天" : "当前"} · ${key(report.before)} → ${report.after ? key(report.after) : "被歼灭"} · 组织度 ${(report.organization / 10).toFixed(1)}%`;
  for (const c of report.companies) {
    const tr = document.createElement("tr");
    for (const text of [
      `${names[c.type]} / ${equipment[c.equipment]}`,
      `${c.beforeHp} → ${c.hp}`,
      `${(c.organization / 10).toFixed(1)}%`,
    ]) {
      const td = document.createElement("td");
      td.textContent = text;
      tr.append(td);
    }
    tr.dataset.companyId = c.id;
    $("company-report").append(tr);
  }
  const decision = battleEvents()
    .filter((e) => e.regimentId === report.id && e.decision)
    .at(-1)?.decision;
  $("report-doctrine").textContent = decision
    ? `${decision.rule} · ${eventNames[decision.action]} · ${decision.reason}`
    : "尚无学说触发记录。";
}
$("report-unit").addEventListener("change", renderCombatReport);
function showEvent(e) {
  $("event-summary").textContent =
    `第 ${e.day} 天 · ${eventNames[e.kind] || e.kind} · ${e.regimentId} · ${key(e.from)} → ${key(e.to)}`;
  const lines = [e.reason, `参与部队：${e.participants.join("、")}`];
  if (e.damage) {
    const d = e.damage;
    lines.push(
      `来源：${d.sourceRegimentId} / ${d.sourceCompanyId}`,
      `受影响连：${d.targetCompanyId}`,
      `HP：${d.beforeHp} → ${d.afterHp}`,
      `组织度：${(d.organizationBefore / 10).toFixed(1)}% → ${(d.organizationAfter / 10).toFixed(1)}%`,
      `火力 ${d.rawPower} · 防护 ${d.protection}% · 类型 ${d.damageType}`,
    );
  }
  if (e.decision) {
    const d = e.decision;
    lines.push(
      `命中规则：${d.rule}`,
      `行动：${eventNames[d.action]} · 补给目标：${d.targetSupplyId || "无"}`,
      `路径：${d.route.map(key).join(" → ") || "原地"}`,
      "决策使用的本团补给知识：",
      ...d.knowledge.map(
        (k) =>
          `${k.id} · ${key(k.position)} · ${k.side} · 库存 ${k.stock} · 第 ${k.observedDay} 天观察`,
      ),
    );
  }
  if (e.stock)
    lines.push(
      `补给点 ${e.stock.supplyId}：${e.stock.beforeStock} → ${e.stock.afterStock}`,
    );
  $("event-detail").textContent = lines.join("\n");
  $("event-dialog").showModal();
}
controls();
$("auth-dialog").showModal();

function battleWorld() {
  return playerView?.world || turn.world;
}
function battleReports() {
  if (replay) return playerView?.units || [];
  return $("perspective").value === "DIVISION"
    ? playerView?.units || []
    : lastDay?.result.units || turn.units;
}
function battleEvents() {
  if (replay) return playerView?.events || [];
  return $("perspective").value === "DIVISION"
    ? playerView?.events || []
    : lastDay?.result.events || [];
}
async function refreshProjection() {
  if (replay) {
    stopReplay();
    await loadReplay(replayDay, 0);
    return;
  }
  playerView = await api(
    `/games/${battleId}/view?perspective=${$("perspective").value}&side=${battleSide}`,
  );
}
$("perspective").addEventListener("change", () =>
  run(async () => {
    await refreshProjection();
    renderTurn();
    showDayEvents();
    message(
      $("perspective").value === "DIVISION"
        ? "师部视图：仅显示已送达报告；通信连线按最后已知位置推定。"
        : "全知验证视图；切换观察不改变结算。",
    );
  }),
);
$("show-comms").addEventListener("change", renderMap);
function renderCommunications(s, layer) {
  for (const n of s.communicationNodes || []) {
    const c = center(n.position);
    layer.append(
      svg(
        "text",
        {
          x: c.x - 23,
          y: c.y + 20,
          fill: n.hp > 0 ? "#eed67c" : "#ba736a",
          "font-size": 13,
        },
        `⌁${n.hp}`,
      ),
    );
  }
  if (activeView !== "battle") return;
  if ($("show-comms").checked)
    for (const link of playerView?.links || []) {
      const nodes = playerView.nodes,
        a = nodes.find((n) => n.id === link.a),
        b = nodes.find((n) => n.id === link.b);
      if (a.side !== battleSide) continue;
      const p = center(a.position),
        q = center(b.position);
      layer.append(
        svg("line", {
          x1: p.x,
          y1: p.y,
          x2: q.x,
          y2: q.y,
          stroke: "#edd77d",
          "stroke-width": 1.2,
          "stroke-dasharray": "2 5",
          opacity: 0.6,
        }),
      );
    }
}
function renderRelay() {
  if (!draft || !selected) return;
  const n = (draft.scenario.communicationNodes || []).find((n) =>
    same(n.position, selected),
  );
  $("relay-side").value = n?.side || "BLUE";
  $("relay-hp").value = n?.hp ?? 100;
  $("relay-radius").value = draft.scenario.communicationRadius || 5;
}
$("relay-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(() =>
    save((s) => {
      const old = (s.communicationNodes || []).find((n) =>
        same(n.position, selected),
      );
      s.communicationNodes = (s.communicationNodes || []).filter(
        (n) => !same(n.position, selected),
      );
      s.communicationNodes.push({
        id: old?.id || `relay-${selected.q}-${selected.r}`,
        position: selected,
        side: $("relay-side").value,
        hp: Number($("relay-hp").value),
      });
      s.communicationRadius = Number($("relay-radius").value);
    }),
  );
});
$("remove-relay").addEventListener("click", () =>
  run(() =>
    save((s) => {
      s.communicationNodes = (s.communicationNodes || []).filter(
        (n) => !same(n.position, selected),
      );
    }),
  ),
);
$("preset-coordination").addEventListener("click", () =>
  run(async () => {
    const result = await api(
      "/scenarios/import",
      "POST",
      await api("/presets/coordination"),
    );
    selected = null;
    revision = null;
    await loadDraft(result.id);
  }),
);
const operationNames = {
  WAITING_DELIVERY: "等待送达",
  WAITING_CONFIRMATION: "等待确认",
  EXECUTING: "执行",
  EXECUTING_FALLBACK: "备用行动",
  HOLD: "前置失败·等待",
  CANCELLED: "取消",
  COMPLETED: "完成",
  FAILED: "失败",
};
Object.assign(eventNames, operationNames, {
  BUILD_BRIDGE: "桥梁架设",
  STRIKE_RELAY: "通信工事受损",
  ACTION_FAILED: "行动失败",
  REPORT_DELIVERED: "报告送达",
});
function renderOperation() {
  const plan = localOperations[battleSide];
  $("op-mode").value = plan?.mode || "NONE";
  optionList(
    $("op-brigade"),
    [
      ["", "无旅部"],
      ...battleWorld()
        .regiments.filter(
          (r) => r.side === battleSide && r.role === "BRIGADE_HQ",
        )
        .map((r) => [r.id, r.name]),
    ],
    plan?.brigadeId || "",
  );
  const cells = plan?.theater;
  $("op-theater").value = cells?.length
    ? `${Math.min(...cells.map((c) => c.q))},${Math.min(...cells.map((c) => c.r))},${Math.max(...cells.map((c) => c.q))},${Math.max(...cells.map((c) => c.r))}`
    : `0,0,${turn.world.width - 1},${turn.world.height - 1}`;
  optionList(
    $("op-task"),
    localOrders[battleSide].map((o) => [
      o.orderId,
      `${o.regimentId} / ${o.orderId}`,
    ]),
    $("op-task").value,
  );
  renderOperationNode();
  renderDag();
}
function renderOperationNode() {
  const n = localOperations[battleSide]?.nodes.find(
    (n) => n.orderId === $("op-task").value,
  );
  $("op-after").value = n?.after.join(",") || "";
  $("op-fallback").value = n?.fallback || "HOLD";
}
$("op-task").addEventListener("change", renderOperationNode);
$("op-save").addEventListener("click", () => {
  if ($("op-mode").value === "NONE") {
    localOperations[battleSide] = null;
    renderTurn();
    return;
  }
  const bounds = $("op-theater").value.split(",").map(Number);
  if (
    bounds.length !== 4 ||
    bounds.some((n) => !Number.isInteger(n)) ||
    bounds[0] < 0 ||
    bounds[1] < 0 ||
    bounds[2] >= turn.world.width ||
    bounds[3] >= turn.world.height ||
    bounds[0] > bounds[2] ||
    bounds[1] > bounds[3]
  ) {
    message("请输入地图内的有效战区矩形。", true);
    return;
  }
  const theater = [];
  for (let q = bounds[0]; q <= bounds[2]; q++)
    for (let r = bounds[1]; r <= bounds[3]; r++) theater.push({ q, r });
  const old = localOperations[battleSide];
  const nodes = localOrders[battleSide].map((o) =>
    structuredClone(
      old?.nodes.find((n) => n.orderId === o.orderId) || {
        orderId: o.orderId,
        after: [],
        fallback: "HOLD",
      },
    ),
  );
  const node = nodes.find((n) => n.orderId === $("op-task").value);
  if (node) {
    node.after = $("op-after")
      .value.split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    node.fallback = $("op-fallback").value;
  }
  localOperations[battleSide] = {
    id: `op-${battleSide.toLowerCase()}`,
    brigadeId: $("op-brigade").value,
    mode: $("op-mode").value,
    theater,
    nodes,
  };
  renderTurn();
  message("已更新本地协作；提交时由服务端校验 DAG、旅属和战区。");
});
function renderDag() {
  const dag = $("operation-dag");
  dag.replaceChildren();
  const plan = replay
    ? replay.plan
    : localOperations[battleSide] ||
      (battleSide === "BLUE"
        ? lastDay?.manifest.blueOperation
        : lastDay?.manifest.redOperation);
  if (!plan) {
    $("op-status").textContent =
      "先为各团规划行动，再设置前置依赖。最多 16 个节点。";
    return;
  }
  const nodes = plan.nodes,
    cols = 2,
    height = Math.max(100, Math.ceil(nodes.length / cols) * 55);
  dag.setAttribute("viewBox", `0 0 360 ${height}`);
  const position = (i) => ({
    x: 5 + (i % cols) * 180,
    y: 5 + Math.floor(i / cols) * 55,
  });
  for (let i = 0; i < nodes.length; i++)
    for (const dep of nodes[i].after) {
      const j = nodes.findIndex((n) => n.orderId === dep);
      if (j < 0) continue;
      const a = position(j),
        b = position(i);
      dag.append(
        svg("path", {
          d: `M${a.x + 85},${a.y + 40} L${b.x + 85},${b.y}`,
          stroke: "#ebcf80",
          "stroke-width": 1.5,
          fill: "none",
        }),
      );
      dag.append(
        svg(
          "text",
          { x: b.x + 80, y: b.y, fill: "#ebcf80", "font-size": 10 },
          "▼",
        ),
      );
    }
  nodes.forEach((node, i) => {
    const p = position(i),
      actual = (replay?.operations || lastDay?.result.operations)?.find(
        (n) => n.operationId === plan.id && n.orderId === node.orderId,
      );
    const observed = [...battleEvents()]
      .reverse()
      .find(
        (e) =>
          e.orderId === node.orderId &&
          ["OrderTransition", "OperationTransition"].includes(e.category),
      );
    const outcome = battleEvents().find(
      (e) => e.orderId === node.orderId && e.category === "OperationOutcome",
    );
    const status =
      $("perspective").value === "DIVISION"
        ? (outcome?.kind === "SUCCEEDED" ? "COMPLETED" : outcome?.kind) ||
          observed?.kind ||
          "待报告"
        : actual?.status || "待提交";
    const g = svg("g", {
      tabindex: 0,
      role: "button",
      "aria-label": `${node.orderId} ${status}`,
      "data-order-id": node.orderId,
    });
    g.append(
      svg("rect", {
        x: p.x,
        y: p.y,
        width: 165,
        height: 40,
        rx: 4,
        fill: "#172f38",
        stroke: status === "COMPLETED" ? "#83be96" : "#c8af75",
      }),
    );
    g.append(
      svg(
        "text",
        { x: p.x + 7, y: p.y + 15, fill: "#e2e8db", "font-size": 11 },
        node.orderId.length > 22
          ? node.orderId.slice(0, 20) + "…"
          : node.orderId,
      ),
    );
    g.append(
      svg(
        "text",
        { x: p.x + 7, y: p.y + 31, fill: "#ceb87e", "font-size": 10 },
        operationNames[status] || eventNames[status] || status,
      ),
    );
    g.append(
      svg(
        "title",
        {},
        `${node.orderId}\n前置：${node.after.join(",") || "无"}\n失败分支：${node.fallback}\n${$("perspective").value === "OMNISCIENT" && actual ? JSON.stringify(actual.confirmed) : observed?.reason || "等待报告"}`,
      ),
    );
    const select = () => {
      $("event-unit").value = "";
      $("event-task").value = node.orderId;
      applyEventFilters();
      const related =
        outcome ||
        battleEvents()
          .filter((e) => e.orderId === node.orderId)
          .at(-1);
      if (related) focusEvent(related);
      $("op-status").textContent =
        `${node.orderId} · 前置 ${node.after.join(",") || "无"} · ${outcome?.reason || observed?.reason || "等待任务报告"}`;
    };
    g.addEventListener("click", select);
    g.addEventListener("keydown", (e) => {
      if (e.key === "Enter") select();
    });
    dag.append(g);
  });
  $("op-status").textContent =
    `${plan.mode === "COORDINATED" ? "旅部确认保序" : "环境相关独立调度"} · ${nodes.length} 个任务 · 点击节点查看关联记录`;
}
$("op-demo").addEventListener("click", () => {
  if (!battleWorld().regiments.some((r) => r.id === "blue-engineers")) {
    message("请在渡河协作预设中使用此任务。", true);
    return;
  }
  battleSide = "BLUE";
  localOrders.BLUE = [
    {
      orderId: "bridge",
      regimentId: "blue-engineers",
      action: "BUILD_BRIDGE",
      target: { q: 3, r: 0 },
      route: [],
    },
    {
      orderId: "cross",
      regimentId: "blue-armor",
      action: "MOVE",
      target: null,
      route: [
        { q: 3, r: 0 },
        { q: 4, r: 0 },
      ],
    },
  ];
  localOperations.BLUE = {
    id: "river-operation",
    brigadeId: "blue-brigade",
    mode: "COORDINATED",
    theater: turn.world.cells.map((c) => c.position),
    nodes: [
      { orderId: "bridge", after: [], fallback: "HOLD" },
      { orderId: "cross", after: ["bridge"], fallback: "HOLD" },
    ],
  };
  renderTurn();
});

function unitIcon(type, x, y, scale = 1) {
  const paths = {
    INFANTRY:
      "M5 6 A5 5 0 0 1 15 6 Z M10 7 V14 M6 19 L10 13 L14 19 M3 13 L17 8",
    ARMOR: "M2 12 H18 V17 H2 Z M6 12 V7 H13 V12 M13 8 H21 M5 15 H15",
    ANTI_TANK: "M2 17 L18 3 M8 12 L16 18 M2 18 H8 M17 2 L21 6",
    ARTILLERY:
      "M3 12 L18 4 L20 7 L6 15 M8 14 L17 19 M4 14 A3 3 0 1 0 4 20 A3 3 0 1 0 4 14",
    ENGINEER: "M10 1 V12 M6 2 H14 M5 12 H15 V15 L10 21 L5 15 Z",
    RECON: "M3 6 H8 L9 17 H1 Z M13 6 H18 L20 17 H11 Z M8 10 H13",
    SIGNAL: "M10 9 V21 M5 21 H15 M6 7 Q10 2 14 7 M2 4 Q10 -3 18 4",
    FOOT: "M6 2 H12 V12 L19 15 V19 H3 V13 H6 Z",
  };
  const icon = svg("g", {
    transform: `translate(${x} ${y}) scale(${scale})`,
    "data-type-icon": type,
  });
  icon.append(
    svg("path", {
      d: paths[type] || paths.INFANTRY,
      fill: "none",
      stroke: "#f0ecd7",
      "stroke-width": 1.6,
      "stroke-linecap": "round",
      "stroke-linejoin": "round",
    }),
  );
  return icon;
}
function renderInitialKnowledge() {
  if (!draft) return;
  const s = draft.scenario,
    previous = $("intel-observer").value;
  optionList(
    $("intel-observer"),
    s.regiments.map((r) => [r.id, `${r.name} / ${r.id}`]),
    s.regiments.some((r) => r.id === previous) ? previous : s.regiments[0]?.id,
  );
  fillInitialKnowledge();
  const setup = s.setup;
  $("setup-summary").textContent =
    `已存首日方案：蓝方 ${setup?.blue.length || 0} 条，红方 ${setup?.red.length || 0} 条；依赖图 ${Number(!!setup?.blueOperation) + Number(!!setup?.redOperation)} 份。`;
  const candidates = `部队：${s.regiments.map((r) => r.id).join(", ")}\n补给：${s.supplies.map((r) => r.id).join(", ") || "无"}\n通信：${(s.communicationNodes || []).map((r) => r.id).join(", ") || "无"}`;
  $("intel-candidates").textContent = candidates;
  $("intel-candidates").title = candidates;
}
function fillInitialKnowledge() {
  const k = draft?.scenario.initialKnowledge?.find(
    (k) => k.observerId === $("intel-observer").value,
  );
  $("intel-units").value = k?.regimentIds.join(",") || "";
  $("intel-supplies").value = k?.supplyIds.join(",") || "";
  $("intel-relays").value = k?.relayIds.join(",") || "";
}
$("intel-observer").addEventListener("change", fillInitialKnowledge);
$("intel-form").addEventListener("submit", (e) => {
  e.preventDefault();
  run(() =>
    save((s) => {
      const ids = (id) =>
        $(id)
          .value.split(",")
          .map((s) => s.trim())
          .filter(Boolean);
      const k = {
        observerId: $("intel-observer").value,
        regimentIds: ids("intel-units"),
        supplyIds: ids("intel-supplies"),
        relayIds: ids("intel-relays"),
      };
      if (!k.observerId) throw new Error("请先部署观察者部队。");
      s.initialKnowledge = (s.initialKnowledge || []).filter(
        (v) => v.observerId !== k.observerId,
      );
      if (k.regimentIds.length + k.supplyIds.length + k.relayIds.length)
        s.initialKnowledge.push(k);
    }),
  );
});
$("clear-setup").addEventListener("click", () =>
  run(() =>
    save((s) => {
      s.setup = { blue: [], red: [], blueOperation: null, redOperation: null };
    }),
  ),
);
$("save-blueprint").addEventListener("click", () =>
  run(async () => {
    if (["BLUE", "RED"].some((side) => ordersDirty(side)))
      throw new Error("请先提交当前修改；另存使用服务端已提交的双方命令。");
    const saved = await api(`/games/${battleId}/blueprint`, "POST", {
      day: turn.day,
      blueVersion: turn.blue.version,
      redVersion: turn.red.version,
    });
    switchHeader(false);
    revision = null;
    await loadDraft(saved.id);
    selectPanel("intel-panel");
    message(
      "实验方案已另存为新草稿：地图、初始情报、双方命令、学说和依赖均已保存。可导出或冻结重开。",
    );
  }),
);
function refreshEventFilters() {
  const events = battleEvents(),
    units = new Set(events.map((e) => e.regimentId).filter(Boolean));
  for (const r of battleReports()) units.add(r.id);
  const tasks = new Set(events.map((e) => e.orderId).filter(Boolean));
  for (const n of replay?.plan?.nodes || []) tasks.add(n.orderId);
  for (const id of ["event-unit", "event-task"]) {
    const values = id === "event-unit" ? [...units] : [...tasks],
      old = $(id).value;
    optionList(
      $(id),
      [
        ["", id === "event-unit" ? "全部团" : "全部任务"],
        ...values.sort().map((v) => [v, v]),
      ],
      values.includes(old) ? old : "",
    );
  }
}
function applyEventFilters() {
  const unit = $("event-unit").value,
    task = $("event-task").value,
    all = battleEvents();
  eventRows = all.filter(
    (e) =>
      (!unit || e.regimentId === unit || e.participants.includes(unit)) &&
      (!task || e.orderId === task),
  );
  eventPage = 0;
  renderEvents();
  $("event-count").textContent = `${eventRows.length} / ${all.length} 条`;
}
for (const id of ["event-unit", "event-task"])
  $(id).addEventListener("change", applyEventFilters);
$("event-reset").addEventListener("click", () => {
  $("event-unit").value = "";
  $("event-task").value = "";
  applyEventFilters();
});
function inspectUnit(id) {
  const r = battleWorld().regiments.find((r) => r.id === id);
  if (!r) return;
  selected = r.position;
  $("report-unit").value = id;
  if (!replay && [...$("order-unit").options].some((o) => o.value === id))
    $("order-unit").value = id;
  document.querySelector('[data-turn-tab="report-page"]').click();
  $("event-unit").value = id;
  $("event-task").value = "";
  applyEventFilters();
  renderCombatReport();
  renderMap();
  message(`${r.name} · ${id} · ${key(r.position)}；已定位团详情与关联记录。`);
}
function focusEvent(e) {
  selected =
    e.to ||
    e.from ||
    battleWorld().regiments.find((r) => r.id === e.regimentId)?.position ||
    selected;
  if ([...$("report-unit").options].some((o) => o.value === e.regimentId)) {
    $("report-unit").value = e.regimentId;
    renderCombatReport();
  }
  if (selected && camera) {
    const p = center(selected);
    camera = { ...camera, x: p.x - camera.w / 2, y: p.y - camera.h / 2 };
  }
  renderMap();
  for (const node of document.querySelectorAll(
    "#operation-dag [data-order-id]",
  ))
    node.classList.toggle("focused-task", node.dataset.orderId === e.orderId);
}
function replayControls() {
  const active = !!replay;
  $("replay-start").disabled = busy || !lastDay;
  $("replay-prev").disabled = busy || !active || replay.index === 0;
  $("replay-next").disabled =
    busy || !active || replay.index + 1 >= replay.count;
  $("replay-play").disabled = busy || !active || replayPlaying;
  $("replay-exit").disabled = busy || !active;
  $("replay-frame").disabled = busy || !active;
  $("replay-frame").max = active ? replay.count - 1 : 0;
  $("replay-frame").value = active ? replay.index : 0;
  $("replay-time").textContent = active
    ? `D${replay.view.day} · t${replay.tick} · ${replay.index + 1}/${replay.count}${replay.phase === "REPORTS_AVAILABLE" ? " · 报告送达" : ""}`
    : "实时指挥";
  $("save-blueprint").disabled =
    busy ||
    active ||
    !turn ||
    turn.day !== 1 ||
    !turn.blue.submitted ||
    !turn.red.submitted;
}
function stopReplay() {
  replayPlaying = false;
  clearTimeout(replayTimer);
  replayTimer = null;
  replayRequest++;
}
async function loadReplay(day, index) {
  const wasActive = !!replay;
  const request = ++replayRequest;
  const value = await api(
    `/games/${battleId}/days/${day}/replay?frame=${index}&perspective=${$("perspective").value}&side=${battleSide}`,
  );
  if (request !== replayRequest) return;
  replay = value;
  replayDay = day;
  playerView = value.view;
  document.body.classList.add("replaying");
  if (!wasActive || !$("action-page").hidden || !$("doctrine-page").hidden)
    document.querySelector('[data-turn-tab="report-page"]').click();
  renderTurn();
  showDayEvents();
  replayControls();
  $("turn-phase").textContent = "历史观察 · 不可修改命令";
}
$("replay-start").addEventListener("click", () =>
  run(async () => {
    stopReplay();
    await loadReplay(lastDay.result.day, 0);
    message("回放已载入；播放或拖动时间轴。");
  }),
);
for (const [id, delta] of [
  ["replay-prev", -1],
  ["replay-next", 1],
])
  $(id).addEventListener("click", () =>
    run(async () => {
      stopReplay();
      await loadReplay(replayDay, replay.index + delta);
    }),
  );
$("replay-frame").addEventListener("change", (event) => {
  const index = Number(event.target.value);
  run(async () => {
    stopReplay();
    await loadReplay(replayDay, index);
  });
});
$("replay-pause").addEventListener("click", () => {
  stopReplay();
  replayControls();
  message("回放已暂停。");
});
async function playReplayStep() {
  if (!replayPlaying || !replay || activeView !== "battle") return;
  if (busy) {
    replayTimer = setTimeout(playReplayStep, 150);
    return;
  }
  if (replay.index + 1 >= replay.count) {
    stopReplay();
    replayControls();
    return;
  }
  await run(() => loadReplay(replayDay, replay.index + 1));
  if (replayPlaying) replayTimer = setTimeout(playReplayStep, 600);
}
$("replay-play").addEventListener("click", () =>
  run(async () => {
    stopReplay();
    if (replay.index + 1 >= replay.count) await loadReplay(replayDay, 0);
    replayPlaying = true;
    replayTimer = setTimeout(playReplayStep, 200);
    replayControls();
  }),
);
$("replay-exit").addEventListener("click", () =>
  run(async () => {
    stopReplay();
    replay = null;
    replayDay = null;
    document.body.classList.remove("replaying");
    if (turn.day > 1) {
      lastDay = await api(`/games/${battleId}/days/${turn.day - 1}`);
      $("day-list").value = String(turn.day - 1);
    }
    await refreshProjection();
    renderTurn();
    showDayEvents();
    message("已返回当前指挥；本地未提交的命令保留。");
  }),
);
$("retry-request").addEventListener("click", () =>
  run(async () => {
    if (!token) {
      $("auth-dialog").showModal();
      return;
    }
    await api("/system");
    if (activeView === "battle" && battleId) await openBattle(battleId);
    else if (draft) await loadDraft(draft.id);
    else await listDrafts();
    message("已重新连接并载入服务端状态。");
  }),
);

$("preset-lab").addEventListener("click", () =>
  run(async () => {
    const saved = await api(
      "/scenarios/import",
      "POST",
      await api("/presets/doctrine-lab"),
    );
    selected = null;
    revision = null;
    await loadDraft(saved.id);
    $("max-iterations").value = "6";
    message(
      "已载入河谷实验：首日学说与架桥／炮击→突击方案已保存；冻结后即可运行。",
    );
  }),
);

$("map-inspect").addEventListener("click", () => {
  mapInspect = !mapInspect;
  renderMap();
});
