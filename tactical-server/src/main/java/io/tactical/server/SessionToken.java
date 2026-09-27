package io.tactical.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A new credential per process. Never printed, returned by HTTP, or supplied via URL. */
@Component
final class SessionToken {
  private final byte[] expected;

  SessionToken(@Value("${tactical.token-file}") String filename) throws IOException {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    Path target = Path.of(filename).toAbsolutePath();
    Files.createDirectories(target.getParent());
    // Fail closed on filesystems without POSIX permissions; Windows support is deferred.
    Path temporary =
        Files.createTempFile(
            target.getParent(),
            ".session-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      Files.writeString(temporary, token, StandardCharsets.UTF_8);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  boolean matches(String authorization) {
    return authorization != null
        && MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8));
  }
}
