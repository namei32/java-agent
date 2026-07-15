package io.namei.agent.adapter.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface JavaMemoryBackup {
  void backup(Connection source, Path destination) throws SQLException;
}
