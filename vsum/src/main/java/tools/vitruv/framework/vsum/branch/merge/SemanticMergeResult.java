package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of a semantic merge operation performed by the replay engine
 * (steps 8-9 of the merge pipeline).
 *
 * <p>A successful merge has {@link #isSuccess()} true and an empty
 * {@link #getConflicts()} list. A failed merge has {@link #isSuccess()} false
 * and a non-empty conflicts list explaining what went wrong.
 */
public class SemanticMergeResult {

  private final boolean success;
  private final List<MergeConflict> conflicts;
  private final String message;

  /**
   * Creates a new {@link SemanticMergeResult}.
   *
   * @param success   whether the merge completed without unresolved conflicts.
   * @param conflicts list of unresolved conflicts; empty for a successful merge.
   * @param message   human-readable summary of the merge outcome.
   */
  public SemanticMergeResult(boolean success, List<MergeConflict> conflicts, String message) {
    this.success = success;
    this.conflicts = Collections.unmodifiableList(
        Objects.requireNonNull(conflicts, "conflicts must not be null"));
    this.message = Objects.requireNonNull(message, "message must not be null");
  }

  /**
   * Returns a successful result with no conflicts.
   *
   * @param message summary of what was merged.
   * @return successful result.
   */
  public static SemanticMergeResult success(String message) {
    return new SemanticMergeResult(true, List.of(), message);
  }

  /**
   * Returns a failed result with the given unresolved conflicts.
   *
   * @param conflicts the conflicts that could not be resolved.
   * @param message   description of the failure.
   * @return failed result.
   */
  public static SemanticMergeResult failure(List<MergeConflict> conflicts, String message) {
    return new SemanticMergeResult(false, conflicts, message);
  }

  public boolean isSuccess() {
    return success;
  }

  public List<MergeConflict> getConflicts() {
    return conflicts;
  }

  public String getMessage() {
    return message;
  }

  public boolean hasConflicts() {
    return !conflicts.isEmpty();
  }

  @Override
  public String toString() {
    return "SemanticMergeResult{"
        + "success=" + success
        + ", conflicts=" + conflicts.size()
        + ", message='" + message + '\''
        + '}';
  }
}
