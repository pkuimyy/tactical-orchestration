package io.tactical.server;

import io.tactical.application.*;
import io.tactical.application.ExperimentRunner.*;
import io.tactical.core.ScenarioViolation;
import io.tactical.simulation.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
final class ExperimentService {
  record Archive(
      int schemaVersion,
      String id,
      String rulesVersion,
      String randomVersion,
      ScenarioService.Revision revision,
      Request request,
      String status,
      List<Run> runs,
      String error) {
    Archive {
      runs = List.copyOf(runs);
    }
  }

  record Row(
      int index,
      String variant,
      long seed,
      String status,
      String goalStatus,
      Integer completionDay,
      int blueDamage,
      int redDamage,
      long waits,
      long ineffective,
      long dependencyDisorders,
      long taskFailures,
      List<String> retreats) {}

  record Paired(
      int count,
      double blueDamageDelta,
      double redDamageDelta,
      double waitDelta,
      double disorderDelta,
      long goalsA,
      long goalsB) {}

  record Summary(
      String id,
      String revisionId,
      String scenarioName,
      String scenarioHash,
      String rulesVersion,
      String status,
      int completed,
      int total,
      String nameA,
      String nameB,
      List<Long> seeds,
      List<Row> rows,
      Paired paired,
      String error) {}

  private final ScenarioService scenarios;
  private final LocalArchive storage;
  private final Map<String, Archive> jobs = new LinkedHashMap<>();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          r -> {
            var thread = new Thread(r, "paired-experiments");
            thread.setDaemon(true);
            return thread;
          });

  ExperimentService(ScenarioService scenarios, LocalArchive storage) {
    this.scenarios = scenarios;
    this.storage = storage;
    for (String name : storage.experiments()) {
      var saved = storage.read(name, Archive.class);
      if (saved.schemaVersion() != 1
          || !saved.rulesVersion().equals(RuleSet.VERSION)
          || !saved.randomVersion().equals(DaySimulation.RANDOM_VERSION))
        throw new IllegalStateException("实验存档版本不兼容");
      jobs.put(saved.id(), saved);
    }
    for (var job : jobs.values()) if (active(job)) worker.submit(() -> execute(job.id()));
  }

  synchronized Summary create(Request request) {
    for (var job : jobs.values())
      if (job.request().requestId().equals(request.requestId())) {
        if (!job.request().equals(request))
          throw new StoreProblem(StoreProblem.Kind.CONFLICT, "请求 ID 已用于另一组实验参数");
        return summary(job);
      }
    if (jobs.size() >= 16
        || worker.getQueue().remainingCapacity() == 0
        || jobs.values().stream().filter(ExperimentService::active).count() >= 2)
      throw new StoreProblem(StoreProblem.Kind.LIMIT, "最多保存 16 组实验，同时运行或排队最多 2 组");
    var revision = scenarios.revision(request.revisionId());
    ExperimentRunner.validate(revision.scenario(), request);
    String id = UUID.randomUUID().toString();
    var job =
        new Archive(
            1,
            id,
            RuleSet.VERSION,
            DaySimulation.RANDOM_VERSION,
            revision,
            request,
            "QUEUED",
            List.of(),
            "");
    put(job);
    worker.submit(() -> execute(id));
    return summary(job);
  }

  synchronized List<Summary> list() {
    return jobs.values().stream().map(ExperimentService::summary).toList();
  }

  synchronized Archive get(String id) {
    var job = jobs.get(id);
    if (job == null) throw new StoreProblem(StoreProblem.Kind.NOT_FOUND, "实验不存在");
    return job;
  }

  synchronized Summary status(String id) {
    return summary(get(id));
  }

  synchronized Summary cancel(String id) {
    var job = get(id);
    if (active(job)) {
      job = change(job, "CANCELLED", job.runs(), "已停止；保留完成的运行");
      put(job);
    }
    return summary(job);
  }

  synchronized void delete(String id) {
    var job = get(id);
    if (active(job)) throw new StoreProblem(StoreProblem.Kind.CONFLICT, "请先停止实验再删除归档");
    storage.deleteExperiment(id);
    jobs.remove(id);
  }

  synchronized Run run(String id, int index) {
    var job = get(id);
    if (index < 0 || index >= job.request().seeds().size() * 2)
      throw new ScenarioViolation("运行序号超出范围");
    return job.runs().stream()
        .filter(r -> r.index() == index)
        .findFirst()
        .orElseThrow(() -> new StoreProblem(StoreProblem.Kind.CONFLICT, "该次运行尚未完成"));
  }

  ScenarioService.Game open(String id, int index) {
    var job = get(id);
    return scenarios.importRun(id, job.revision(), run(id, index), job.request().maxIterations());
  }

  private void execute(String id) {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Archive job;
        synchronized (this) {
          job = jobs.get(id);
          if (job == null || !active(job)) return;
          if (!job.status().equals("RUNNING")) {
            job = change(job, "RUNNING", job.runs(), "");
            put(job);
          }
        }
        int index = job.runs().size();
        if (index >= job.request().seeds().size() * 2) return;
        var result =
            ExperimentRunner.run(
                job.revision().scenario(), job.request(), index, () -> !isActive(id));
        synchronized (this) {
          job = jobs.get(id);
          if (job == null || !active(job)) return;
          var results = new ArrayList<>(job.runs());
          results.add(result);
          put(
              change(
                  job,
                  results.size() == job.request().seeds().size() * 2 ? "COMPLETED" : "RUNNING",
                  results,
                  ""));
        }
      }
    } catch (CancellationException stopped) {
      // Shutdown leaves a resumable checkpoint; explicit cancellation remains terminal.
    } catch (RuntimeException failure) {
      System.getLogger(ExperimentService.class.getName())
          .log(System.Logger.Level.ERROR, "实验执行或归档失败：" + id, failure);
      synchronized (this) {
        var job = jobs.get(id);
        if (job != null && active(job)) {
          var failed = change(job, "FAILED", job.runs(), "实验执行或归档失败；已完成结果保留，请检查本地存储和服务日志");
          try {
            put(failed);
          } catch (RuntimeException diskFailure) {
            jobs.put(id, failed);
          }
        }
      }
    }
  }

  private void put(Archive job) {
    storage.write("experiment-" + job.id(), job);
    jobs.put(job.id(), job);
  }

  private synchronized boolean isActive(String id) {
    return jobs.containsKey(id) && active(jobs.get(id));
  }

  private static boolean active(Archive job) {
    return Set.of("QUEUED", "RUNNING").contains(job.status());
  }

  private static Archive change(Archive old, String status, List<Run> runs, String error) {
    return new Archive(
        old.schemaVersion(),
        old.id(),
        old.rulesVersion(),
        old.randomVersion(),
        old.revision(),
        old.request(),
        status,
        runs,
        error);
  }

  private static Summary summary(Archive job) {
    var rows =
        job.runs().stream()
            .map(
                r -> {
                  var m = r.metrics();
                  return new Row(
                      r.index(),
                      r.variant(),
                      r.seed(),
                      r.status(),
                      m.goalStatus(),
                      m.completionDay(),
                      m.blueDamage(),
                      m.redDamage(),
                      m.waits(),
                      m.ineffective(),
                      m.dependencyDisorders(),
                      m.taskFailures(),
                      m.retreats().stream()
                          .map(t -> "D" + t.day() + " " + t.regimentId() + " → " + t.supplyId())
                          .toList());
                })
            .toList();
    int count = 0;
    double blue = 0, red = 0, waits = 0, disorders = 0;
    long goalsA = 0, goalsB = 0;
    for (int i = 0; i + 1 < rows.size(); i += 2) {
      var a = rows.get(i);
      var b = rows.get(i + 1);
      if (!a.status().equals("COMPLETED") || !b.status().equals("COMPLETED")) continue;
      count++;
      blue += b.blueDamage() - a.blueDamage();
      red += b.redDamage() - a.redDamage();
      waits += b.waits() - a.waits();
      disorders += b.dependencyDisorders() - a.dependencyDisorders();
      if (a.goalStatus().equals("REACHED")) goalsA++;
      if (b.goalStatus().equals("REACHED")) goalsB++;
    }
    var paired =
        new Paired(
            count,
            count == 0 ? 0 : blue / count,
            count == 0 ? 0 : red / count,
            count == 0 ? 0 : waits / count,
            count == 0 ? 0 : disorders / count,
            goalsA,
            goalsB);
    return new Summary(
        job.id(),
        job.revision().id(),
        job.revision().scenario().name(),
        job.revision().contentHash(),
        job.rulesVersion(),
        job.status(),
        rows.size(),
        job.request().seeds().size() * 2,
        job.request().a().name(),
        job.request().b().name(),
        job.request().seeds(),
        rows,
        paired,
        job.error());
  }

  @PreDestroy
  void close() throws InterruptedException {
    worker.shutdownNow();
    worker.awaitTermination(30, TimeUnit.SECONDS);
  }
}
