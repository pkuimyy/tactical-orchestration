package io.tactical.application;

import io.tactical.core.*;
import io.tactical.core.Scenario.Side;
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

  public record Saved(
      int schemaVersion,
      String rulesVersion,
      List<Draft> drafts,
      List<Revision> revisions,
      List<Game> games,
      Map<String, BattleSession.Saved> battles,
      List<EditEvent> events,
      long sequence,
      Set<String> archived) {}

  private java.util.function.Consumer<Saved> persistence = ignored -> {};
  private Saved checkpoint;

  public synchronized Saved save() {
    var savedBattles = new TreeMap<String, BattleSession.Saved>();
    battles.forEach(
        (id, battle) -> savedBattles.put(id, battle.save(games.get(id).initialState())));
    return new Saved(
        1,
        RuleSet.VERSION,
        List.copyOf(drafts.values()),
        List.copyOf(revisions.values()),
        List.copyOf(games.values()),
        Collections.unmodifiableMap(savedBattles),
        List.copyOf(events),
        sequence,
        Set.copyOf(archived));
  }

  public synchronized void restore(Saved saved) {
    if (saved.schemaVersion() != 1 || !RuleSet.VERSION.equals(saved.rulesVersion()))
      throw new IllegalStateException("本地存档格式或规则版本不兼容");
    var restored = new HashMap<String, BattleSession>();
    saved.battles().forEach((id, battle) -> restored.put(id, BattleSession.restore(battle)));
    drafts.clear();
    saved.drafts().forEach(d -> drafts.put(d.id(), d));
    revisions.clear();
    saved.revisions().forEach(r -> revisions.put(r.id(), r));
    games.clear();
    saved.games().forEach(g -> games.put(g.id(), g));
    battles.clear();
    battles.putAll(restored);
    events.clear();
    events.addAll(saved.events());
    sequence = saved.sequence();
    archived.clear();
    archived.addAll(saved.archived());
  }

  public synchronized void persistWith(java.util.function.Consumer<Saved> writer) {
    persistence = writer;
    checkpoint = save();
  }

  private void changed() {
    if (checkpoint == null) return;
    var next = save();
    try {
      persistence.accept(next);
      checkpoint = next;
    } catch (RuntimeException failure) {
      restore(checkpoint);
      throw failure;
    }
  }

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
    changed();
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
    changed();
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
            s.supplies(),
            s.communicationNodes(),
            s.communicationRadius(),
            s.setup(),
            s.initialKnowledge()));
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
            s.supplies(),
            s.communicationNodes(),
            s.communicationRadius(),
            s.setup(),
            s.initialKnowledge()));
  }

  public synchronized Draft archive(String id, long expectedVersion, boolean value) {
    Draft old = get(id);
    version(old, expectedVersion);
    if (value) archived.add(id);
    else archived.remove(id);
    Draft next = new Draft(id, old.version() + 1, old.scenario());
    drafts.put(id, next);
    event(value ? "DRAFT_ARCHIVED" : "DRAFT_RESTORED", id, id, ScenarioHash.sha256(old.scenario()));
    changed();
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
    changed();
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
    changed();
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
    changed();
    return game;
  }

  /** Stable ID makes opening an experiment result safe to retry. */
  public synchronized Game importRun(
      String experimentId, Revision revision, ExperimentRunner.Run run, int iterations) {
    if (!run.status().equals("COMPLETED")) throw new ScenarioViolation("只有完整运行可以打开为战局");
    String id =
        UUID.nameUUIDFromBytes(
                (experimentId + ":" + run.index())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    if (games.containsKey(id)) return game(id);
    revision(revision.id());
    limit(games.size(), 128, "实验实例");
    var empty = new BattleSession.Batch(0, false, false, List.of(), null);
    var battle =
        BattleSession.restore(
            new BattleSession.Saved(
                revision.scenario(), run.seed(), iterations, run.days(), empty, empty));
    var game =
        new Game(
            id,
            revision.id(),
            revision.contentHash(),
            RuleSet.VERSION,
            run.seed(),
            0,
            "PLANNING",
            revision.scenario());
    games.put(id, game);
    battles.put(id, battle);
    event("EXPERIMENT_OPENED", revision.scenarioId(), id, revision.contentHash());
    changed();
    return game(id);
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

  public synchronized BattleSession.Replay replay(
      String id, int day, int frame, BattleSession.Perspective perspective, Side side) {
    game(id);
    return battles.get(id).replay(day, frame, perspective, side);
  }

  public synchronized Draft blueprint(
      String id, int expectedDay, long blueVersion, long redVersion) {
    var game = game(id);
    var turn = battles.get(id).view();
    if (expectedDay != 1
        || turn.day() != 1
        || turn.blue().version() != blueVersion
        || turn.red().version() != redVersion)
      throw new StoreProblem(StoreProblem.Kind.CONFLICT, "另存初始实验方案需要第 1 天当前版本；请先提交双方命令");
    if (!turn.blue().submitted() || !turn.red().submitted())
      throw new StoreProblem(StoreProblem.Kind.CONFLICT, "请先提交双方命令后另存方案");
    var s = game.initialState();
    return importScenario(
        new Scenario(
            s.schemaVersion(),
            s.name(),
            s.width(),
            s.height(),
            s.cells(),
            s.edges(),
            s.regiments(),
            s.supplies(),
            s.communicationNodes(),
            s.communicationRadius(),
            new ExperimentSetup(
                turn.blue().orders(),
                turn.red().orders(),
                turn.blue().operation(),
                turn.red().operation()),
            s.initialKnowledge()));
  }

  public synchronized BattleSession.PlayerView projection(
      String id, BattleSession.Perspective perspective, Side side) {
    game(id);
    return battles.get(id).projection(perspective, side);
  }

  public synchronized BattleSession.View submit(
      String id,
      int day,
      Side side,
      long expectedVersion,
      List<MovementOrder> orders,
      OperationPlan operation) {
    game(id);
    var result = battles.get(id).submit(day, side, expectedVersion, orders, operation);
    changed();
    return result;
  }

  public synchronized BattleSession.View submit(
      String id, int day, Scenario.Side side, long expectedVersion, List<MovementOrder> orders) {
    game(id);
    var result = battles.get(id).submit(day, side, expectedVersion, orders);
    changed();
    return result;
  }

  public synchronized BattleSession.View commit(
      String id, int day, Scenario.Side side, long expectedVersion) {
    game(id);
    var result = battles.get(id).commit(day, side, expectedVersion);
    changed();
    return result;
  }

  public synchronized BattleSession.Day resolve(String id, int day) {
    game(id);
    var result = battles.get(id).resolve(day);
    changed();
    return result;
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
