package io.tactical.core;

import java.io.*;
import java.security.*;
import java.util.HexFormat;

/**
 * Canonical binary encoding v1; independent of JSON formatting, list order and edge orientation.
 */
public final class ScenarioHash {
  private ScenarioHash() {}

  public static String sha256(Scenario input) {
    Scenario s = ScenarioRules.normalize(input);
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeUTF("tactical-scenario-canonical-v1");
      out.writeInt(s.schemaVersion());
      out.writeUTF(s.name());
      out.writeInt(s.width());
      out.writeInt(s.height());
      out.writeInt(s.cells().size());
      for (var c : s.cells()) {
        coord(out, c.position());
        out.writeUTF(c.terrain().name());
        out.writeInt(c.fortification());
      }
      out.writeInt(s.edges().size());
      for (var e : s.edges()) {
        coord(out, e.a());
        coord(out, e.b());
        out.writeBoolean(e.river());
        out.writeUTF(e.bridge().name());
        out.writeBoolean(e.road());
      }
      out.writeInt(s.regiments().size());
      for (var r : s.regiments()) {
        out.writeUTF(r.id());
        out.writeUTF(r.name());
        out.writeUTF(r.side().name());
        out.writeUTF(r.role().name());
        coord(out, r.position());
        out.writeUTF(r.brigadeId());
        out.writeInt(r.companies().size());
        for (var c : r.companies()) {
          out.writeUTF(c.id());
          out.writeUTF(c.type().name());
          out.writeUTF(c.equipment().name());
          out.writeInt(c.maxHp());
          out.writeInt(c.hp());
        }
      }
      out.writeInt(s.supplies().size());
      for (var supply : s.supplies()) {
        out.writeUTF(supply.id());
        coord(out, supply.position());
        out.writeUTF(supply.side().name());
        out.writeInt(supply.stock());
      }
      out.flush();
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void coord(DataOutputStream out, HexCoord c) throws IOException {
    out.writeInt(c.q());
    out.writeInt(c.r());
  }
}
