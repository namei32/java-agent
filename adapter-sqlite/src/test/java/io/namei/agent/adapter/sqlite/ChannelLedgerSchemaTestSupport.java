package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ChannelLedgerSchemaTestSupport {
  private ChannelLedgerSchemaTestSupport() {}

  static void createV0(Path database) throws Exception {
    Files.createDirectories(database.getParent());
    try (var connection = rawConnection(database);
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE channel_schema (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            version INTEGER NOT NULL CHECK (version >= 0),
            updated_at TEXT NOT NULL
          )
          """);
      statement.execute("INSERT INTO channel_schema VALUES (1, 0, '2026-07-16T00:00:00Z')");
    }
  }

  static Connection rawConnection(Path database) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
  }

  static Set<String> tables(Connection connection) throws SQLException {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE"
                    + " 'sqlite_%'")) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return Set.copyOf(names);
  }

  static Set<String> viewsAndTriggers(Connection connection) throws SQLException {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT type || ':' || name FROM sqlite_schema WHERE type IN ('view', 'trigger')")) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return Set.copyOf(names);
  }

  static List<String> columns(Connection connection, String table) throws SQLException {
    var names = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
    }
    return List.copyOf(names);
  }

  static Set<String> indexes(Connection connection) throws SQLException {
    var names = new HashSet<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type = 'index' AND name NOT LIKE"
                    + " 'sqlite_%'")) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return Set.copyOf(names);
  }

  static List<String> indexColumns(Connection connection, String index) throws SQLException {
    var columns = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA index_xinfo('" + index + "')")) {
      while (rows.next()) {
        if (rows.getInt("key") == 1 && rows.getInt("cid") >= 0) {
          columns.add(rows.getString("name") + (rows.getBoolean("desc") ? " DESC" : " ASC"));
        }
      }
    }
    return List.copyOf(columns);
  }

  static int schemaVersion(Connection connection) throws SQLException {
    try (var rows =
        connection.createStatement().executeQuery("SELECT version FROM channel_schema")) {
      assertThat(rows.next()).isTrue();
      int version = rows.getInt(1);
      assertThat(rows.next()).isFalse();
      return version;
    }
  }

  static String schemaTimestamp(Connection connection) throws SQLException {
    try (var rows =
        connection.createStatement().executeQuery("SELECT updated_at FROM channel_schema")) {
      assertThat(rows.next()).isTrue();
      String value = rows.getString(1);
      assertThat(rows.next()).isFalse();
      return value;
    }
  }

  static String pragmaText(Connection connection, String name) throws SQLException {
    try (var rows = connection.createStatement().executeQuery("PRAGMA " + name)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  static int pragmaInt(Connection connection, String name) throws SQLException {
    try (var rows = connection.createStatement().executeQuery("PRAGMA " + name)) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  static String quickCheck(Connection connection) throws SQLException {
    try (var rows = connection.createStatement().executeQuery("PRAGMA quick_check(1)")) {
      assertThat(rows.next()).isTrue();
      String value = rows.getString(1);
      assertThat(rows.next()).isFalse();
      return value;
    }
  }
}
