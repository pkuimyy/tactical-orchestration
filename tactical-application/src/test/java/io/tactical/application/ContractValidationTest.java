package io.tactical.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ContractValidationTest {
  @Test
  void rejectsUnsupportedVersionsBeforeProcessing() {
    var validation = new ContractValidation();
    assertThrows(
        IllegalArgumentException.class,
        () -> validation.validate(2, ContractValidation.Kind.COMMAND, "id"));
    assertThrows(
        IllegalArgumentException.class,
        () -> validation.validate(1, ContractValidation.Kind.COMMAND, "../bad"));
    assertTrue(validation.validate(1, ContractValidation.Kind.COMMAND, "id").valid());
  }
}
