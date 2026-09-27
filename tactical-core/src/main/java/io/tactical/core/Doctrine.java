package io.tactical.core;

/** Reusable versioned doctrine parameters; decisions consume local knowledge only. */
public record Doctrine(
    int schemaVersion, Template template, int withdrawBelowPercent, String supplyId) {
  public enum Template {
    ADVANCE,
    BREAKTHROUGH,
    HOLD
  }

  public Doctrine {
    if (schemaVersion != 1
        || template == null
        || withdrawBelowPercent < 0
        || withdrawBelowPercent > 100
        || supplyId != null && !supplyId.isEmpty() && !supplyId.matches("[A-Za-z0-9_-]{1,64}"))
      throw new ScenarioViolation("学说版本须为 1，撤退阈值为 0–100，补给目标须为合法 ID");
    supplyId = supplyId == null ? "" : supplyId;
  }

  public static Doctrine standard(Scenario.Regiment r) {
    return new Doctrine(
        1,
        r.companies().stream().anyMatch(c -> c.type() == Scenario.CompanyType.ARMOR && c.hp() > 0)
            ? Template.BREAKTHROUGH
            : Template.ADVANCE,
        35,
        "");
  }
}
