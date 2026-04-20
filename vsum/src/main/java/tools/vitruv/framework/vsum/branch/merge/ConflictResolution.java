package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

/**
 * The decision made when resolving a {@link MergeConflict} during replay.
 *
 * <p>Used by {@link ConflictResolutionProvider} to communicate back to the
 * replay engine which action to take for a given conflict.
 */
public enum ConflictResolution {

  /**
   * Apply branch A's version of the conflicting change. Branch B's change is discarded.
   */
  BRANCH_A_WINS,

  /**
   * Apply branch B's version of the conflicting change. Branch A's change is discarded.
   */
  BRANCH_B_WINS,

  /**
   * Skip both conflicting changes. Neither side's version is applied.
   * Leaves the model in the state it was before either branch made the change.
   */
  SKIP,

  /**
   * Abort the entire merge. The model is left in the pre-merge state.
   */
  ABORT
}
