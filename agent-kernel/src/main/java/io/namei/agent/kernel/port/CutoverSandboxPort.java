package io.namei.agent.kernel.port;

import io.namei.agent.kernel.cutover.CutoverBackupManifest;
import io.namei.agent.kernel.cutover.CutoverDifferenceReport;

/** A sandbox-only file boundary; production roots and process control are intentionally absent. */
public interface CutoverSandboxPort {
  CutoverBackupManifest backup();

  boolean verify(CutoverBackupManifest expected);

  CutoverDifferenceReport compare(CutoverBackupManifest expected, int threshold);
}
