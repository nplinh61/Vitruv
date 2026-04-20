package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;

/**
 * Converts stored {@link SemanticChangeEntry} records into {@link ChangeDto} objects
 * used by the merge pipeline.
 *
 * <p>The conversion is straightforward: one {@link SemanticChangeEntry} produces one
 * {@link ChangeDto}. The {@code isConsequential} flag is set when the entry's origin is
 * {@link ChangeOrigin#CONSEQUENTIAL}.
 *
 * <p>This deserializer also produces the per-commit grouping needed by
 * {@link CommitDependencyGraph#buildGraph}: entries from the same commit SHA are grouped
 * together in the returned map.
 */
public class ChangeDtoDeserializer {

  /**
   * Converts all change entries from a {@link ChangelogDocument} into a map of
   * {@link ChangeDto} lists, keyed by the short commit SHA.
   *
   * <p>If the document covers multiple files, all changes are grouped under the same
   * commit SHA regardless of which file they came from.
   *
   * @param doc the changelog document to deserialize; must not be null.
   * @return map from short commit SHA to its list of {@link ChangeDto}s (never null; may be empty).
   */
  public Map<String, List<ChangeDto>> deserialize(ChangelogDocument doc) {
    Objects.requireNonNull(doc, "doc must not be null");

    Map<String, List<ChangeDto>> result = new LinkedHashMap<>();
    if (doc.commit == null || doc.fileChanges == null) {
      return result;
    }

    String commitSha = doc.commit.shortSha != null ? doc.commit.shortSha : doc.commit.sha;
    List<ChangeDto> dtos = new ArrayList<>();

    for (ChangelogDocument.FileChangeInfo fileChange : doc.fileChanges) {
      if (fileChange.semanticChanges == null) {
        continue;
      }
      for (SemanticChangeEntry entry : fileChange.semanticChanges) {
        dtos.add(toDto(commitSha, entry));
      }
    }

    if (!dtos.isEmpty()) {
      result.put(commitSha, Collections.unmodifiableList(dtos));
    }
    return result;
  }

  /**
   * Converts a single {@link SemanticChangeEntry} to a {@link ChangeDto}.
   *
   * @param commitSha short SHA of the containing commit.
   * @param entry     the entry to convert; must not be null.
   * @return a {@link ChangeDto} with fields populated from the entry.
   */
  public ChangeDto toDto(String commitSha, SemanticChangeEntry entry) {
    Objects.requireNonNull(entry, "entry must not be null");
    boolean isConsequential = entry.getOrigin() == ChangeOrigin.CONSEQUENTIAL;
    return new ChangeDto(
        commitSha,
        entry.getElementUuid(),
        entry.getFeature(),
        entry.getFrom(),
        entry.getTo(),
        entry.getChangeType() != null ? entry.getChangeType().name() : "UNKNOWN",
        isConsequential);
  }
}
