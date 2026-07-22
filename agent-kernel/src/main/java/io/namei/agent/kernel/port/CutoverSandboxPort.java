package io.namei.agent.kernel.port;

import io.namei.agent.kernel.cutover.CutoverBackupManifest;
import io.namei.agent.kernel.cutover.CutoverDifferenceReport;

/** 仅限 Sandbox 的文件边界；有意不包含生产 Root 和进程控制。 */
public interface CutoverSandboxPort {
  CutoverBackupManifest backup();

  boolean verify(CutoverBackupManifest expected);

  CutoverDifferenceReport compare(CutoverBackupManifest expected, int threshold);
}
