package io.tactical.core;

import java.util.*;

/** Explicit, truthful day-zero intelligence for an existing observer. Never arbitrary snapshots. */
public record InitialKnowledge(
    String observerId, List<String> regimentIds, List<String> supplyIds, List<String> relayIds) {
  public InitialKnowledge {
    if (observerId == null || !observerId.matches("[A-Za-z0-9_-]{1,64}"))
      throw new ScenarioViolation("初始情报需要合法观察者 ID");
    regimentIds = copy(regimentIds, 32);
    supplyIds = copy(supplyIds, 64);
    relayIds = copy(relayIds, 32);
  }

  private static List<String> copy(List<String> ids, int max) {
    if (ids == null
        || ids.size() > max
        || ids.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,64}")))
      throw new ScenarioViolation("初始情报引用不合法或超出上限");
    return ids.stream().distinct().sorted().toList();
  }

  public void validate(Scenario s) {
    if (s.regiments().stream().noneMatch(r -> r.id().equals(observerId))
        || !s.regiments().stream().map(Scenario.Regiment::id).toList().containsAll(regimentIds)
        || !s.supplies().stream().map(Scenario.Supply::id).toList().containsAll(supplyIds)
        || !s.communicationNodes().stream()
            .map(Scenario.CommunicationNode::id)
            .toList()
            .containsAll(relayIds)) throw new ScenarioViolation("初始情报的观察者或目标不存在；请更新情报引用");
  }
}
