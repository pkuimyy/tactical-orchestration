package io.tactical.simulation;

import static io.tactical.core.Scenario.*;

import java.util.*;

/** Integer deterministic experimental table. Organization is applied once to firepower. */
public final class CombatRules {
  private CombatRules() {}

  public record Profile(int soft, int antiArmor, int protection, int range, int suggestedHp) {}

  public record Hit(
      String sourceRegimentId,
      String sourceCompanyId,
      String targetCompanyId,
      int rawPower,
      int protection,
      String damageType,
      int damage) {}

  public static Profile profile(Company c) {
    return switch (c.type()) {
      case INFANTRY -> new Profile(22, 4, c.equipment() == Equipment.MECHANIZED ? 10 : 0, 1, 160);
      case ARMOR -> new Profile(34, 30, 55, 1, 100);
      case ANTI_TANK -> new Profile(10, 48, 5, 1, 100);
      case ARTILLERY -> new Profile(32, 18, 0, 3, 100);
      case ENGINEER -> new Profile(16, 6, 0, 1, 120);
      case RECON -> new Profile(14, 8, 10, 1, 80);
      case SIGNAL -> new Profile(4, 1, 0, 1, 80);
    };
  }

  public static int organization(Company c) {
    return (int) (1000L * c.hp() / c.maxHp());
  }

  public static int organization(Regiment r) {
    return r.companies().stream().mapToInt(CombatRules::organization).sum() / r.companies().size();
  }

  public static List<Hit> volley(
      Regiment source,
      Regiment target,
      int distance,
      boolean indirect,
      boolean targetPrepared,
      Terrain terrain,
      int fortification) {
    List<Hit> hits = new ArrayList<>();
    List<Company> defenders =
        target.companies().stream()
            .filter(c -> c.hp() > 0)
            .sorted(Comparator.comparing(Company::id))
            .toList();
    if (defenders.isEmpty()) return hits;
    Map<String, Integer> assigned = new HashMap<>();
    for (var c : source.companies().stream().sorted(Comparator.comparing(Company::id)).toList()) {
      var p = profile(c);
      if (c.hp() == 0 || distance > p.range() || indirect && c.type() != CompanyType.ARTILLERY)
        continue;
      boolean antiArmor = p.antiArmor() > p.soft();
      var preferred =
          defenders.stream().filter(d -> (d.type() == CompanyType.ARMOR) == antiArmor).toList();
      var pool = preferred.isEmpty() ? defenders : preferred;
      var victim =
          pool.stream()
              .min(
                  Comparator.comparingInt((Company d) -> assigned.getOrDefault(d.id(), 0))
                      .thenComparing(Company::id))
              .orElseThrow();
      assigned.merge(victim.id(), 1, Integer::sum);
      boolean armored = victim.type() == CompanyType.ARMOR;
      int raw = (armored ? p.antiArmor() : p.soft()) * organization(c) / 1000;
      int protection = profile(victim).protection();
      int terrainCover =
          switch (terrain) {
            case FOREST, HILL -> 10;
            case MOUNTAIN, CITY -> 15;
            default -> 0;
          };
      int cover = Math.min(50, terrainCover + fortification * 5 + (targetPrepared ? 15 : 0));
      int damage = raw * (100 - protection) * (100 - cover) / 10000;
      if (damage > 0)
        hits.add(
            new Hit(
                source.id(),
                c.id(),
                victim.id(),
                raw,
                protection,
                armored ? "ANTI_ARMOR" : indirect ? "HIGH_EXPLOSIVE" : "DIRECT",
                damage));
    }
    return List.copyOf(hits);
  }
}
