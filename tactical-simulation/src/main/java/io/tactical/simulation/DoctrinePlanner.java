package io.tactical.simulation;

import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import io.tactical.simulation.RegimentMemory.KnownSupply;
import java.util.*;

/**
 * Receives public terrain, self, local obstacles and copied knowledge; never WorldState/supplies.
 */
public final class DoctrinePlanner {
  public record TerrainMap(int width, int height, List<Cell> cells, List<Edge> edges) {
    public TerrainMap {
      cells = List.copyOf(cells);
      edges = List.copyOf(edges);
    }
  }

  public record Decision(
      String rule,
      String action,
      String targetSupplyId,
      List<HexCoord> route,
      int organization,
      List<KnownSupply> knowledge,
      String reason) {
    public Decision {
      route = List.copyOf(route);
      knowledge = List.copyOf(knowledge);
    }
  }

  public static Decision decide(
      TerrainMap map,
      Regiment self,
      RegimentMemory memory,
      Set<HexCoord> observedOccupied,
      boolean breakthroughFailed) {
    var doctrine = memory.doctrine();
    int org = CombatRules.organization(self);
    String trigger =
        org < doctrine.withdrawBelowPercent() * 10
            ? "LOW_ORGANIZATION"
            : breakthroughFailed && doctrine.template() == Doctrine.Template.BREAKTHROUGH
                ? "BREAKTHROUGH_FAILED"
                : "CONTACT_HOLD";
    if (self.role() == Role.DIVISION_HQ
        || doctrine.template() == Doctrine.Template.HOLD
        || trigger.equals("CONTACT_HOLD"))
      return new Decision(trigger, "HOLD", "", List.of(), org, memory.supplies(), "保持当前位置");
    List<Decision> reachable = new ArrayList<>();
    for (var known : memory.supplies()) {
      if (known.side() != self.side()
          || known.stock() <= 0
          || !doctrine.supplyId().isEmpty() && !known.id().equals(doctrine.supplyId())) continue;
      var path = path(map, self, known.position(), observedOccupied);
      if (path != null)
        reachable.add(
            new Decision(
                trigger,
                "WITHDRAW",
                known.id(),
                path,
                org,
                memory.supplies(),
                "撤向已知且地形可达的己方补给点；路径占据按本地观察判断"));
    }
    return reachable.stream()
        .min(
            Comparator.comparingInt((Decision d) -> d.route().size())
                .thenComparing(Decision::targetSupplyId))
        .orElse(
            new Decision(
                trigger,
                "HOLD",
                "",
                List.of(),
                org,
                memory.supplies(),
                "无已知可达且有库存的指定／己方补给点，执行原地防御备用动作"));
  }

  private static List<HexCoord> path(
      TerrainMap map, Regiment self, HexCoord target, Set<HexCoord> blocked) {
    var terrain =
        new Scenario(
            1,
            "public terrain",
            map.width(),
            map.height(),
            map.cells(),
            map.edges(),
            List.of(),
            List.of());
    Map<HexCoord, HexCoord> previous = new HashMap<>();
    Deque<HexCoord> queue = new ArrayDeque<>();
    queue.add(self.position());
    previous.put(self.position(), self.position());
    while (!queue.isEmpty()) {
      var at = queue.removeFirst();
      if (at.equals(target)) {
        var result = new ArrayList<HexCoord>();
        for (var p = target; !p.equals(self.position()); p = previous.get(p)) result.add(p);
        Collections.reverse(result);
        return result;
      }
      for (var to :
          map.cells().stream()
              .map(Cell::position)
              .filter(p -> at.distance(p) == 1)
              .sorted()
              .toList())
        if (!previous.containsKey(to)
            && !blocked.contains(to)
            && Mobility.speed(terrain, self, at, to) > 0) {
          previous.put(to, at);
          queue.addLast(to);
        }
    }
    return null;
  }
}
