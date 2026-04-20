package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Adapted to wrap SemanticMergeCommand and expose the mergeBidirectional() entry point
// used by SemanticConflictDetector steps 8 and 9.

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Facade over {@link SemanticMergeCommand} that exposes a single
 * {@link #mergeBidirectional} method for use in the replay steps (8 and 9) of the
 * merge pipeline.
 *
 * <p>Steps 8 and 9 use {@link #mergeBidirectional} only. The iterative footprint
 * refinement ({@code mergeWithInterleaving}) is out of scope for this thesis.
 *
 * <p>Source: anonymous author (paper companion code). Adapted to wrap
 * {@link SemanticMergeCommand} and expose the {@code mergeBidirectional} entry point
 * used by {@link tools.vitruv.framework.vsum.branch.SemanticConflictDetector}.
 */
public class SemanticMergeEngine {

  private static final Logger LOGGER = LogManager.getLogger(SemanticMergeEngine.class);

  private final Path repositoryRoot;
  private final IntraBranchDependencyMode mode;
  private final MergeTracer tracer;

  /**
   * Creates a new {@link SemanticMergeEngine}.
   *
   * @param repositoryRoot root of the Git repository; must not be null.
   * @param mode           intra-branch ordering mode for graph construction.
   * @param tracer         optional tracer for debug output; may be null.
   */
  public SemanticMergeEngine(Path repositoryRoot, IntraBranchDependencyMode mode,
      MergeTracer tracer) {
    this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
    this.mode = Objects.requireNonNull(mode, "mode must not be null");
    this.tracer = tracer;
  }

  /**
   * Runs the bidirectional replay pipeline for two sets of commits.
   *
   * <p>Builds the dependency graph from both sides, computes the topological order,
   * and runs the replay stub (full replay requires a live Vitruvius model).
   *
   * <p>Corresponds to steps 8 and 9 of the merge pipeline:
   * <ul>
   *   <li>Step 8: apply each commit's original changes in topological order through the
   *       Vitruvius Reaction engine.</li>
   *   <li>Step 9: compare actual vs. estimated consequential footprints; rebuild graph
   *       if new overlaps appear (iterative refinement - out of scope for thesis).</li>
   * </ul>
   *
   * @param commitsA per-commit {@link ChangeDto} lists for branch A; must not be null.
   * @param commitsB per-commit {@link ChangeDto} lists for branch B; must not be null.
   * @return a {@link SemanticMergeResult} describing the merge outcome.
   */
  public SemanticMergeResult mergeBidirectional(
      Map<String, List<ChangeDto>> commitsA,
      Map<String, List<ChangeDto>> commitsB) {
    Objects.requireNonNull(commitsA, "commitsA must not be null");
    Objects.requireNonNull(commitsB, "commitsB must not be null");

    LOGGER.info("SemanticMergeEngine: starting bidirectional merge ({} + {} commits)",
        commitsA.size(), commitsB.size());

    // Use ABORT as the default resolution provider: any replay conflict stops the merge.
    // A proper implementation would accept a ConflictResolutionProvider via the constructor.
    ConflictResolutionProvider provider = conflict -> ConflictResolution.ABORT;
    SemanticMergeCommand command = new SemanticMergeCommand(
        repositoryRoot, commitsA, commitsB, mode, provider, tracer);
    return command.execute();
  }
}
