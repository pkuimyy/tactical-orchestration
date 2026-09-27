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
const uid = (prefix) => `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
function message(text, error = false) {
  $("status").textContent = text;
  $("status").classList.toggle("error", error);
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
  $("connection").textContent = token ? "已连接 · 本地服务" : "未连接";
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
  const result = await response.json();
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
  optionList(
    $("draft-list"),
    [
      ["", "请选择草稿"],
      ...list.map((d) => [d.id, `${d.name} · v${d.version}`]),
    ],
    draft?.id || "",
  );
}
async function loadDraft(id) {
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
  const events = await api(`/scenarios/${draft.id}/events`);
  $("events").replaceChildren(
    ...events
      .slice()
      .reverse()
      .map((e) => {
        const li = document.createElement("li");
        li.textContent = `#${e.sequence} ${e.kind} · ${e.entityId} · ${e.contentHash.slice(0, 16)}`;
        return li;
      }),
  );
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
  const s = draft.scenario,
    map = $("map");
  map.replaceChildren();
  map.setAttribute(
    "viewBox",
    `0 0 ${85 + Math.sqrt(3) * 30 * (s.width - 1 + (s.height - 1) / 2)} ${84 + 45 * (s.height - 1)}`,
  );
  $("draft-meta").textContent =
    `${s.name} · v${draft.version} · ${s.width}×${s.height}`;
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
      if (busy) return;
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
  for (const cell of s.cells) {
    const c = center(cell.position);
    overlays.append(
      svg(
        "text",
        { x: c.x, y: c.y + 23, "text-anchor": "middle", class: "coord" },
        key(cell.position),
      ),
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
  $("selection").textContent = `q ${selected.q} / r ${selected.r}`;
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
  remove.addEventListener("click", () => div.remove());
  div.append(remove);
  const field = (label, name, node) => {
    const l = document.createElement("label");
    l.textContent = label;
    node.dataset.field = name;
    l.append(node);
    div.append(l);
    return node;
  };
  const id = field("连 ID", "id", document.createElement("input"));
  id.value = c.id;
  id.required = true;
  id.pattern = "[A-Za-z0-9_-]{1,64}";
  id.maxLength = 64;
  const type = field("类型", "type", document.createElement("select"));
  optionList(type, Object.entries(names), c.type);
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
}
async function renderGames() {
  $("games").replaceChildren();
  $("game-detail").textContent = "选择一个实验查看其冻结初始输入。";
  if (!revision) {
    $("revision-info").textContent =
      "尚未冻结。为双方部署城市师部后，冻结并创建实验。";
    return;
  }
  $("revision-info").textContent =
    `版本 ${revision.id} · 来源草稿 v${revision.draftVersion}\nSHA-256 ${revision.contentHash}${revision.draftVersion !== draft.version ? " · 当前草稿有后续编辑，实验仍使用此冻结版本。" : ""}`;
  const games = await api(`/revisions/${revision.id}/games`);
  for (const game of games) {
    const box = document.createElement("div");
    box.className = "game";
    const title = document.createElement("strong");
    title.textContent = `独立实验 ${games.indexOf(game) + 1} · ${game.status}`;
    const id = document.createElement("p");
    id.textContent = game.id;
    const seed = document.createElement("code");
    seed.textContent = `种子 ${game.seed} · 第 ${game.day} 天`;
    const button = document.createElement("button");
    button.className = "secondary";
    button.textContent = "查看初始状态";
    button.addEventListener("click", () =>
      run(async () => {
        const state = await api(`/games/${game.id}`);
        $("game-detail").textContent = JSON.stringify(state, null, 2);
        $("game-detail").parentElement.open = true;
        message("已读取独立实验的冻结初始状态");
      }),
    );
    box.append(title, id, seed, document.createElement("br"), button);
    $("games").append(box);
  }
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
    await api("/games", "POST", { revisionId: revision.id, seed });
    await loadHistory();
    message("独立实验已创建，状态 READY；M1 尚不结算回合。");
  }),
);
$("openapi").addEventListener("click", (e) => {
  e.preventDefault();
  run(async () => {
    download(await api("/openapi"), "openapi.json");
    message("已读取代码生成的 OpenAPI。");
  });
});
controls();
