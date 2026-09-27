package io.tactical.simulation;

import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import java.util.*;

/** Supplies are last local observations, never a live query from the doctrine planner. */
public record RegimentMemory(Doctrine doctrine, List<KnownSupply> supplies) {
  public record KnownSupply(String id, HexCoord position, Side side, int stock, int observedDay) {}

  public RegimentMemory {
    supplies = supplies.stream().sorted(Comparator.comparing(KnownSupply::id)).toList();
  }

  public static Map<String, RegimentMemory> initial(Scenario world) {
    Map<String, RegimentMemory> result = new TreeMap<>();
    for (var r : world.regiments())
      result.put(r.id(), new RegimentMemory(Doctrine.standard(r), List.of()).observe(world, r, 0));
    return Collections.unmodifiableMap(result);
  }

  public RegimentMemory observe(Scenario world, Regiment self, int day) {
    Map<String, KnownSupply> known = new TreeMap<>();
    supplies.forEach(s -> known.put(s.id(), s));
    // A fixed local observation radius of one is the M3 knowledge boundary, not M4 communications.
    for (var s : world.supplies())
      if (self.position().distance(s.position()) <= 1)
        known.put(s.id(), new KnownSupply(s.id(), s.position(), s.side(), s.stock(), day));
    return new RegimentMemory(doctrine, new ArrayList<>(known.values()));
  }
}
