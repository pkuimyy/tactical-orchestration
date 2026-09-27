package io.tactical.server;

import io.swagger.v3.oas.annotations.Operation;
import io.tactical.application.*;
import io.tactical.application.ExperimentRunner.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/experiments")
final class ExperimentController {
  private final ExperimentService service;

  ExperimentController(ExperimentService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      summary =
          "Queue paired A/B runs from one frozen revision; requestId makes retries idempotent")
  ExperimentService.Summary create(@RequestBody Request request) {
    return service.create(request);
  }

  @GetMapping
  java.util.List<ExperimentService.Summary> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  ExperimentService.Summary get(@PathVariable String id) {
    return service.status(id);
  }

  @PostMapping("/{id}/cancel")
  ExperimentService.Summary cancel(@PathVariable String id) {
    return service.cancel(id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id) {
    service.delete(id);
  }

  @GetMapping("/{id}/export")
  ExperimentService.Archive export(@PathVariable String id) {
    return service.get(id);
  }

  @GetMapping("/{id}/runs/{index}")
  Run run(@PathVariable String id, @PathVariable int index) {
    return service.run(id, index);
  }

  @PostMapping("/{id}/runs/{index}/game")
  @Operation(
      summary = "Verify recorded days and open an independently inspectable game; safe to retry")
  ScenarioService.Game open(@PathVariable String id, @PathVariable int index) {
    return service.open(id, index);
  }

  @GetMapping(value = "/{id}/runs/{index}/events", produces = "application/x-ndjson")
  String events(@PathVariable String id, @PathVariable int index) {
    var mapper = new ObjectMapper();
    return service.run(id, index).days().stream()
        .flatMap(d -> d.result().events().stream())
        .map(mapper::writeValueAsString)
        .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
  }
}
