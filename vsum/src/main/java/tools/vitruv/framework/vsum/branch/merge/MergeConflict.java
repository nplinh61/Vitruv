package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.util.Objects;

/**
 * Represents a conflict detected during the replay phase of the merge pipeline
 * (steps 8-9 of the {@link tools.vitruv.framework.vsum.branch.SemanticConflictDetector}).
 *
 * <p>A replay conflict differs from a direct conflict (step 3): it arises when
 * applying a commit's change in the computed interleaving order fails because the
 * model guard is no longer satisfied (e.g. the target element was deleted by a
 * previously replayed commit).
 *
 * <p>Instances are produced by the replay engine and collected in {@link SemanticMergeResult}.
 */
public class MergeConflict {

  /** Short SHA of the commit whose change could not be applied. */
  private final String commitSha;

  /** The change that could not be applied. */
  private final ChangeDto change;

  /** Human-readable description of why the conflict occurred. */
  private final String reason;

  /**
   * Creates a new {@link MergeConflict}.
   *
   * @param commitSha short SHA of the conflicting commit; must not be null.
   * @param change    the change that could not be applied; must not be null.
   * @param reason    description of why the conflict occurred; must not be null.
   */
  public MergeConflict(String commitSha, ChangeDto change, String reason) {
    this.commitSha = Objects.requireNonNull(commitSha, "commitSha must not be null");
    this.change = Objects.requireNonNull(change, "change must not be null");
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  public String getCommitSha() {
    return commitSha;
  }

  public ChangeDto getChange() {
    return change;
  }

  public String getReason() {
    return reason;
  }

  @Override
  public String toString() {
    return "MergeConflict{"
        + "commitSha='" + commitSha + '\''
        + ", change=" + change
        + ", reason='" + reason + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    MergeConflict that = (MergeConflict) o;
    return Objects.equals(commitSha, that.commitSha)
        && Objects.equals(change, that.change)
        && Objects.equals(reason, that.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(commitSha, change, reason);
  }
}
