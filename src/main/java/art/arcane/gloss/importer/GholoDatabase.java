package art.arcane.gloss.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class GholoDatabase {
  static final long MAX_DATABASE_BYTES = 256L * 1024L * 1024L;

  private static final String DRIVER = "org.sqlite.JDBC";
  private static final String READ_ONLY_OPEN_MODE = "1";

  Contents read(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("GHolo data.db must be a real regular file");
    }
    if (Files.size(file) > MAX_DATABASE_BYTES) {
      throw new IOException("GHolo data.db exceeds " + MAX_DATABASE_BYTES + " bytes");
    }
    try {
      Class.forName(DRIVER);
    } catch (ClassNotFoundException failure) {
      throw new IOException("the server has no SQLite driver to read GHolo data.db", failure);
    }
    Properties properties = new Properties();
    properties.setProperty("open_mode", READ_ONLY_OPEN_MODE);
    try (Connection connection = DriverManager.getConnection(
        "jdbc:sqlite:" + file.toAbsolutePath().normalize(), properties)) {
      connection.setReadOnly(true);
      List<Holo> holos = hasTable(connection, "gholo_holo") ? holos(connection) : List.of();
      List<Interaction> interactions = hasTable(connection, "gholo_interaction")
          ? interactions(connection) : List.of();
      return new Contents(holos, interactions);
    } catch (SQLException failure) {
      throw new IOException("GHolo data.db could not be read: " + failure.getMessage(), failure);
    }
  }

  private static List<Holo> holos(Connection connection) throws SQLException {
    Map<String, List<Row>> rows = new LinkedHashMap<>();
    if (hasTable(connection, "gholo_holo_row")) {
      try (Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(
               "SELECT holo_uuid, content, `offset`, data FROM gholo_holo_row ORDER BY holo_uuid, position")) {
        while (result.next()) {
          rows.computeIfAbsent(result.getString("holo_uuid"), key -> new ArrayList<>())
              .add(new Row(text(result.getString("content")), result.getString("offset"),
                  result.getString("data")));
        }
      }
    }
    List<Holo> holos = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT uuid, id, location, data FROM gholo_holo")) {
      while (result.next()) {
        String uuid = result.getString("uuid");
        holos.add(new Holo(result.getString("id"), result.getString("location"),
            result.getString("data"), List.copyOf(rows.getOrDefault(uuid, List.of()))));
      }
    }
    return List.copyOf(holos);
  }

  private static List<Interaction> interactions(Connection connection) throws SQLException {
    Map<String, List<Action>> actions = new LinkedHashMap<>();
    if (hasTable(connection, "gholo_interaction_action")) {
      try (Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery("SELECT interaction_uuid, type, parameter "
               + "FROM gholo_interaction_action ORDER BY interaction_uuid, position")) {
        while (result.next()) {
          actions.computeIfAbsent(result.getString("interaction_uuid"), key -> new ArrayList<>())
              .add(new Action(text(result.getString("type")), text(result.getString("parameter"))));
        }
      }
    }
    List<Interaction> interactions = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT uuid, id, location, data FROM gholo_interaction")) {
      while (result.next()) {
        String uuid = result.getString("uuid");
        interactions.add(new Interaction(result.getString("id"), result.getString("location"),
            result.getString("data"), List.copyOf(actions.getOrDefault(uuid, List.of()))));
      }
    }
    return List.copyOf(interactions);
  }

  private static boolean hasTable(Connection connection, String table) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  record Contents(List<Holo> holos, List<Interaction> interactions) {
  }

  record Holo(String id, String location, String data, List<Row> rows) {
  }

  record Row(String content, String offset, String data) {
  }

  record Interaction(String id, String location, String data, List<Action> actions) {
  }

  record Action(String type, String parameter) {
  }
}
