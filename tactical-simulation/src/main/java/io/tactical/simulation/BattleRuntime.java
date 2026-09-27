package io.tactical.simulation;

import static io.tactical.core.Scenario.*;

import io.tactical.core.*;
import io.tactical.core.MovementOrder.Action;
import java.util.*;

/** Mutable working set for one resolve only; all damage is computed from a same-tick snapshot. */
public final class BattleRuntime {
  record Pair(String a, String b, HexCoord contested) {}

  public record Damage(
      String sourceRegimentId,
      String sourceCompanyId,
      String targetCompanyId,
      int beforeHp,
      int afterHp,
      int organizationBefore,
      int organizationAfter,
      int rawPower,
      int protection,
      String damageType) {}

  public record StockChange(String supplyId, int beforeStock, int afterStock) {}

  final Map<String, Regiment> units = new TreeMap<>();
  final Map<String, HexCoord> positions;
  final Map<String, RegimentMemory> memory = new TreeMap<>();
  final Set<String> underFire = new HashSet<>();
  final Set<String> lastEngaged = new TreeSet<>();
  private final Set<String> prepared = new HashSet<>();
  private final Scenario input;
  Map<String, IntelligenceState.Knowledge> priorKnowledge = Map.of();
  private final Map<String, MovementOrder> orders = new TreeMap<>();
  private final List<DaySimulation.Event> events;
  private final Map<String, Supply> supplies = new TreeMap<>();
  private final int day, departure;
  private final List<Edge> edges;
  private final List<CommunicationNode> relays;
  final Map<String, IntelligenceState.Knowledge> observations = new TreeMap<>();

  BattleRuntime(
      Scenario input,
      Map<String, HexCoord> positions,
      Map<String, RegimentMemory> prior,
      List<MovementOrder> commands,
      List<DaySimulation.Event> events,
      int day,
      int departure) {
    this.input = input;
    this.edges = new ArrayList<>(input.edges());
    this.relays = new ArrayList<>(input.communicationNodes());
    this.positions = positions;
    this.events = events;
    this.day = day;
    this.departure = departure;
    input.regiments().forEach(r -> units.put(r.id(), r));
    input.supplies().forEach(s -> supplies.put(s.id(), s));
    commands.forEach(o -> orders.put(o.regimentId(), o));
    for (var r : input.regiments()) {
      var old = prior.getOrDefault(r.id(), new RegimentMemory(Doctrine.standard(r), List.of()));
      var command = orders.get(r.id());
      if (command != null && command.doctrine() != null)
        old = new RegimentMemory(command.doctrine(), old.supplies());
      memory.put(r.id(), old.observe(input, r, day));
      if (command == null
          || command.action() == Action.DEFEND
          || command.action() == Action.MOVE && command.route().isEmpty()) prepared.add(r.id());
    }
  }

  void activate(MovementOrder command) {
    orders.put(command.regimentId(), command);
    if (command.doctrine() != null) {
      var old = memory.get(command.regimentId());
      memory.put(command.regimentId(), new RegimentMemory(command.doctrine(), old.supplies()));
    }
    if (command.action() != Action.DEFEND
        && !(command.action() == Action.MOVE && command.route().isEmpty()))
      prepared.remove(command.regimentId());
  }

  boolean alive(String id) {
    return units.containsKey(id) && units.get(id).companies().stream().anyMatch(c -> c.hp() > 0);
  }

  Regiment regiment(String id) {
    var r = units.get(id);
    return new Regiment(
        r.id(),
        r.name(),
        r.side(),
        r.role(),
        positions.getOrDefault(id, r.position()),
        r.brigadeId(),
        r.companies());
  }

  void moved(String id) {
    prepared.remove(id);
    memory.put(id, memory.get(id).observe(world(), regiment(id), day));
  }

  Scenario world() {
    return ScenarioRules.normalizeRuntime(
        new Scenario(
            input.schemaVersion(),
            input.name(),
            input.width(),
            input.height(),
            input.cells(),
            edges,
            units.keySet().stream().filter(this::alive).map(this::regiment).toList(),
            new ArrayList<>(supplies.values()),
            relays,
            input.communicationRadius()));
  }

  private int observedEvents;

  void observeAll() {
    var current = world();
    for (var r : current.regiments()) {
      var prior = observations.getOrDefault(r.id(), IntelligenceState.Knowledge.empty());
      observations.put(
          r.id(),
          prior.merge(
              IntelligenceState.observe(
                  current, r, day, prior, events.subList(observedEvents, events.size()))));
    }
    observedEvents = events.size();
  }

  boolean infrastructure(MovementOrder order) {
    if (!alive(order.regimentId())) return false;
    var r = regiment(order.regimentId());
    if (order.action() == Action.BUILD_BRIDGE) {
      if (r.companies().stream().noneMatch(c -> c.hp() > 0 && c.type() == CompanyType.ENGINEER))
        return false;
      for (int i = 0; i < edges.size(); i++) {
        var e = edges.get(i);
        if (e.river()
            && (e.a().equals(order.target()) || e.b().equals(order.target()))
            && r.position().distance(e.a()) <= 1
            && r.position().distance(e.b()) <= 1) {
          edges.set(i, new Edge(e.a(), e.b(), true, Bridge.INTACT, e.road()));
          return true;
        }
      }
    } else {
      int range =
          r.companies().stream().anyMatch(c -> c.hp() > 0 && c.type() == CompanyType.ARTILLERY)
              ? 3
              : 1;
      if (r.position().distance(order.target()) > range) return false;
      for (int i = 0; i < relays.size(); i++) {
        var n = relays.get(i);
        if (n.side() != r.side() && n.position().equals(order.target()) && n.hp() > 0) {
          int damage =
              r.companies().stream()
                  .filter(c -> c.hp() > 0)
                  .mapToInt(c -> Math.max(1, CombatRules.profile(c).soft() * c.hp() / c.maxHp()))
                  .sum();
          relays.set(
              i,
              new CommunicationNode(n.id(), n.position(), n.side(), Math.max(0, n.hp() - damage)));
          return true;
        }
      }
    }
    return false;
  }

  void fight(Set<Pair> contacts, List<MovementOrder> bombardments, int tick) {
    lastEngaged.clear();
    // One directed volley per ordered pair; split firepower when engaging multiple regiments.
    Map<String, Set<String>> targets = new TreeMap<>();
    Set<String> indirect = new HashSet<>();
    Set<String> melee = new HashSet<>();
    Map<String, HexCoord> contested = new HashMap<>();
    for (var pair : contacts)
      if (alive(pair.a())
          && alive(pair.b())
          && units.get(pair.a()).side() != units.get(pair.b()).side()) {
        melee.add(pair.a() + ":" + pair.b());
        melee.add(pair.b() + ":" + pair.a());
        if (pair.contested() != null) {
          contested.put(pair.a() + ":" + pair.b(), pair.contested());
          contested.put(pair.b() + ":" + pair.a(), pair.contested());
        }
        targets.computeIfAbsent(pair.a(), k -> new TreeSet<>()).add(pair.b());
        targets.computeIfAbsent(pair.b(), k -> new TreeSet<>()).add(pair.a());
      }
    for (var o : bombardments)
      if (alive(o.regimentId())) {
        var target =
            positions.entrySet().stream()
                .filter(
                    e ->
                        e.getValue().equals(o.target())
                            && alive(e.getKey())
                            && units.get(e.getKey()).side() != units.get(o.regimentId()).side())
                .map(Map.Entry::getKey)
                .findFirst();
        if (target.isEmpty() || positions.get(o.regimentId()).distance(o.target()) > 3) {
          log(
              tick,
              "WorldEvent",
              "BOMBARD_MISSED",
              o.regimentId(),
              o.target(),
              List.of(o.regimentId()),
              "目标格无敌军或已超出射程",
              null,
              null,
              null);
          continue;
        }
        String enemy = target.get();
        targets.computeIfAbsent(o.regimentId(), k -> new TreeSet<>()).add(enemy);
        indirect.add(o.regimentId() + ":" + enemy);
        targets.computeIfAbsent(enemy, k -> new TreeSet<>()).add(o.regimentId());
        if (positions.get(enemy).distance(positions.get(o.regimentId())) > 1)
          indirect.add(enemy + ":" + o.regimentId());
      }
    record Aimed(String target, CombatRules.Hit hit) {}
    List<Aimed> hits = new ArrayList<>();
    for (var entry : targets.entrySet())
      for (var target : entry.getValue()) {
        String source = entry.getKey();
        underFire.add(source);
        underFire.add(target);
        lastEngaged.add(source);
        lastEngaged.add(target);
        var cell =
            input.cells().stream()
                .filter(
                    c ->
                        c.position()
                            .equals(
                                contested.getOrDefault(
                                    source + ":" + target, positions.get(target))))
                .findFirst()
                .orElseThrow();
        for (var hit :
            CombatRules.volley(
                regiment(source),
                regiment(target),
                melee.contains(source + ":" + target)
                    ? 1
                    : positions.get(source).distance(positions.get(target)),
                indirect.contains(source + ":" + target),
                prepared.contains(target) && !contested.containsKey(source + ":" + target),
                cell.terrain(),
                cell.fortification())) {
          int share = entry.getValue().size();
          int readiness = prepared.contains(source) ? 110 : 100;
          hits.add(
              new Aimed(
                  target,
                  new CombatRules.Hit(
                      hit.sourceRegimentId(),
                      hit.sourceCompanyId(),
                      hit.targetCompanyId(),
                      hit.rawPower() * readiness / (100 * share),
                      hit.protection(),
                      hit.damageType(),
                      hit.damage() * readiness / (100 * share))));
        }
      }
    for (var aimed : hits) {
      var hit = aimed.hit();
      var target = units.get(aimed.target());
      var victim =
          target.companies().stream()
              .filter(c -> c.id().equals(hit.targetCompanyId()))
              .findFirst()
              .orElseThrow();
      int after = Math.max(0, victim.hp() - hit.damage());
      if (after == victim.hp()) continue;
      var changed =
          new Company(victim.id(), victim.type(), victim.equipment(), victim.maxHp(), after);
      replaceCompany(aimed.target(), changed);
      log(
          tick,
          "WorldEvent",
          "DAMAGE",
          aimed.target(),
          positions.get(aimed.target()),
          List.of(hit.sourceRegimentId(), aimed.target()),
          "同刻火力；组织度只缩放一次，防护只作用于实际受击连",
          new Damage(
              hit.sourceRegimentId(),
              hit.sourceCompanyId(),
              victim.id(),
              victim.hp(),
              after,
              CombatRules.organization(victim),
              CombatRules.organization(changed),
              hit.rawPower(),
              hit.protection(),
              hit.damageType()),
          null,
          null);
    }
    for (var id : new ArrayList<>(positions.keySet()))
      if (!alive(id)) {
        log(
            tick,
            "WorldEvent",
            "DESTROYED",
            id,
            positions.get(id),
            List.of(id),
            "全部连队失去有效 HP，移出占格",
            null,
            null,
            null);
        positions.remove(id);
      }
    // Prepared occupants with collapsed cohesion may be driven back, never choose a winner by loop
    // order.
    Map<String, List<HexCoord>> candidates = new TreeMap<>();
    for (var pair : contacts)
      for (var id : List.of(pair.a(), pair.b())) {
        if (!alive(id)
            || !prepared.contains(id)
            || regiment(id).role() == Role.DIVISION_HQ
            || CombatRules.organization(regiment(id)) >= 200) continue;
        var self = regiment(id);
        var enemies =
            contacts.stream()
                .filter(p -> p.a().equals(id) || p.b().equals(id))
                .map(p -> p.a().equals(id) ? p.b() : p.a())
                .filter(this::alive)
                .filter(e -> units.get(e).side() != self.side())
                .toList();
        if (enemies.isEmpty()) continue;
        var free =
            input.cells().stream()
                .map(Cell::position)
                .filter(
                    p ->
                        p.distance(self.position()) == 1
                            && !positions.containsValue(p)
                            && Mobility.speed(input, self, self.position(), p) > 0)
                .sorted(
                    Comparator.comparingInt(
                            (HexCoord p) ->
                                enemies.stream()
                                    .mapToInt(e -> p.distance(positions.get(e)))
                                    .min()
                                    .orElse(0))
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        if (!free.isEmpty()) candidates.put(id, free);
      }
    Map<HexCoord, List<String>> claims = new TreeMap<>();
    candidates.forEach(
        (id, path) -> claims.computeIfAbsent(path.getFirst(), k -> new ArrayList<>()).add(id));
    claims.forEach(
        (to, ids) -> {
          if (ids.size() == 1) {
            var id = ids.getFirst();
            log(
                tick,
                "WorldEvent",
                "DISPLACED",
                id,
                to,
                List.of(id),
                "组织度低于 20%，被驱离至相邻可通行空格",
                null,
                null,
                null);
            positions.put(id, to);
            moved(id);
          }
        });
  }

  DoctrinePlanner.Decision decision(String id, boolean failed, int tick) {
    var self = regiment(id);
    Set<HexCoord> observed = new HashSet<>();
    positions.forEach(
        (other, p) -> {
          if (!other.equals(id) && p.distance(self.position()) <= 1) observed.add(p);
        });
    var known =
        priorKnowledge
            .getOrDefault(id, IntelligenceState.Knowledge.empty())
            .merge(observations.getOrDefault(id, IntelligenceState.Knowledge.empty()))
            .merge(
                IntelligenceState.observe(
                    world(), self, day, IntelligenceState.Knowledge.empty(), List.of()));
    var decision =
        DoctrinePlanner.decide(
            new DoctrinePlanner.TerrainMap(
                input.width(),
                input.height(),
                input.cells(),
                known.edges().values().stream().map(IntelligenceState.KnownEdge::edge).toList()),
            self,
            memory.get(id),
            observed,
            failed);
    log(
        tick,
        "DoctrineDecision",
        decision.action(),
        id,
        decision.route().isEmpty() ? self.position() : decision.route().getLast(),
        List.of(id),
        decision.reason(),
        null,
        decision,
        null);
    return decision;
  }

  void rest(int tick) {
    for (var o : orders.values())
      if (o.action() == Action.REST && alive(o.regimentId())) {
        String id = o.regimentId();
        var r = regiment(id);
        var supply =
            supplies.values().stream()
                .filter(
                    s -> s.position().equals(r.position()) && s.side() == r.side() && s.stock() > 0)
                .findFirst();
        if (underFire.contains(id) || supply.isEmpty()) {
          log(
              tick,
              "OrderTransition",
              "REST_FAILED",
              id,
              r.position(),
              List.of(id),
              underFire.contains(id) ? "当日受到火力，不能休整" : "当前位置没有有库存的己方补给点",
              null,
              null,
              null);
          continue;
        }
        var s = supply.get();
        int stock = s.stock();
        for (var c : r.companies())
          if (c.hp() > 0 && c.hp() < c.maxHp() && stock > 0) {
            int gain = Math.min(stock, Math.min(c.maxHp() - c.hp(), Math.max(1, c.maxHp() / 10)));
            var changed = new Company(c.id(), c.type(), c.equipment(), c.maxHp(), c.hp() + gain);
            replaceCompany(id, changed);
            stock -= gain;
            log(
                tick,
                "WorldEvent",
                "RECOVERED",
                id,
                r.position(),
                List.of(id),
                "消耗 1 库存恢复 1 HP；不复活被歼灭连",
                new Damage(
                    id,
                    c.id(),
                    c.id(),
                    c.hp(),
                    changed.hp(),
                    CombatRules.organization(c),
                    CombatRules.organization(changed),
                    0,
                    0,
                    "RECOVERY"),
                null,
                null);
          }
        supplies.put(s.id(), new Supply(s.id(), s.position(), s.side(), stock));
        log(
            tick,
            "WorldEvent",
            "SUPPLY_USED",
            id,
            r.position(),
            List.of(id),
            "原地休整库存结算",
            null,
            null,
            new StockChange(s.id(), s.stock(), stock));
        log(
            tick,
            "OrderTransition",
            "COMPLETED",
            id,
            r.position(),
            List.of(id),
            "原地休整结束",
            null,
            null,
            null);
      }
    for (var id : positions.keySet())
      memory.put(id, memory.get(id).observe(world(), regiment(id), day));
  }

  private void replaceCompany(String id, Company changed) {
    var r = units.get(id);
    units.put(
        id,
        new Regiment(
            r.id(),
            r.name(),
            r.side(),
            r.role(),
            r.position(),
            r.brigadeId(),
            r.companies().stream().map(c -> c.id().equals(changed.id()) ? changed : c).toList()));
  }

  private void log(
      int tick,
      String category,
      String kind,
      String id,
      HexCoord to,
      List<String> participants,
      String reason,
      Damage damage,
      DoctrinePlanner.Decision decision,
      StockChange stock) {
    var order = orders.get(id);
    events.add(
        new DaySimulation.Event(
            1,
            "d" + day + "-e" + (events.size() + 1),
            category,
            kind,
            day,
            Math.max(0, (tick - departure + 14) / 15),
            tick,
            0,
            order == null ? "default-" + id : order.orderId(),
            id,
            positions.getOrDefault(id, units.get(id).position()),
            to,
            participants.stream().sorted().toList(),
            reason,
            damage,
            decision,
            stock));
  }
}
