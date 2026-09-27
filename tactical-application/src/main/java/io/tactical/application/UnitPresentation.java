package io.tactical.application;

import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import io.tactical.simulation.Mobility;
import java.util.*;

/** Server-derived display metadata; clients do not duplicate mobility rules. */
public final class UnitPresentation {
  private UnitPresentation() {}

  public record Marker(
      CompanyType main, CompanyType bottleneck, Equipment bottleneckEquipment, int speed) {}

  public static Map<String, Marker> markers(Scenario world) {
    var result = new TreeMap<String, Marker>();
    for (var r : world.regiments()) {
      var living = r.companies().stream().filter(c -> c.hp() > 0).toList();
      if (living.isEmpty()) continue;
      var counts = new TreeMap<CompanyType, Integer>();
      living.forEach(c -> counts.merge(c.type(), 1, Integer::sum));
      var main =
          counts.entrySet().stream()
              .sorted(
                  Comparator.<Map.Entry<CompanyType, Integer>>comparingInt(Map.Entry::getValue)
                      .reversed()
                      .thenComparing(e -> e.getKey().name()))
              .findFirst()
              .orElseThrow()
              .getKey();
      var terrain =
          world.cells().stream()
              .filter(c -> c.position().equals(r.position()))
              .findFirst()
              .orElseThrow()
              .terrain();
      var slow =
          living.stream()
              .min(
                  Comparator.<Company>comparingInt(c -> Mobility.speed(c, terrain, false))
                      .thenComparing(Company::id))
              .orElseThrow();
      int speed = Mobility.speed(slow, terrain, false);
      boolean mixed = living.stream().anyMatch(c -> Mobility.speed(c, terrain, false) > speed);
      result.put(
          r.id(),
          new Marker(
              main,
              mixed ? slow.type() : null,
              mixed ? slow.equipment() : null,
              r.role() == Role.DIVISION_HQ ? 0 : speed));
    }
    return Collections.unmodifiableMap(result);
  }
}
