package io.tactical.application;

import io.tactical.core.*;
import io.tactical.simulation.RuleSet;
import java.util.*;

/**
 * In-memory scenario and game repository: immutable values, atomic version checks, bounded resource
 * use.
 */
public final class ScenarioService {
  private final Map<String, Draft> drafts = new LinkedHashMap<>();
  private final Map<String, Revision> revisions = new LinkedHashMap<>();
  private final Map<String, BattleSession> battles = new HashMap<>();
  private final Map<String, Game> games = new LinkedHashMap<>();
  private final Deque<EditEvent> events = new ArrayDeque<>();
  private long sequence;
  private final Set<String> archived = new HashSet<>();

  public record Draft(String id, long version, Scenario scenario) {}

  public record DraftSummary(
      String id,
      long version,
      String name,
      int width,
      int height,
      boolean archived,
      long revisions,
      long games) {}

  public record Revision(
      String id,
      String scenarioId,
      long draftVersion,
      String contentHash,
      String hashVersion,
      Scenario scenario) {}

  public record Game(
      String id,
      String revisionId,
      String contentHash,
      String rulesVersion,
      long seed,
      int day,
      String status,
      Scenario initialState) {}

  public record EditEvent(
      long sequence, String kind, String scenarioId, String entityId, String contentHash) {}

  public synchronized List<DraftSummary> list() {
    return drafts.values().stream()
        .map(
            d ->
                new DraftSummary(
                    d.id(),
                    d.version(),
                    d.scenario().name(),
                    d.scenario().width(),
                    d.scenario().height(),
                    archived.contains(d.id()),
                    revisions.values().stream().filter(r -> r.scenarioId().equals(d.id())).count(),
                    games.values().stream()
                        .filter(g -> revision(g.revisionId()).scenarioId().equals(d.id()))
                        .count()))
        .toList();
  }

  public synchronized Draft create(String name, int width, int height) {
    return importScenario(ScenarioRules.blank(name, width, height));
  }

  public synchronized Draft importScenario(Scenario scenario) {
    Scenario normalized = ScenarioRules.normalize(scenario);
    limit(drafts.size(), 32, "草稿");
    Draft draft = new Draft(UUID.randomUUID().toString(), 1, normalized);
    drafts.put(draft.id(), draft);
    event("DRAFT_CREATED", draft.id(), draft.id(), ScenarioHash.sha256(normalized));
    return draft;
  }

  public synchronized Draft get(String id) {
    return require(drafts, id, "场景草稿");
  }

  public synchronized Draft update(String id, long expectedVersion, Scenario scenario) {
    Draft old = get(id);
    version(old, expectedVersion);
    Scenario normalized = ScenarioRules.normalize(scenario);
    Draft draft = new Draft(id, old.version() + 1, normalized);
    drafts.put(id, draft);
    event("DRAFT_UPDATED", id, id, ScenarioHash.sha256(normalized));
    return draft;
  }

  public synchronized Draft rename(String id, long expectedVersion, String name) {
    Draft old = get(id);
    version(old, expectedVersion);
    Scenario s = old.scenario();
    return update(
        id,
        expectedVersion,
        new Scenario(
            s.schemaVersion(),
            name,
            s.width(),
            s.height(),
            s.cells(),
            s.edges(),
            s.regiments(),
            s.supplies()));
  }

  public synchronized Draft copy(String id, long expectedVersion) {
    Draft old = get(id);
    version(old, expectedVersion);
    Scenario s = old.scenario();
    String name = s.name().substring(0, Math.min(s.name().length(), 74)) + " · 副本";
    return importScenario(
        new Scenario(
            s.schemaVersion(),
            name,
            s.width(),
            s.height(),
            s.cells(),
            s.edges(),
            s.regiments(),
            s.supplies()));
  }

  public synchronized Draft archive(String id, long expectedVersion, boolean value) {
    Draft old = get(id);
    version(old, expectedVersion);
    if (value) archived.add(id);
    else archived.remove(id);
    Draft next = new Draft(id, old.version() + 1, old.scenario());
    drafts.put(id, next);
    event(value ? "DRAFT_ARCHIVED" : "DRAFT_RESTORED", id, id, ScenarioHash.sha256(old.scenario()));
    return next;
  }

  public synchronized void delete(String id, long expectedVersion) {
    Draft old = get(id);
    version(old, expectedVersion);
    if (revisions.values().stream().anyMatch(r -> r.scenarioId().equals(id)))
      throw new StoreProblem(StoreProblem.Kind.CONFLICT, "战场已有冻结版本，请使用归档保留实验来源");
    drafts.remove(id);
    archived.remove(id);
    events.removeIf(e -> e.scenarioId().equals(id));
  }

  public synchronized Revision freeze(String id, long expectedVersion) {
    Draft draft = get(id);
    version(draft, expectedVersion);
    ScenarioRules.requirePlayable(draft.scenario());
    String hash = ScenarioHash.sha256(draft.scenario());
    var existing =
        revisions.values().stream()
            .filter(r -> r.scenarioId().equals(id) && r.contentHash().equals(hash))
            .findFirst();
    if (existing.isPresent()) return existing.get();
    limit(revisions.size(), 128, "冻结版本");
    Revision revision =
        new Revision(
            UUID.randomUUID().toString(),
            id,
            draft.version(),
            hash,
            "canonical-v1",
            draft.scenario());
    revisions.put(revision.id(), revision);
    event("SCENARIO_FROZEN", id, revision.id(), hash);
    return revision;
  }

  public synchronized Revision revision(String id) {
    return require(revisions, id, "冻结版本");
  }

  public synchronized List<Revision> revisions(String scenarioId) {
    get(scenarioId);
    return revisions.values().stream().filter(r -> r.scenarioId().equals(scenarioId)).toList();
  }

  public synchronized Game createGame(String revisionId, long seed) {
    return createGame(revisionId, seed, 4);
  }

  public synchronized Game createGame(String revisionId, long seed, int maxIterations) {
    Revision revision = revision(revisionId);
    var battle = new BattleSession(revision.scenario(), seed, maxIterations);
    limit(games.size(), 128, "实验实例");
    Game game =
        new Game(
            UUID.randomUUID().toString(),
            revisionId,
            revision.contentHash(),
            RuleSet.VERSION,
            seed,
            0,
            "PLANNING",
            revision.scenario());
    games.put(game.id(), game);
    battles.put(game.id(), battle);
    event("GAME_CREATED", revision.scenarioId(), game.id(), game.contentHash());
    return game;
  }

  public synchronized Game game(String id) {
    Game original = require(games, id, "实验实例");
    var view = battles.get(id).view();
    return new Game(
        original.id(),
        original.revisionId(),
        original.contentHash(),
        original.rulesVersion(),
        original.seed(),
        view.day() - 1,
        view.status(),
        original.initialState());
  }

  public synchronized List<Game> games(String revisionId) {
    revision(revisionId);
    return games.values().stream()
        .filter(g -> g.revisionId().equals(revisionId))
        .map(g -> game(g.id()))
        .toList();
  }

  public synchronized BattleSession.View turn(String id) {
    game(id);
    return battles.get(id).view();
  }

  public synchronized BattleSession.View submit(
      String id, int day, Scenario.Side side, long expectedVersion, List<MovementOrder> orders) {
    game(id);
    return battles.get(id).submit(day, side, expectedVersion, orders);
  }

  public synchronized BattleSession.View commit(
      String id, int day, Scenario.Side side, long expectedVersion) {
    game(id);
    return battles.get(id).commit(day, side, expectedVersion);
  }

  public synchronized BattleSession.Day resolve(String id, int day) {
    game(id);
    return battles.get(id).resolve(day);
  }

  public synchronized BattleSession.Day day(String id, int day) {
    game(id);
    return battles.get(id).day(day);
  }

  public synchronized List<EditEvent> events(String scenarioId) {
    get(scenarioId);
    return events.stream().filter(e -> e.scenarioId().equals(scenarioId)).toList();
  }

  private void event(String kind, String scenarioId, String entityId, String hash) {
    if (events.size() == 1000) events.removeFirst();
    events.addLast(new EditEvent(++sequence, kind, scenarioId, entityId, hash));
  }

  private static void version(Draft draft, long expected) {
    if (draft.version() != expected)
      throw new StoreProblem(StoreProblem.Kind.CONFLICT, "草稿已被更新，请重新载入后再编辑");
  }

  private static void limit(int size, int max, String type) {
    if (size >= max) throw new StoreProblem(StoreProblem.Kind.LIMIT, type + "数量已达到本地上限 " + max);
  }

  private static <T> T require(Map<String, T> map, String id, String kind) {
    T value = map.get(id);
    if (value == null) throw new StoreProblem(StoreProblem.Kind.NOT_FOUND, kind + "不存在");
    return value;
  }
}
