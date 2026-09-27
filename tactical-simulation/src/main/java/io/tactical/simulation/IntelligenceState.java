package io.tactical.simulation;

import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import java.util.*;

/** Immutable knowledge, distinct from world facts. Reports observed on N are eligible on N+1. */
public record IntelligenceState(
    Map<String, Knowledge> regiments,
    Map<String, Knowledge> brigades,
    Map<Side, Knowledge> divisions,
    List<Report> pending) {
  public record Contact(Regiment unit, int observedDay, boolean destroyed, int observedTick) {
    public Contact(Regiment unit, int observedDay, boolean destroyed) {
      this(unit, observedDay, destroyed, 0);
    }
  }

  public record KnownRelay(CommunicationNode node, int observedDay, int observedTick) {
    public KnownRelay(CommunicationNode node, int observedDay) {
      this(node, observedDay, 0);
    }
  }

  public record KnownEdge(Edge edge, int observedDay, int observedTick) {
    public KnownEdge(Edge edge, int observedDay) {
      this(edge, observedDay, 0);
    }
  }

  public record Knowledge(
      Map<String, Contact> contacts,
      Map<String, RegimentMemory.KnownSupply> supplies,
      Map<String, KnownRelay> relays,
      Map<String, KnownEdge> edges,
      List<DaySimulation.Event> events) {
    public Knowledge {
      contacts = sorted(contacts);
      supplies = sorted(supplies);
      relays = sorted(relays);
      edges = sorted(edges);
      events = List.copyOf(events);
    }

    public static Knowledge empty() {
      return new Knowledge(Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    public Knowledge merge(Knowledge other) {
      var c = new TreeMap<>(contacts);
      other.contacts.forEach(
          (id, v) ->
              c.merge(
                  id,
                  v,
                  (a, b) ->
                      a.observedDay() > b.observedDay()
                              || a.observedDay() == b.observedDay()
                                  && a.observedTick() > b.observedTick()
                          ? a
                          : b));
      var s = new TreeMap<>(supplies);
      other.supplies.forEach(
          (id, v) -> s.merge(id, v, (a, b) -> a.observedDay() > b.observedDay() ? a : b));
      var r = new TreeMap<>(relays);
      other.relays.forEach(
          (id, v) ->
              r.merge(
                  id,
                  v,
                  (a, b) ->
                      a.observedDay() > b.observedDay()
                              || a.observedDay() == b.observedDay()
                                  && a.observedTick() > b.observedTick()
                          ? a
                          : b));
      var e = new TreeMap<>(edges);
      other.edges.forEach(
          (id, v) ->
              e.merge(
                  id,
                  v,
                  (a, b) ->
                      a.observedDay() > b.observedDay()
                              || a.observedDay() == b.observedDay()
                                  && a.observedTick() > b.observedTick()
                          ? a
                          : b));
      var logs = new TreeMap<String, DaySimulation.Event>();
      events.forEach(v -> logs.put(v.id(), v));
      other.events.forEach(v -> logs.put(v.id(), v));
      // Knowledge retains the latest day's report log; full immutable history remains in day
      // exports.
      int latest = logs.values().stream().mapToInt(DaySimulation.Event::day).max().orElse(0);
      return new Knowledge(
          c, s, r, e, logs.values().stream().filter(v -> v.day() == latest).toList());
    }
  }

  public record Report(String source, Side side, int observedDay, Knowledge knowledge) {}

  public IntelligenceState {
    regiments = sorted(regiments);
    brigades = sorted(brigades);
    divisions = Collections.unmodifiableMap(new TreeMap<>(divisions));
    pending = List.copyOf(pending);
  }

  private static <V> Map<String, V> sorted(Map<String, V> value) {
    return Collections.unmodifiableMap(new TreeMap<>(value));
  }

  public static String edgeId(Edge e) {
    return e.a().compareTo(e.b()) < 0 ? e.a() + "/" + e.b() : e.b() + "/" + e.a();
  }

  public static IntelligenceState initial(Scenario world) {
    var units = new TreeMap<String, Knowledge>();
    var divisions = new TreeMap<Side, Knowledge>();
    var brigades = new TreeMap<String, Knowledge>();
    for (var side : Side.values()) {
      var contacts = new TreeMap<String, Contact>();
      world.regiments().stream()
          .filter(r -> r.side() == side)
          .forEach(r -> contacts.put(r.id(), new Contact(r, 0, false)));
      var relays = new TreeMap<String, KnownRelay>();
      world.communicationNodes().stream()
          .filter(n -> n.side() == side)
          .forEach(n -> relays.put(n.id(), new KnownRelay(n, 0)));
      // Initial deployment / surveyed bridge state is public scenario input, enemy units are not.
      var edges = new TreeMap<String, KnownEdge>();
      world.edges().forEach(e -> edges.put(edgeId(e), new KnownEdge(e, 0)));
      divisions.put(side, new Knowledge(contacts, Map.of(), relays, edges, List.of()));
    }
    for (var r : world.regiments()) {
      var local = observe(world, r, 0, Knowledge.empty(), List.of());
      local =
          new Knowledge(Map.of(), Map.of(), Map.of(), divisions.get(r.side()).edges(), List.of())
              .merge(local);
      units.put(r.id(), local);
      if (r.role() == Role.BRIGADE_HQ) brigades.put(r.id(), local);
      if (r.role() == Role.DIVISION_HQ)
        divisions.put(r.side(), divisions.get(r.side()).merge(local));
    }
    return new IntelligenceState(units, brigades, divisions, List.of());
  }

  public static Knowledge observe(
      Scenario world,
      Regiment observer,
      int day,
      Knowledge prior,
      List<DaySimulation.Event> events) {
    int tick =
        Math.max(
            events.stream().mapToInt(DaySimulation.Event::tick).max().orElse(0),
            prior.contacts().values().stream()
                .filter(c -> c.observedDay() == day)
                .mapToInt(Contact::observedTick)
                .max()
                .orElse(0));
    var contacts = new TreeMap<String, Contact>();
    for (var r : world.regiments())
      if (observer.position().distance(r.position()) <= 1)
        contacts.put(r.id(), new Contact(r, day, false, tick));
    // A local witness may confirm a casualty; distant disappearance is never inferred.
    for (var c : prior.contacts().values())
      if (events.stream()
          .anyMatch(
              e ->
                  e.kind().equals("DESTROYED")
                      && e.regimentId().equals(c.unit().id())
                      && e.from() != null
                      && observer.position().distance(e.from()) <= 1))
        contacts.put(c.unit().id(), new Contact(c.unit(), day, true, tick));
    var supplies = new TreeMap<String, RegimentMemory.KnownSupply>();
    for (var s : world.supplies())
      if (observer.position().distance(s.position()) <= 1)
        supplies.put(
            s.id(), new RegimentMemory.KnownSupply(s.id(), s.position(), s.side(), s.stock(), day));
    var relays = new TreeMap<String, KnownRelay>();
    for (var n : world.communicationNodes())
      if (observer.position().distance(n.position()) <= 1)
        relays.put(n.id(), new KnownRelay(n, day, tick));
    var edges = new TreeMap<String, KnownEdge>();
    for (var e : world.edges())
      if (observer.position().distance(e.a()) <= 1 || observer.position().distance(e.b()) <= 1)
        edges.put(edgeId(e), new KnownEdge(e, day, tick));
    var logs =
        events.stream()
            .filter(
                e ->
                    !e.kind().equals("BOMBARD_MISSED")
                        || e.to() != null && observer.position().distance(e.to()) <= 1)
            .filter(
                e ->
                    e.regimentId().equals(observer.id())
                        || !e.category().equals("DoctrineDecision")
                            && ((e.from() != null && observer.position().distance(e.from()) <= 1)
                                || (e.to() != null && observer.position().distance(e.to()) <= 1)))
            .filter(e -> e.decision() == null || e.regimentId().equals(observer.id()))
            .toList();
    return new Knowledge(contacts, supplies, relays, edges, logs);
  }

  /** Called at the N -> N+1 boundary. Delivery and unit sharing occur in this same boundary. */
  public IntelligenceState advance(Scenario world, int nextDay, Map<String, Knowledge> observed) {
    var network = new CommunicationNetwork(world);
    var queue = new ArrayList<>(pending);
    for (var r : world.regiments()) {
      var k = observed.get(r.id());
      if (k != null) queue.add(new Report(r.id(), r.side(), nextDay - 1, k));
    }
    var shared = new TreeMap<>(divisions);
    var remaining = new ArrayList<Report>();
    for (var report : queue) {
      if (report.observedDay() < nextDay && network.toDivision(report.source()))
        shared.put(report.side(), shared.get(report.side()).merge(report.knowledge()));
      else remaining.add(report);
    }
    var locals = new TreeMap<>(regiments);
    var brigade = new TreeMap<>(brigades);
    for (var r : world.regiments()) {
      var k =
          locals
              .getOrDefault(r.id(), Knowledge.empty())
              .merge(observed.getOrDefault(r.id(), Knowledge.empty()));
      if (network.toDivision(r.id())) k = k.merge(shared.get(r.side()));
      locals.put(r.id(), k);
      if (r.role() == Role.BRIGADE_HQ) brigade.put(r.id(), k);
    }
    // Dead reporters cannot reconnect. Their observations are retained in their local knowledge
    // only.
    remaining.removeIf(r -> world.regiments().stream().noneMatch(u -> u.id().equals(r.source())));
    return new IntelligenceState(locals, brigade, shared, remaining);
  }

  public Map<String, RegimentMemory> shareSupplies(Map<String, RegimentMemory> memory) {
    var result = new TreeMap<String, RegimentMemory>();
    memory.forEach(
        (id, m) -> {
          var supplies = new TreeMap<String, RegimentMemory.KnownSupply>();
          m.supplies().forEach(s -> supplies.put(s.id(), s));
          regiments
              .getOrDefault(id, Knowledge.empty())
              .supplies()
              .forEach(
                  (key, s) ->
                      supplies.merge(key, s, (a, b) -> a.observedDay() > b.observedDay() ? a : b));
          result.put(id, new RegimentMemory(m.doctrine(), new ArrayList<>(supplies.values())));
        });
    return result;
  }
}
