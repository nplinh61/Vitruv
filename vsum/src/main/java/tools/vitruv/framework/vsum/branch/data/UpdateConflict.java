package tools.vitruv.framework.vsum.branch.data;

// Source: Tural Mammadlee (feature/conflict-analyzer branch, Vitruv fork).
// Adapted with package and import changes only.

import java.util.Objects;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;

/**
 * A direct conflict between two branches that has been analyzed for automatic resolution.
 *
 * <p>When one branch's change is {@link ChangeOrigin#ORIGINAL} and the other's is
 * {@link ChangeOrigin#CONSEQUENTIAL}, the conflict can be resolved automatically:
 * the ORIGINAL side wins because it represents the developer's direct intent, while
 * the CONSEQUENTIAL side is an engine-generated side effect that will be regenerated
 * during replay.
 *
 * <p>When both sides share the same origin (both ORIGINAL, both CONSEQUENTIAL, or
 * either side is UNKNOWN), the conflict cannot be auto-resolved and is returned to
 * the caller for human review.
 *
 * <p>Design based on Tural Mammadlee's UpdateConflict auto-resolution approach.
 */
public class UpdateConflict {

  /**
   * Describes the outcome of auto-resolution analysis for a conflict.
   */
  public enum Resolution {

    /**
     * Branch A's change wins: it was ORIGINAL while branch B's change was CONSEQUENTIAL.
     */
    AUTO_RESOLVED_BRANCH_A_WINS,

    /**
     * Branch B's change wins: it was ORIGINAL while branch A's change was CONSEQUENTIAL.
     */
    AUTO_RESOLVED_BRANCH_B_WINS,

    /**
     * Both sides have the same or unknown origin. A human must decide which side to keep.
     */
    NEEDS_HUMAN_REVIEW
  }

  private final SemanticConflict conflict;
  private final Resolution resolution;

  /**
   * Creates a new {@link UpdateConflict} pairing the underlying conflict with
   * the resolution determined by origin analysis.
   *
   * @param conflict   the underlying semantic conflict; must not be null.
   * @param resolution how the conflict was (or was not) resolved; must not be null.
   */
  public UpdateConflict(SemanticConflict conflict, Resolution resolution) {
    this.conflict = Objects.requireNonNull(conflict, "conflict must not be null");
    this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
  }

  /**
   * Returns the underlying {@link SemanticConflict} that was analyzed.
   */
  public SemanticConflict getConflict() {
    return conflict;
  }

  /**
   * Returns the {@link Resolution} determined during origin analysis.
   */
  public Resolution getResolution() {
    return resolution;
  }

  /**
   * Returns {@code true} if this conflict was resolved automatically and does not
   * require human review.
   */
  public boolean isAutoResolved() {
    return resolution != Resolution.NEEDS_HUMAN_REVIEW;
  }

  @Override
  public String toString() {
    return "UpdateConflict{"
        + "resolution=" + resolution
        + ", conflict=" + conflict
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
    UpdateConflict that = (UpdateConflict) o;
    return Objects.equals(conflict, that.conflict) && resolution == that.resolution;
  }

  @Override
  public int hashCode() {
    return Objects.hash(conflict, resolution);
  }
}
