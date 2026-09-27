package io.tactical.simulation;

import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import java.util.*;

/** Terminals can receive but cannot relay. Only living signal companies / facilities relay. */
public final class CommunicationNetwork {
  public record Node(String id, HexCoord position, Side side, boolean relay) {}

  public record Link(String a, String b) {}

  private final Map<String, Node> nodes = new TreeMap<>();
  private final List<Link> links = new ArrayList<>();
  private final Scenario world;

  public CommunicationNetwork(Scenario world) {
    this.world = world;
    for (var r : world.regiments())
      nodes.put(r.id(), new Node(r.id(), r.position(), r.side(), signals(r) > 0));
    for (var n : world.communicationNodes())
      if (n.hp() > 0) nodes.put(n.id(), new Node(n.id(), n.position(), n.side(), true));
    for (var a : nodes.values())
      for (var b : nodes.values())
        if (a.id().compareTo(b.id()) < 0
            && a.side() == b.side()
            && (a.relay() || b.relay())
            && a.position().distance(b.position()) <= world.communicationRadius())
          links.add(new Link(a.id(), b.id()));
  }

  public static long signals(Regiment r) {
    return r.companies().stream().filter(c -> c.hp() > 0 && c.type() == CompanyType.SIGNAL).count();
  }

  public List<Node> nodes() {
    return List.copyOf(nodes.values());
  }

  public List<Link> links() {
    return List.copyOf(links);
  }

  public boolean connected(String from, String to) {
    if (!nodes.containsKey(from) || !nodes.containsKey(to)) return false;
    var seen = new HashSet<String>();
    var queue = new ArrayDeque<String>();
    queue.add(from);
    seen.add(from);
    while (!queue.isEmpty()) {
      var id = queue.remove();
      if (id.equals(to)) return true;
      if (!id.equals(from) && !nodes.get(id).relay()) continue;
      for (var link : links) {
        String next = link.a().equals(id) ? link.b() : link.b().equals(id) ? link.a() : null;
        if (next != null && seen.add(next)) queue.add(next);
      }
    }
    return false;
  }

  public boolean toDivision(String id) {
    var unit = nodes.get(id);
    return unit != null
        && world.regiments().stream()
            .anyMatch(
                r ->
                    r.side() == unit.side()
                        && r.role() == Role.DIVISION_HQ
                        && signals(r) > 0
                        && connected(id, r.id()));
  }
}
