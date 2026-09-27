package io.tactical.core;

import java.util.*;

/** Authored first-day orders and doctrines; independent of runtime command versions. */
public record ExperimentSetup(
    List<MovementOrder> blue,
    List<MovementOrder> red,
    OperationPlan blueOperation,
    OperationPlan redOperation) {
  public static ExperimentSetup empty() {
    return new ExperimentSetup(List.of(), List.of(), null, null);
  }

  public ExperimentSetup {
    blue = copy(blue);
    red = copy(red);
  }

  private static List<MovementOrder> copy(List<MovementOrder> orders) {
    if (orders == null || orders.size() > 32 || orders.stream().anyMatch(Objects::isNull))
      throw new ScenarioViolation("实验方案每方需要最多 32 条命令");
    return orders.stream().sorted(Comparator.comparing(MovementOrder::regimentId)).toList();
  }

  public void validate(Scenario scenario) {
    OrderRules.validate(scenario, Scenario.Side.BLUE, blue);
    OrderRules.validate(scenario, Scenario.Side.RED, red);
    if (blueOperation != null) blueOperation.validate(scenario, Scenario.Side.BLUE, blue);
    if (redOperation != null) redOperation.validate(scenario, Scenario.Side.RED, red);
  }
}
