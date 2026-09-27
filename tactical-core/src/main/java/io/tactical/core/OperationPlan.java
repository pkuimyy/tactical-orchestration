package io.tactical.core;

import java.util.*;

/** One brigade, one explicit theater, one acyclic set of daily tasks. */
public record OperationPlan(
    String id, String brigadeId, Mode mode, List<HexCoord> theater, List<Node> nodes) {
  public enum Mode {
    COORDINATED,
    INDEPENDENT
  }

  public enum Fallback {
    HOLD,
    CANCEL,
    CONTINUE
  }

  public record Node(String orderId, List<String> after, Fallback fallback) {
    public Node {
      if (orderId == null
          || after == null
          || after.size() > 16
          || after.stream().anyMatch(Objects::isNull)
          || fallback == null) throw new ScenarioViolation("任务需要命令 ID、前置依赖和失败分支");
      after = after.stream().distinct().sorted().toList();
    }
  }

  public OperationPlan {
    if (id == null
        || !id.matches("[A-Za-z0-9_-]{1,64}")
        || mode == null
        || theater == null
        || theater.isEmpty()
        || theater.size() > 256
        || theater.stream().anyMatch(Objects::isNull)
        || nodes == null
        || nodes.isEmpty()
        || nodes.size() > 16
        || nodes.stream().anyMatch(Objects::isNull))
      throw new ScenarioViolation("协作需要合法 ID、战区及 1–16 个任务");
    brigadeId = brigadeId == null ? "" : brigadeId;
    theater = theater.stream().distinct().sorted().toList();
    nodes = nodes.stream().sorted(Comparator.comparing(Node::orderId)).toList();
    var graph = new TreeMap<String, Node>();
    for (var n : nodes)
      if (graph.put(n.orderId(), n) != null) throw new ScenarioViolation("协作任务重复");
    var done = new HashSet<String>();
    for (var n : nodes) visit(n.orderId(), graph, new HashSet<>(), done);
  }

  private static void visit(
      String id, Map<String, Node> graph, Set<String> path, Set<String> done) {
    if (done.contains(id)) return;
    if (!graph.containsKey(id)) throw new ScenarioViolation("前置任务不存在");
    if (!path.add(id)) throw new ScenarioViolation("协作依赖不能成环");
    for (var dep : graph.get(id).after()) visit(dep, graph, path, done);
    path.remove(id);
    done.add(id);
  }

  public void validate(Scenario world, Scenario.Side side, List<MovementOrder> orders) {
    for (var c : theater)
      if (c.q() < 0 || c.r() < 0 || c.q() >= world.width() || c.r() >= world.height())
        throw new ScenarioViolation("战区超出地图");
    if (mode == Mode.COORDINATED
        && world.regiments().stream()
            .noneMatch(
                r ->
                    r.id().equals(brigadeId)
                        && r.side() == side
                        && r.role() == Scenario.Role.BRIGADE_HQ
                        && r.companies().stream()
                                .filter(c -> c.hp() > 0 && c.type() == Scenario.CompanyType.SIGNAL)
                                .count()
                            >= 2)) throw new ScenarioViolation("协作旅部需要两个存活通信连");
    for (var node : nodes) {
      var o =
          orders.stream()
              .filter(v -> v.orderId().equals(node.orderId()))
              .findFirst()
              .orElseThrow(() -> new ScenarioViolation("协作引用的命令不存在"));
      var r =
          world.regiments().stream()
              .filter(v -> v.id().equals(o.regimentId()) && v.side() == side)
              .findFirst()
              .orElseThrow(() -> new ScenarioViolation("任务团不存在或阵营错误"));
      if (mode == Mode.COORDINATED && !brigadeId.equals(r.brigadeId()))
        throw new ScenarioViolation("只能协调本旅部属团");
      if (!theater.contains(r.position())
          || !theater.containsAll(o.route())
          || o.target() != null && !theater.contains(o.target()))
        throw new ScenarioViolation("任务必须位于单一战区内");
      if (o.action() == MovementOrder.Action.REST) throw new ScenarioViolation("休整在日末结算，不作为日内协作节点");
    }
  }
}
