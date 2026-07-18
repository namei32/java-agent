package io.namei.agent.kernel.mcp;

/**
 * Immutable public metadata projection; it deliberately contains no URI, body, arguments or path.
 */
public record McpAssetDescriptor(
    int schemaVersion,
    String serverId,
    McpAssetKind kind,
    String localName,
    String name,
    String description,
    boolean available) {
  public McpAssetDescriptor {
    if (schemaVersion != McpAssetContract.CURRENT_VERSION
        || serverId == null
        || !McpAssetContract.SERVER_ID.matcher(serverId).matches()
        || kind == null
        || localName == null
        || !McpAssetContract.LOCAL_NAME.matcher(localName).matches()
        || !localName.startsWith("mcp_" + serverId + "__")) {
      throw McpAssetContract.violation();
    }
    McpAssetContract.requirePublicText(name, true);
    McpAssetContract.requirePublicText(description, false);
  }

  @Override
  public String toString() {
    return "McpAssetDescriptor[schemaVersion="
        + schemaVersion
        + ", serverId="
        + serverId
        + ", kind="
        + kind
        + ", localName="
        + localName
        + ", available="
        + available
        + "]";
  }
}
