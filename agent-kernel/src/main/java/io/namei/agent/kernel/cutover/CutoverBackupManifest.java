package io.namei.agent.kernel.cutover;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record CutoverBackupManifest(String backupId, List<CutoverBackupEntry> entries) {
  private static final Pattern ID = Pattern.compile("backup-[a-z0-9-]{1,63}");

  public CutoverBackupManifest {
    if (backupId == null || !ID.matcher(backupId).matches()) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (entries.isEmpty()
        || new HashSet<>(entries.stream().map(CutoverBackupEntry::relativePath).toList()).size()
            != entries.size()) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
  }
}
