package io.tactical.core;

import java.util.List;

/** Explicit adjacent waypoints; empty route means hold. No client-side pathfinding is trusted. */
public record MovementOrder(String orderId, String regimentId, List<HexCoord> route) {
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
  }
}
