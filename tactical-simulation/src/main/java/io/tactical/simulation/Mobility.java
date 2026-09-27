package io.tactical.simulation;

import static io.tactical.core.Scenario.*;

import io.tactical.core.*;

/** Versioned experimental movement table. Dead companies do not constrain a regiment. */
public final class Mobility {
  private Mobility() {}

  public static int speed(Company company, Terrain terrain, boolean road) {
    int base =
        switch (company.equipment()) {
          case FOOT -> 2;
          case MOTORIZED, MECHANIZED, TRACKED -> company.type() == CompanyType.RECON ? 6 : 5;
          case TOWED -> 3;
        };
    // A road does not make a mountain passable for heavy equipment in this rule version.
    if (terrain == Terrain.MOUNTAIN) return company.equipment() == Equipment.FOOT ? 1 : 0;
    if (road || terrain == Terrain.PLAIN || terrain == Terrain.CITY) return base;
    return switch (terrain) {
      case FOREST ->
          switch (company.equipment()) {
            case FOOT -> 2;
            case MECHANIZED, TRACKED -> Math.min(base, 3);
            case MOTORIZED, TOWED -> 1;
          };
      case HILL -> Math.max(1, base - 1);
      default -> base;
    };
  }

  public static int speed(Scenario map, Regiment regiment, HexCoord from, HexCoord to) {
    if (regiment.role() == Role.DIVISION_HQ) return 0;
    var edge =
        map.edges().stream()
            .filter(
                e ->
                    e.a().equals(from) && e.b().equals(to)
                        || e.a().equals(to) && e.b().equals(from))
            .findFirst();
    if (edge.isPresent() && edge.get().river() && edge.get().bridge() != Bridge.INTACT) return 0;
    var terrain =
        map.cells().stream()
            .filter(c -> c.position().equals(to))
            .findFirst()
            .orElseThrow(() -> new ScenarioViolation("路径超出地图"))
            .terrain();
    boolean road = edge.isPresent() && edge.get().road();
    return regiment.companies().stream()
        .filter(c -> c.hp() > 0)
        .mapToInt(c -> speed(c, terrain, road))
        .min()
        .orElse(0);
  }
}
