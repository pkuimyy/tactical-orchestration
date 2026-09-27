package io.tactical.server;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.tactical.application.ContractValidation;
import io.tactical.application.ContractValidation.Kind;
import io.tactical.core.ContractVersion;
import io.tactical.simulation.RuleSet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
final class ApiController {
  private final ContractValidation validation = new ContractValidation();

  @GetMapping("/health")
  @SecurityRequirements
  Health health() {
    return new Health("UP");
  }

  @GetMapping("/api/v1/system")
  SystemInfo system() {
    return new SystemInfo(ContractVersion.CURRENT, "M5", RuleSet.VERSION);
  }

  @PostMapping(value = "/api/v1/contracts/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Validate envelope metadata without creating or executing game state")
  ContractValidation.Receipt validate(@Valid @RequestBody Envelope input) {
    return validation.validate(input.schemaVersion(), input.kind(), input.id());
  }

  record Health(String status) {}

  record SystemInfo(int schemaVersion, String milestone, String rulesVersion) {}

  record Envelope(
      @NotNull @Min(ContractVersion.CURRENT) @Max(ContractVersion.CURRENT) Integer schemaVersion,
      @NotNull Kind kind,
      @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id) {}
}
