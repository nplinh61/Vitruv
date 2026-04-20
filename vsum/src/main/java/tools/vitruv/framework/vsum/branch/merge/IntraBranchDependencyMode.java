package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

/**
 * Controls how intra-branch commit ordering edges are added to the
 * {@link CommitDependencyGraph}.
 *
 * <p>Within a single branch, commits are ordered chronologically. When building
 * the dependency graph for replay, we can either strictly preserve that order
 * (add an edge between every consecutive pair) or only add edges where a
 * footprint overlap is detected.
 */
public enum IntraBranchDependencyMode {

  /**
   * Adds a dependency edge between every consecutive commit pair within a branch.
   * Preserves the original commit order during replay. Safest option; may produce
   * a more constrained interleaving.
   */
  PRESERVE_ORDER,

  /**
   * Only adds intra-branch edges where footprint overlaps are detected.
   * Allows the interleaving generator more freedom to reorder commits for a
   * better merge outcome, at the cost of potentially reordering independent commits.
   */
  FOOTPRINT_ONLY
}
