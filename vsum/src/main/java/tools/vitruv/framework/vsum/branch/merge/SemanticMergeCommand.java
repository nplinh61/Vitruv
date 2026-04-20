package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Orchestrates the full semantic merge pipeline (steps 5-9) for two diverged branches.
 *
 * <p>A single instance represents one merge operation. Call {@link #execute()} to run
 * the pipeline; the result is returned as a {@link SemanticMergeResult}.
 *
 * <p>The pipeline:
 * <ol>
 *   <li>Build the footprint dependency graph ({@link CommitDependencyGraph}).</li>
 *   <li>Detect cycles in the graph (consequential conflicts).</li>
 *   <li>Topological sort ({@link InterleavingGenerator}) to get replay order.</li>
 *   <li>Replay each commit's original changes in order, capturing actual consequential
 *       footprints ({@link ChangeLogCapture}).</li>
 *   <li>Compare actual vs. estimated footprints; rebuild graph if new overlaps are found
 *       (iterative refinement, out of scope for the thesis).</li>
 * </ol>
 *
 * <p>Steps 4-5 (replay and iterative refinement) require a live Vitruvius model and are
 * therefore stubbed in this implementation. The command still runs steps 1-3 and produces
 * a valid ordering that can be inspected.
 */
public class SemanticMergeCommand {

  private static final Logger LOGGER = LogManager.getLogger(SemanticMergeCommand.class);

  private final Path repositoryRoot;
  private final Map<String, List<ChangeDto>> commitsA;
  private final Map<String, List<ChangeDto>> commitsB;
  private final IntraBranchDependencyMode mode;
  private final ConflictResolutionProvider resolutionProvider;
  private final MergeTracer tracer;

  /**
   * Creates a new {@link SemanticMergeCommand}.
   *
   * @param repositoryRoot      root of the Git repository.
   * @param commitsA            map from commit SHA to change DTOs for branch A.
   * @param commitsB            map from commit SHA to change DTOs for branch B.
   * @param mode                intra-branch ordering mode.
   * @param resolutionProvider  strategy for resolving replay conflicts.
   * @param tracer              tracer for debug output; may be null.
   */
  public SemanticMergeCommand(
      Path repositoryRoot,
      Map<String, List<ChangeDto>> commitsA,
      Map<String, List<ChangeDto>> commitsB,
      IntraBranchDependencyMode mode,
      ConflictResolutionProvider resolutionProvider,
      MergeTracer tracer) {
    this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
    this.commitsA = Objects.requireNonNull(commitsA, "commitsA must not be null");
    this.commitsB = Objects.requireNonNull(commitsB, "commitsB must not be null");
    this.mode = Objects.requireNonNull(mode, "mode must not be null");
    this.resolutionProvider = Objects.requireNonNull(resolutionProvider,
        "resolutionProvider must not be null");
    this.tracer = tracer;
  }

  /**
   * Executes the semantic merge pipeline and returns the result.
   *
   * <p>Steps 1-3 (graph construction, cycle detection, topological sort) are implemented.
   * Steps 4-5 (replay and iterative refinement) are stubbed: the method returns success
   * with an empty replay output when no cyclic conflicts are found.
   *
   * @return a {@link SemanticMergeResult} describing the outcome.
   */
  public SemanticMergeResult execute() {
    LOGGER.info("SemanticMergeCommand: starting pipeline ({} commits on A, {} on B)",
        commitsA.size(), commitsB.size());

    // Step 1: Build footprint dependency graph.
    CommitDependencyGraph graphBuilder = new CommitDependencyGraph();
    Map<String, Set<String>> graph = graphBuilder.buildGraph(commitsA, commitsB, mode, tracer);
    if (tracer != null) {
      tracer.trace("buildGraph", "graph built with " + graph.size() + " nodes");
    }

    // Step 2: Detect cycles.
    InterleavingGenerator generator = new InterleavingGenerator();
    List<String> order = generator.computeOrder(graph);
    List<String> cyclicNodes = generator.findCyclicNodes(graph, order);
    if (!cyclicNodes.isEmpty()) {
      LOGGER.warn("Cyclic nodes detected: {}", cyclicNodes);
      if (tracer != null) {
        tracer.warn("detectCycles", "cyclic nodes: " + cyclicNodes);
      }
      // Cyclic conflicts cannot be auto-resolved by ordering alone.
      return SemanticMergeResult.failure(List.of(),
          "Dependency cycle detected involving: " + cyclicNodes);
    }

    // Step 3: Topological order produced.
    LOGGER.info("Topological order: {}", order);
    if (tracer != null) {
      tracer.trace("computeOrder", "order: " + order);
    }

    // Steps 4-5: Replay with iterative refinement - stub.
    // Full replay requires a live Vitruvius InternalVirtualModel, which is out of scope here.
    // The order is valid and ready for use when a model is available.
    LOGGER.info("SemanticMergeCommand: pipeline complete (replay stub). Order: {}", order);
    return SemanticMergeResult.success(
        "Merge pipeline completed. Replay order: " + order
            + ". Full replay requires a live Vitruvius model (out of scope).");
  }
}
