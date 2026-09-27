package io.tactical.core;

import static io.tactical.core.Scenario.*;

import java.util.*;

/** Only rule authority for draft/import/freeze; no framework dependencies. */
public final class ScenarioRules {
  public static final int MAX_DIMENSION = 16;
  public static final int MAX_REGIMENTS = 32;
  public static final int MAX_COMPANIES = 6;

  private ScenarioRules() {}

  public static Scenario blank(String name, int width, int height) {
    dimensions(width, height);
    var cells = new ArrayList<Cell>();
    for (int q = 0; q < width; q++)
      for (int r = 0; r < height; r++) cells.add(new Cell(new HexCoord(q, r), Terrain.PLAIN, 0));
    return normalize(new Scenario(1, name, width, height, cells, List.of(), List.of(), List.of()));
  }

  public static Scenario normalize(Scenario s) {
    return normalize(s, false);
  }

  /** Runtime casualties may remove HQ signal companies and brigade headquarters. */
  public static Scenario normalizeRuntime(Scenario s) {
    return normalize(s, true);
  }

  private static Scenario normalize(Scenario s, boolean runtime) {
    check(s != null, "场景不能为空");
    check(s.schemaVersion() == ContractVersion.CURRENT, "不支持的场景 schemaVersion");
    name(s.name(), "场景名称");
    dimensions(s.width(), s.height());
    check(s.cells().size() == s.width() * s.height(), "地图必须完整覆盖 width × height 个格子");
    var cells = new HashMap<HexCoord, Cell>();
    for (Cell cell : s.cells()) {
      coordinate(cell.position(), s);
      check(cell.terrain() != null, "地形不能为空");
      check(cell.fortification() >= 0 && cell.fortification() <= 3, "工事等级必须在 0–3 之间");
      check(cells.put(cell.position(), cell) == null, "地图坐标重复：" + cell.position());
    }
    check(s.edges().size() <= 3 * s.cells().size(), "格边数量超限");
    var edges = new ArrayList<Edge>();
    var edgeKeys = new HashSet<String>();
    for (Edge edge : s.edges()) {
      coordinate(edge.a(), s);
      coordinate(edge.b(), s);
      check(edge.a().distance(edge.b()) == 1, "格边必须连接相邻六角格");
      check(edge.bridge() != null, "桥梁状态不能为空");
      check(edge.bridge() == Bridge.NONE || edge.river(), "桥梁必须位于河流格边上");
      HexCoord a = edge.a().compareTo(edge.b()) < 0 ? edge.a() : edge.b();
      HexCoord b = a.equals(edge.a()) ? edge.b() : edge.a();
      check(edgeKeys.add(a + "/" + b), "格边重复（包括反向重复）");
      if (edge.river() || edge.road())
        edges.add(new Edge(a, b, edge.river(), edge.bridge(), edge.road()));
    }
    check(s.regiments().size() <= MAX_REGIMENTS, "团数量不能超过 " + MAX_REGIMENTS);
    var occupied = new HashSet<HexCoord>();
    var ids = new HashSet<String>();
    var companyIds = new HashSet<String>();
    var hqs = new HashSet<Side>();
    var regiments = new ArrayList<Regiment>();
    for (Regiment regiment : s.regiments()) {
      id(regiment.id());
      name(regiment.name(), "团名称");
      coordinate(regiment.position(), s);
      check(ids.add(regiment.id()), "团 ID 重复：" + regiment.id());
      check(occupied.add(regiment.position()), "同一格只能部署一个团：" + regiment.position());
      check(regiment.side() != null && regiment.role() != null, "团必须指定阵营和角色");
      check(
          regiment.companies().size() >= 1 && regiment.companies().size() <= MAX_COMPANIES,
          "每个团必须包含 1–6 个正式连位");
      for (Company company : regiment.companies()) {
        id(company.id());
        check(companyIds.add(company.id()), "连 ID 必须全场景唯一：" + company.id());
        check(company.type() != null && company.equipment() != null, "连必须指定类型和装备形态");
        check(company.maxHp() >= 1 && company.maxHp() <= 1000, "连 maxHp 必须在 1–1000 之间");
        check(company.hp() >= 0 && company.hp() <= company.maxHp(), "连 HP 必须在 0–maxHp 之间");
      }
      check(regiment.companies().stream().anyMatch(c -> c.hp() > 0), "部署的团至少有一个存活连");
      long signals =
          regiment.companies().stream()
              .filter(c -> c.type() == CompanyType.SIGNAL && c.hp() > 0)
              .count();
      if (regiment.role() == Role.DIVISION_HQ) {
        check(cells.get(regiment.position()).terrain() == Terrain.CITY, "师部必须部署在城市格");
        check(runtime || signals >= 1, "师部至少需要一个存活通信连");
        check(hqs.add(regiment.side()), "每方只能部署一个师部");
      }
      if (regiment.role() == Role.BRIGADE_HQ) check(runtime || signals >= 2, "旅部必须占用两个正式连位配置存活通信连");
      String brigade = regiment.brigadeId() == null ? "" : regiment.brigadeId();
      if (!brigade.isEmpty()) {
        id(brigade);
        check(regiment.role() == Role.REGIMENT, "仅普通团可以设置所属旅部");
        check(
            runtime
                || s.regiments().stream()
                    .anyMatch(
                        b ->
                            brigade.equals(b.id())
                                && b.role() == Role.BRIGADE_HQ
                                && b.side() == regiment.side()),
            "所属旅部不存在或阵营不符");
      }
      regiments.add(
          new Regiment(
              regiment.id(),
              regiment.name(),
              regiment.side(),
              regiment.role(),
              regiment.position(),
              brigade,
              regiment.companies().stream().sorted(Comparator.comparing(Company::id)).toList()));
    }
    check(s.supplies().size() <= 64, "补给点数量不能超过 64");
    var supplyIds = new HashSet<String>();
    var supplyPositions = new HashSet<HexCoord>();
    for (Supply supply : s.supplies()) {
      id(supply.id());
      coordinate(supply.position(), s);
      check(supplyIds.add(supply.id()) && supplyPositions.add(supply.position()), "补给点 ID 或坐标重复");
      check(supply.side() != null, "补给点必须指定阵营");
      check(supply.stock() >= 0 && supply.stock() <= 1000000, "补给库存必须在 0–1000000 之间");
    }
    check(s.communicationRadius() >= 1 && s.communicationRadius() <= 8, "通信半径必须在 1–8 之间");
    check(s.communicationNodes().size() <= 32, "通信工事最多 32 个");
    var nodeIds = new HashSet<String>();
    var nodePositions = new HashSet<HexCoord>();
    for (var node : s.communicationNodes()) {
      id(node.id());
      coordinate(node.position(), s);
      check(
          nodeIds.add(node.id()) && !ids.contains(node.id()) && nodePositions.add(node.position()),
          "通信工事 ID 或位置重复");
      check(node.side() != null && node.hp() >= 0 && node.hp() <= 1000, "通信工事需要阵营及 0–1000 HP");
    }
    if (!runtime) {
      check(s.initialKnowledge().size() <= 32, "初始情报最多 32 个观察者");
      var observers = new HashSet<String>();
      for (var k : s.initialKnowledge()) {
        check(observers.add(k.observerId()), "初始情报观察者重复");
        k.validate(s);
      }
      s.setup().validate(s);
    }
    return new Scenario(
        s.schemaVersion(),
        s.name(),
        s.width(),
        s.height(),
        s.cells().stream().sorted(Comparator.comparing(Cell::position)).toList(),
        edges.stream().sorted(Comparator.comparing(Edge::a).thenComparing(Edge::b)).toList(),
        regiments.stream().sorted(Comparator.comparing(Regiment::id)).toList(),
        s.supplies().stream().sorted(Comparator.comparing(Supply::id)).toList(),
        s.communicationNodes().stream()
            .sorted(Comparator.comparing(CommunicationNode::id))
            .toList(),
        s.communicationRadius(),
        s.setup(),
        s.initialKnowledge().stream()
            .sorted(Comparator.comparing(InitialKnowledge::observerId))
            .toList());
  }

  public static void requirePlayable(Scenario s) {
    for (Side side : Side.values())
      check(
          s.regiments().stream().anyMatch(r -> r.side() == side && r.role() == Role.DIVISION_HQ),
          "冻结前请为 " + side + " 部署城市师部（至少一个存活通信连）");
  }

  private static void dimensions(int width, int height) {
    check(
        width >= 2 && height >= 2 && width <= MAX_DIMENSION && height <= MAX_DIMENSION,
        "地图宽高必须在 2–16 之间");
  }

  private static void coordinate(HexCoord c, Scenario s) {
    check(
        c != null && c.q() >= 0 && c.r() >= 0 && c.q() < s.width() && c.r() < s.height(),
        "坐标超出地图范围：" + c);
  }

  private static void id(String id) {
    check(id != null && id.matches("[A-Za-z0-9_-]{1,64}"), "ID 必须是 1–64 位字母、数字、下划线或短横线");
  }

  private static void name(String name, String label) {
    check(name != null && !name.isBlank() && name.length() <= 80, label + "必须为 1–80 个字符");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new ScenarioViolation(message);
  }
}
