package io.tactical.server;

import io.tactical.application.ScenarioService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** One local writer, private files, durable temporary write + atomic replacement. No API paths. */
@Component
final class LocalArchive {
  private final Path root;
  private final ObjectMapper mapper = new ObjectMapper();
  private final FileChannel lockChannel;
  private final FileLock lock;

  LocalArchive(@Value("${tactical.data-dir:.runtime/data}") String directory) throws IOException {
    root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    lockChannel =
        FileChannel.open(
            root.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (OverlappingFileLockException busy) {
      acquired = null;
    }
    if (acquired == null) {
      lockChannel.close();
      throw new IOException("数据目录正在被另一服务使用；请指定独立 tactical.data-dir");
    }
    lock = acquired;
  }

  @Bean
  ScenarioService scenarioService() {
    var service = new ScenarioService();
    var saved = read("scenarios", ScenarioService.Saved.class);
    if (saved != null) service.restore(saved);
    service.persistWith(value -> write("scenarios", value));
    return service;
  }

  <T> T read(String name, Class<T> type) {
    Path path = file(name);
    if (!Files.exists(path)) return null;
    try {
      if (Files.size(path) > 256L * 1024 * 1024) throw new IOException("存档超过 256 MiB");
      return mapper.readValue(Files.readAllBytes(path), type);
    } catch (IOException | RuntimeException error) {
      throw new IllegalStateException("本地存档读取失败，原文件保留：" + name, error);
    }
  }

  void write(String name, Object value) {
    Path temporary = null;
    try {
      byte[] data = mapper.writeValueAsBytes(value);
      if (data.length > 256L * 1024 * 1024) throw new IOException("存档超过 256 MiB");
      temporary =
          Files.createTempFile(
              root,
              "checkpoint-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          temporary,
          file(name),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException error) {
      throw new IllegalStateException("本地存档写入失败，操作未确认：" + name, error);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }
  }

  java.util.List<String> experiments() {
    try (var files = Files.list(root)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.matches("experiment-[a-f0-9-]{36}\\.json"))
          .sorted()
          .map(n -> n.substring(0, n.length() - 5))
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("无法读取实验归档", e);
    }
  }

  void deleteExperiment(String id) {
    try {
      Files.deleteIfExists(file("experiment-" + id));
    } catch (IOException error) {
      throw new IllegalStateException("实验归档删除失败", error);
    }
  }

  private Path file(String name) {
    if (!name.matches("scenarios|experiment-[a-f0-9-]{36}"))
      throw new IllegalArgumentException("非法存档名");
    return root.resolve(name + ".json");
  }

  @PreDestroy
  void close() throws IOException {
    lock.release();
    lockChannel.close();
  }
}
