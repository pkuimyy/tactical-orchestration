package io.tactical.server;

import io.swagger.v3.oas.annotations.Operation;
import io.tactical.application.ScenarioService;
import io.tactical.application.ScenarioService.*;
import io.tactical.application.StoreProblem;
import io.tactical.core.Scenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
final class ScenarioController {
  private final ScenarioService service = new ScenarioService();

  @GetMapping("/scenarios")
  List<DraftSummary> list() {
    return service.list();
  }

  @PostMapping("/scenarios")
  @ResponseStatus(HttpStatus.CREATED)
  Draft create(@Valid @RequestBody CreateScenario request) {
    return service.create(request.name(), request.width(), request.height());
  }

  @PostMapping("/scenarios/import")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Import JSON content as a new draft; never reads a user-supplied filesystem path")
  Draft importScenario(@RequestBody Scenario scenario) {
    return service.importScenario(scenario);
  }

  @GetMapping("/scenarios/{id}")
  Draft get(@PathVariable String id) {
    return service.get(id);
  }

  @PutMapping("/scenarios/{id}")
  Draft update(@PathVariable String id, @Valid @RequestBody UpdateScenario request) {
    return service.update(id, request.expectedVersion(), request.scenario());
  }

  @PatchMapping("/scenarios/{id}/name")
  Draft rename(@PathVariable String id, @Valid @RequestBody Rename request) {
    return service.rename(id, request.expectedVersion(), request.name());
  }

  @PostMapping("/scenarios/{id}/copy")
  @ResponseStatus(HttpStatus.CREATED)
  Draft copy(@PathVariable String id, @Valid @RequestBody Version request) {
    return service.copy(id, request.expectedVersion());
  }

  @PostMapping("/scenarios/{id}/archive")
  Draft archive(@PathVariable String id, @Valid @RequestBody Archive request) {
    return service.archive(id, request.expectedVersion(), request.archived());
  }

  @DeleteMapping("/scenarios/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestParam @Min(1) long expectedVersion) {
    service.delete(id, expectedVersion);
  }

  @GetMapping("/scenarios/{id}/export")
  Scenario export(@PathVariable String id) {
    return service.get(id).scenario();
  }

  @PostMapping("/scenarios/{id}/revisions")
  Revision freeze(@PathVariable String id, @Valid @RequestBody Version request) {
    return service.freeze(id, request.expectedVersion());
  }

  @GetMapping("/scenarios/{id}/revisions")
  List<Revision> revisions(@PathVariable String id) {
    return service.revisions(id);
  }

  @GetMapping("/scenarios/{id}/events")
  List<EditEvent> events(@PathVariable String id) {
    return service.events(id);
  }

  @GetMapping("/revisions/{id}")
  Revision revision(@PathVariable String id) {
    return service.revision(id);
  }

  @GetMapping("/revisions/{id}/export")
  Scenario exportRevision(@PathVariable String id) {
    return service.revision(id).scenario();
  }

  @PutMapping("/revisions/{id}")
  @Operation(summary = "Reject edits to immutable frozen input", hidden = true)
  void rejectRevisionEdit(@PathVariable String id) {
    service.revision(id);
    throw new StoreProblem(StoreProblem.Kind.CONFLICT, "冻结版本不可修改，请编辑原草稿后重新冻结");
  }

  @PostMapping("/games")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary =
          "Create an independent READY instance from frozen input; M1 does not simulate turns")
  Game createGame(@Valid @RequestBody CreateGame request) {
    return service.createGame(request.revisionId(), request.seed());
  }

  @GetMapping("/games/{id}")
  Game game(@PathVariable String id) {
    return service.game(id);
  }

  @GetMapping("/revisions/{id}/games")
  List<Game> games(@PathVariable String id) {
    return service.games(id);
  }

  @GetMapping("/presets/river-valley")
  Scenario preset() throws IOException {
    return new ObjectMapper()
        .readValue(
            new ClassPathResource("scenarios/river-valley.json").getContentAsByteArray(),
            Scenario.class);
  }

  record Rename(@Min(1) long expectedVersion, @NotBlank @Size(max = 80) String name) {}

  record Archive(@Min(1) long expectedVersion, boolean archived) {}

  record CreateScenario(
      @NotBlank @Size(max = 80) String name,
      @Min(2) @Max(16) int width,
      @Min(2) @Max(16) int height) {}

  record UpdateScenario(@Min(1) long expectedVersion, @NotNull Scenario scenario) {}

  record Version(@Min(1) long expectedVersion) {}

  record CreateGame(@NotBlank @Size(max = 64) String revisionId, long seed) {}
}
