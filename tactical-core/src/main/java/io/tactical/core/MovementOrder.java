package io.tactical.core;

import java.util.List;

/** Day command with adjacent waypoints, action and optional versioned doctrine override. */
public record MovementOrder(
    String orderId,
    String regimentId,
    List<HexCoord> route,
    Action action,
    HexCoord target,
    Doctrine doctrine) {
  public enum Action {
    MOVE,
    DEFEND,
    BOMBARD,
    REST,
    BUILD_BRIDGE,
    STRIKE_RELAY
  }

  public MovementOrder(String orderId, String regimentId, List<HexCoord> route) {
    this(orderId, regimentId, route, Action.MOVE, null, null);
  }

  public MovementOrder {
    if (orderId == null
        || !orderId.matches("[A-Za-z0-9_-]{1,64}")
        || regimentId == null
        || regimentId.isBlank()
        || regimentId.length() > 64
        || route == null
        || route.size() > 64
        || route.stream().anyMatch(java.util.Objects::isNull))
      throw new ScenarioViolation("命令需要合法 ID、团 ID 和最多 64 个相邻路径点");
    route = List.copyOf(route);
    action = action == null ? Action.MOVE : action;
    if (action != Action.MOVE && !route.isEmpty())
      throw new ScenarioViolation("防御、炮击与休整命令不能同时携带行军路径");
    if ((action == Action.BOMBARD || action == Action.BUILD_BRIDGE || action == Action.STRIKE_RELAY)
        != (target != null)) throw new ScenarioViolation("炮击、架桥和通信工事攻击必须指定目标格");
  }
}
