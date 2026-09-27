package io.tactical.core;

public record HexCoord(int q, int r) implements Comparable<HexCoord> {
  public int distance(HexCoord other) {
    long dq = (long) q - other.q, dr = (long) r - other.r;
    return (int)
        Math.min(
            Integer.MAX_VALUE, Math.max(Math.abs(dq), Math.max(Math.abs(dr), Math.abs(dq + dr))));
  }

  @Override
  public int compareTo(HexCoord other) {
    int comparison = Integer.compare(q, other.q);
    return comparison == 0 ? Integer.compare(r, other.r) : comparison;
  }
}
