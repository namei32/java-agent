package io.namei.agent.adapter.workspace;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Read-only existence checks. Implementations must not execute a binary or expose environment
 * values.
 */
public interface SkillRequirementChecker {
  boolean binaryAvailable(String name);

  boolean environmentAvailable(String name);

  static SkillRequirementChecker system() {
    return new SkillRequirementChecker() {
      @Override
      public boolean binaryAvailable(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
          return false;
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
          if (entry.isBlank()) {
            continue;
          }
          try {
            Path candidate = Path.of(entry).resolve(name).normalize();
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                && Files.isExecutable(candidate)) {
              return true;
            }
          } catch (RuntimeException ignored) {
            // An invalid PATH item is unavailable; it must not affect another item.
          }
        }
        return false;
      }

      @Override
      public boolean environmentAvailable(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
      }
    };
  }
}
