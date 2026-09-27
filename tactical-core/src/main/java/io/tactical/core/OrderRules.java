package io.tactical.core;

import static io.tactical.core.Scenario.*;

import java.util.*;

public final class OrderRules {
  private OrderRules() {}

  public static List<MovementOrder> validate(
      Scenario world, Side side, List<MovementOrder> orders) {
    if (side == null || orders == null || orders.size() > 32)
      throw new ScenarioViolation("必须指定阵营及最多 32 条命令");
    Set<String> ids = new HashSet<>(), units = new HashSet<>();
    for (var o : orders) {
      if (o == null || !ids.add(o.orderId()) || !units.add(o.regimentId()))
        throw new ScenarioViolation("命令 ID 和被指挥团不得重复");
      var r =
          world.regiments().stream()
              .filter(u -> u.id().equals(o.regimentId()))
              .findFirst()
              .orElseThrow(() -> new ScenarioViolation("命令中的团不存在"));
      if (r.side() != side) throw new ScenarioViolation("不得向另一方部队下令");
      if (r.role() == Role.DIVISION_HQ && !o.route().isEmpty())
        throw new ScenarioViolation("城市师部不可移动");
      if (o.action() == MovementOrder.Action.BOMBARD) {
        if (o.target().q() < 0
            || o.target().q() >= world.width()
            || o.target().r() < 0
            || o.target().r() >= world.height()
            || r.position().distance(o.target()) < 1
            || r.position().distance(o.target()) > 3
            || r.companies().stream()
                .noneMatch(c -> c.hp() > 0 && c.type() == CompanyType.ARTILLERY))
          throw new ScenarioViolation("炮击需要有效炮兵连及 1–3 格内的目标");
      }
      if (o.action() == MovementOrder.Action.BUILD_BRIDGE
          || o.action() == MovementOrder.Action.STRIKE_RELAY) {
        var target = o.target();
        if (target.q() < 0
            || target.r() < 0
            || target.q() >= world.width()
            || target.r() >= world.height()) throw new ScenarioViolation("设施目标超出地图");
        if (o.action() == MovementOrder.Action.BUILD_BRIDGE
            && (r.position().distance(target) != 1
                || r.companies().stream()
                    .noneMatch(c -> c.hp() > 0 && c.type() == CompanyType.ENGINEER)
                || world.edges().stream()
                    .noneMatch(
                        e ->
                            e.river()
                                && (e.a().equals(target) || e.b().equals(target))
                                && r.position().distance(e.a()) <= 1
                                && r.position().distance(e.b()) <= 1)))
          throw new ScenarioViolation("架桥需要工兵及本团相邻河流边");
        if (o.action() == MovementOrder.Action.STRIKE_RELAY
            && (r.position().distance(target)
                    > (r.companies().stream()
                            .anyMatch(c -> c.hp() > 0 && c.type() == CompanyType.ARTILLERY)
                        ? 3
                        : 1)
                || world.communicationNodes().stream()
                    .noneMatch(n -> n.side() != side && n.position().equals(target))))
          throw new ScenarioViolation("目标必须为射程内敌方通信工事");
      }
      var from = r.position();
      for (var to : o.route()) {
        if (to.q() < 0
            || to.r() < 0
            || to.q() >= world.width()
            || to.r() >= world.height()
            || from.distance(to) != 1) throw new ScenarioViolation("路径必须由地图内的相邻格组成");
        from = to;
      }
    }
    return orders.stream().sorted(Comparator.comparing(MovementOrder::regimentId)).toList();
  }
}
