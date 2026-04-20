package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

/**
 * Strategy interface for resolving {@link MergeConflict}s that arise during replay
 * (steps 8-9 of the merge pipeline).
 *
 * <p>Implementations can resolve conflicts automatically (e.g. always pick branch A)
 * or interactively (e.g. prompt the user). The replay engine calls
 * {@link #resolve(MergeConflict)} for each conflict it encounters.
 */
public interface ConflictResolutionProvider {

  /**
   * Decides how to handle the given merge conflict.
   *
   * @param conflict the conflict to resolve; must not be null.
   * @return the resolution to apply; must not be null.
   */
  ConflictResolution resolve(MergeConflict conflict);
}
