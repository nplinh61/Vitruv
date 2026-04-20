package tools.vitruv.framework.vsum.branch.merge;

// This class bridges the two changelog formats.
// My format: SemanticChangeEntry (stored per-commit in .vitruvius/changelogs/).
// Anonymous-repo format: ChangeDto (used by CommitDependencyGraph for replay).
// The consequentialFootprints set is filled from entries where origin == CONSEQUENTIAL.
// CommitDependencyGraph uses that set to build inter-branch dependency edges.

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

/**
 * Converts a list of {@link SemanticChangeEntry} records for a single commit into the
 * {@link ChangeDto} format used by the merge pipeline ({@link CommitDependencyGraph},
 * {@link SemanticMergeCommand}).
 *
 * <p>For each commit, call {@link #convert(String, List)} to get the {@link ChangeDto}
 * list. Then call {@link #getConsequentialFootprint(String)} to retrieve the set of
 * {@code "uuid|feature"} keys for engine-generated changes -- this is what
 * {@link CommitDependencyGraph} needs to build inter-branch dependency edges.
 *
 * <p>Typical usage:
 * <pre>{@code
 *   SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
 *   Map<String, List<ChangeDto>> commitsA = new LinkedHashMap<>();
 *   for (var commitEntry : shortShasToEntries.entrySet()) {
 *       String sha = commitEntry.getKey();
 *       List<ChangeDto> dtos = converter.convert(sha, commitEntry.getValue());
 *       commitsA.put(sha, dtos);
 *   }
 *   // CommitDependencyGraph will call converter.getConsequentialFootprint(sha) internally
 *   // via the ChangeDto.isConsequential() flag.
 * }</pre>
 */
public class SemanticChangeEntryToDto {

  private static final Logger LOGGER = LogManager.getLogger(SemanticChangeEntryToDto.class);

  /**
   * Per-commit cache of consequential footprint keys ({@code "uuid|feature"}).
   * Populated as a side effect of {@link #convert(String, List)}.
   */
  private final Map<String, Set<String>> consequentialFootprintsByCommit = new LinkedHashMap<>();

  /**
   * Converts a list of {@link SemanticChangeEntry} records for a single commit to
   * {@link ChangeDto} objects.
   *
   * <p>As a side effect, builds the consequential footprint for this commit (accessible
   * via {@link #getConsequentialFootprint(String)}). The footprint is the set of
   * {@code "uuid|feature"} pairs from entries with {@link ChangeOrigin#CONSEQUENTIAL}.
   *
   * @param commitSha short SHA of the commit (7 characters); must not be null.
   * @param entries   semantic change entries for this commit; must not be null.
   * @return unmodifiable list of {@link ChangeDto}s, one per entry.
   */
  public List<ChangeDto> convert(String commitSha, List<SemanticChangeEntry> entries) {
    Objects.requireNonNull(commitSha, "commitSha must not be null");
    Objects.requireNonNull(entries, "entries must not be null");

    List<ChangeDto> dtos = new ArrayList<>();
    Set<String> consequentialFp = new LinkedHashSet<>();

    for (SemanticChangeEntry entry : entries) {
      boolean isConsequential = entry.getOrigin() == ChangeOrigin.CONSEQUENTIAL;
      ChangeDto dto = new ChangeDto(
          commitSha,
          entry.getElementUuid(),
          entry.getFeature(),
          entry.getFrom(),
          entry.getTo(),
          entry.getChangeType() != null ? entry.getChangeType().name() : "UNKNOWN",
          isConsequential);
      dtos.add(dto);

      // Build the consequential footprint: used by CommitDependencyGraph to add
      // inter-branch edges wherever this commit's engine-generated writes overlap
      // another commit's developer-written (ORIGINAL) changes.
      if (isConsequential && entry.getElementUuid() != null) {
        String fpKey = entry.getElementUuid() + "|" + entry.getFeature();
        consequentialFp.add(fpKey);
      }
    }

    consequentialFootprintsByCommit.put(commitSha, Collections.unmodifiableSet(consequentialFp));
    LOGGER.debug("Converted {} entries for commit {} ({} consequential)",
        entries.size(), commitSha, consequentialFp.size());
    return Collections.unmodifiableList(dtos);
  }

  /**
   * Returns the consequential footprint for a commit as a set of {@code "uuid|feature"} keys.
   *
   * <p>The footprint is computed as a side effect of {@link #convert(String, List)} and
   * cached. Call {@link #convert} before calling this method.
   *
   * @param commitSha short SHA of the commit.
   * @return set of {@code "uuid|feature"} keys for all CONSEQUENTIAL entries in this commit;
   *         empty if no consequential entries were found or if {@link #convert} was not called yet.
   */
  public Set<String> getConsequentialFootprint(String commitSha) {
    return consequentialFootprintsByCommit.getOrDefault(commitSha, Set.of());
  }

  /**
   * Converts multiple commits at once.
   *
   * <p>Iterates over the map in insertion order and calls {@link #convert} for each entry.
   * Consequential footprints are cached as usual.
   *
   * @param commitToEntries map from short commit SHA to its list of entries (insertion order
   *                        should be chronological oldest-first for correct intra-branch edges).
   * @return map from short commit SHA to its {@link ChangeDto} list.
   */
  public Map<String, List<ChangeDto>> convertAll(
      Map<String, List<SemanticChangeEntry>> commitToEntries) {
    Objects.requireNonNull(commitToEntries, "commitToEntries must not be null");
    Map<String, List<ChangeDto>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<SemanticChangeEntry>> entry : commitToEntries.entrySet()) {
      result.put(entry.getKey(), convert(entry.getKey(), entry.getValue()));
    }
    return Collections.unmodifiableMap(result);
  }
}
