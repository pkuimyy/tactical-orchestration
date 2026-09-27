package io.tactical.simulation;

import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import java.util.*;

/** Physical task outcome is never itself permission to start a dependent task. */
public final class OperationRuntime {
  public record State(
      String operationId,
      String orderId,
      String regimentId,
      String status,
      boolean received,
      Map<String, String> confirmed,
      String fact,
      HexCoord resultPosition) {
    public State {
      confirmed = Collections.unmodifiableMap(new TreeMap<>(confirmed));
    }
  }

  private static final class Task {
    final MovementOrder order;
    final OperationPlan.Node node;
    boolean received;
    String status = "WAITING_DELIVERY", fact = "UNKNOWN";
    HexCoord resultPosition;
    final Map<String, String> confirmed = new TreeMap<>();

    Task(MovementOrder o, OperationPlan.Node n) {
      order = o;
      node = n;
    }
  }

  private final OperationPlan plan;
  private final Map<String, Task> tasks = new TreeMap<>();

  OperationRuntime(OperationPlan plan, List<MovementOrder> orders) {
    this.plan = plan;
    for (var n : plan.nodes())
      tasks.put(
          n.orderId(),
          new Task(
              orders.stream()
                  .filter(o -> o.orderId().equals(n.orderId()))
                  .findFirst()
                  .orElseThrow(),
              n));
  }

  boolean contains(String regimentId) {
    return tasks.values().stream().anyMatch(t -> t.order.regimentId().equals(regimentId));
  }

  int delay(Scenario world, long seed, int day, String regimentId) {
    if (plan.mode() != OperationPlan.Mode.INDEPENDENT) return 0;
    var r =
        world.regiments().stream().filter(u -> u.id().equals(regimentId)).findFirst().orElseThrow();
    var terrain =
        world.cells().stream()
            .filter(c -> c.position().equals(r.position()))
            .findFirst()
            .orElseThrow()
            .terrain();
    // Stable keyed scheduling conditioned on local terrain; no unrelated global RNG consumption.
    return Math.floorMod(
        DaySimulation.digest(seed + ":" + day + ":" + terrain + ":" + regimentId).hashCode(), 25);
  }

  String ready(Scenario world, String regimentId) {
    var t =
        tasks.values().stream()
            .filter(v -> v.order.regimentId().equals(regimentId))
            .findFirst()
            .orElseThrow();
    var net = new CommunicationNetwork(world);
    var self =
        world.regiments().stream().filter(r -> r.id().equals(regimentId)).findFirst().orElse(null);
    var brigade =
        world.regiments().stream()
            .filter(r -> r.id().equals(plan.brigadeId()) && CommunicationNetwork.signals(r) >= 2)
            .findFirst()
            .orElse(null);
    if (self == null) return t.status = "CANCELLED";
    if (plan.mode() == OperationPlan.Mode.INDEPENDENT) {
      t.received = true;
      return t.status = "EXECUTING";
    }
    if (!t.received
        && brigade != null
        && net.toDivision(brigade.id())
        && net.connected(brigade.id(), regimentId)) t.received = true;
    if (!t.received) return t.status = "WAITING_DELIVERY";
    for (var dep : t.node.after()) {
      var source = tasks.get(dep);
      if (source.fact.equals("UNKNOWN")) continue;
      boolean radio =
          brigade != null
              && net.connected(source.order.regimentId(), brigade.id())
              && net.connected(brigade.id(), regimentId);
      if (radio) t.confirmed.put(dep, source.fact);
    }
    boolean failure = t.confirmed.values().stream().anyMatch(v -> v.equals("FAILED"));
    if (failure)
      return t.status =
          switch (t.node.fallback()) {
            case HOLD -> "HOLD";
            case CANCEL -> "CANCELLED";
            case CONTINUE -> "EXECUTING_FALLBACK";
          };
    if (t.confirmed.size() != t.node.after().size()) return t.status = "WAITING_CONFIRMATION";
    return t.status = "EXECUTING";
  }

  void outcomes(Scenario world, List<DaySimulation.Event> events) {
    for (var t : tasks.values()) {
      if (!t.fact.equals("UNKNOWN")) continue;
      for (var e : events) {
        if (!e.orderId().equals(t.order.orderId())
            || !e.regimentId().equals(t.order.regimentId())
            || !e.category().equals("OrderTransition")) continue;
        if (e.kind().equals("COMPLETED")) {
          t.fact = "SUCCESS";
          t.status = "COMPLETED";
          t.resultPosition = e.to();
        }
        if (Set.of("IMPASSABLE", "BLOCKED", "ACTION_FAILED", "CANCELLED").contains(e.kind())) {
          t.fact = "FAILED";
          if (!Set.of("HOLD", "CANCELLED").contains(t.status)) t.status = "FAILED";
          t.resultPosition = e.from();
        }
      }
      if (world.regiments().stream().noneMatch(r -> r.id().equals(t.order.regimentId()))) {
        t.fact = "FAILED";
        t.status = "FAILED";
        events.stream()
            .filter(
                e -> e.kind().equals("DESTROYED") && e.regimentId().equals(t.order.regimentId()))
            .reduce((a, b) -> b)
            .ifPresent(e -> t.resultPosition = e.from());
      }
      if (!t.fact.equals("UNKNOWN") && t.resultPosition != null) {
        for (var recipient : tasks.values())
          if (recipient.received && recipient.node.after().contains(t.order.orderId())) {
            boolean witnessed =
                world.regiments().stream()
                    .anyMatch(
                        r ->
                            r.id().equals(recipient.order.regimentId())
                                && r.position().distance(t.resultPosition) <= 1);
            if (witnessed) recipient.confirmed.put(t.order.orderId(), t.fact);
          }
      }
    }
  }

  List<State> states() {
    return tasks.values().stream()
        .map(
            t ->
                new State(
                    plan.id(),
                    t.order.orderId(),
                    t.order.regimentId(),
                    t.status,
                    t.received,
                    t.confirmed,
                    t.fact,
                    t.resultPosition))
        .toList();
  }

  String reason(String regimentId) {
    var s =
        states().stream().filter(v -> v.regimentId().equals(regimentId)).findFirst().orElseThrow();
    return "operation=" + plan.id() + "; received=" + s.received() + "; confirmed=" + s.confirmed();
  }
}
