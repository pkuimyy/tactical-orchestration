package io.tactical.core;

import java.util.List;

/** Immutable scenario input. Construction copies every collection, including nested companies. */
public record Scenario(
    int schemaVersion,
    String name,
    int width,
    int height,
    List<Cell> cells,
    List<Edge> edges,
    List<Regiment> regiments,
    List<Supply> supplies) {
  public Scenario {
    cells = copy(cells, "cells");
    edges = copy(edges, "edges");
    regiments = copy(regiments, "regiments");
    supplies = copy(supplies, "supplies");
  }

  private static <T> List<T> copy(List<T> items, String field) {
    if (items == null || items.stream().anyMatch(java.util.Objects::isNull))
      throw new ScenarioViolation(field + " 必须是非 null 数组，且不能包含 null");
    return List.copyOf(items);
  }

  public enum Terrain {
    PLAIN,
    FOREST,
    HILL,
    MOUNTAIN,
    CITY
  }

  public enum Side {
    BLUE,
    RED
  }

  public enum Role {
    DIVISION_HQ,
    BRIGADE_HQ,
    REGIMENT
  }

  public enum CompanyType {
    INFANTRY,
    ARMOR,
    ANTI_TANK,
    ARTILLERY,
    ENGINEER,
    RECON,
    SIGNAL
  }

  public enum Equipment {
    FOOT,
    MOTORIZED,
    MECHANIZED,
    TRACKED,
    TOWED
  }

  public enum Bridge {
    NONE,
    INTACT,
    DESTROYED
  }

  public record Cell(HexCoord position, Terrain terrain, int fortification) {}

  public record Edge(HexCoord a, HexCoord b, boolean river, Bridge bridge, boolean road) {}

  public record Company(String id, CompanyType type, Equipment equipment, int maxHp, int hp) {}

  public record Regiment(
      String id,
      String name,
      Side side,
      Role role,
      HexCoord position,
      String brigadeId,
      List<Company> companies) {
    public Regiment {
      companies = copy(companies, "companies");
    }
  }

  public record Supply(String id, HexCoord position, Side side, int stock) {}
}
