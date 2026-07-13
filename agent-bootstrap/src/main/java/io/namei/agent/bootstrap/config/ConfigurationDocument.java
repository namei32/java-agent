package io.namei.agent.bootstrap.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

final class ConfigurationDocument {
  private final TomlParseResult root;

  private ConfigurationDocument(TomlParseResult root) {
    this.root = root;
  }

  static LoadResult load(Path path) {
    try {
      String source = decodeUtf8(Files.readAllBytes(path));
      TomlParseResult parsed = Toml.parse(source);
      if (parsed.hasErrors()) {
        return LoadResult.failed(
            new ConfigurationDiagnostic(
                ConfigurationDiagnosticCode.CONFIG_TOML_INVALID, "$document"));
      }
      return LoadResult.loaded(new ConfigurationDocument(parsed));
    } catch (CharacterCodingException exception) {
      return LoadResult.failed(
          new ConfigurationDiagnostic(
              ConfigurationDiagnosticCode.CONFIG_TOML_INVALID, "$document"));
    } catch (IOException exception) {
      return LoadResult.failed(
          new ConfigurationDiagnostic(
              ConfigurationDiagnosticCode.CONFIG_FILE_INVALID, "$document"));
    }
  }

  TomlParseResult root() {
    return root;
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    return decoder.decode(ByteBuffer.wrap(bytes)).toString();
  }

  record LoadResult(
      Optional<ConfigurationDocument> document, Optional<ConfigurationDiagnostic> diagnostic) {
    static LoadResult loaded(ConfigurationDocument document) {
      return new LoadResult(Optional.of(document), Optional.empty());
    }

    static LoadResult failed(ConfigurationDiagnostic diagnostic) {
      return new LoadResult(Optional.empty(), Optional.of(diagnostic));
    }
  }
}
