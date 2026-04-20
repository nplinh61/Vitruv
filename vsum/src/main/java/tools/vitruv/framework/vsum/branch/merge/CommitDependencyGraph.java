package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builds the footprint-based dependency graph used by the merge pipeline (step 5).
 *
 * <p>Nodes are commit SHAs. Edges are directed: an edge {@code A -> B} means commit
 * {@code A} must be replayed before commit {@code B} to preserve {@code B}'s intent.
 *
 * <p>Two types of edges are added:
 * <ol>
 *   <li><b>Intra-branch ordering edges</b>: in {@link IntraBranchDependencyMode#PRESERVE_ORDER}
 *       mode, a dependency edge is added between every consecutive commit pair within each
 *       branch. This ensures commits within a branch are replayed in original order.</li>
 *   <li><b>Inter-branch footprint edges</b>: if the consequential footprint of commit
 *       {@code A_i} (element-feature pairs written by Reactions) overlaps the original
 *       footprint of commit {@code B_j}, add edge {@code A_i -> B_j}. This ensures that
 *       {@code B_j}'s original change is the last write and its intent is preserved.</li>
 * </ol>
 *
 * <p>A footprint is represented as the set of {@code (affectedElementUuid, featureName)}
 * pairs from the list of {@link ChangeDto}s. The original footprint uses all non-consequential
 * changes; the consequential footprint uses all consequential changes.
 */
public class CommitDependencyGraph {

  private static final Logger LOGGER = LogManager.getLogger(CommitDependencyGraph.class);

  /**
   * Builds the dependency graph from the {@link ChangeDto} lists of two branches.
   *
   * @param commitsA  map from commit SHA to its {@link ChangeDto} list for branch A
   *                  (insertion order = chronological order, oldest first).
   * @param commitsB  map from commit SHA to its {@link ChangeDto} list for branch B.
   * @param mode      how intra-branch ordering edges are added.
   * @param tracer    optional tracer for debug output; may be null.
   * @return adjacency list mapping each commit SHA to the set of SHAs that must follow it.
   */
  public Map<String, Set<String>> buildGraph(
      Map<String, List<ChangeDto>> commitsA,
      Map<String, List<ChangeDto>> commitsB,
      IntraBranchDependencyMode mode,
      MergeTracer tracer) {
    Objects.requireNonNull(commitsA, "commitsA must not be null");
    Objects.requireNonNull(commitsB, "commitsB must not be null");
    Objects.requireNonNull(mode, "mode must not be null");

    Map<String, Set<String>> graph = new LinkedHashMap<>();

    // Initialize all nodes (every commit SHA gets an entry, even if it has no outgoing edges).
    initNodes(graph, commitsA.keySet());
    initNodes(graph, commitsB.keySet());

    // Add intra-branch ordering edges for each branch.
    if (mode == IntraBranchDependencyMode.PRESERVE_ORDER) {
      addIntraOrderEdges(graph, commitsA.keySet(), "A", tracer);
      addIntraOrderEdges(graph, commitsB.keySet(), "B", tracer);
    }

    // Add inter-branch footprint edges.
    addInterBranchEdges(graph, commitsA, commitsB, tracer);
    addInterBranchEdges(graph, commitsB, commitsA, tracer);

    LOGGER.debug("Dependency graph: {} nodes, {} total edges",
        graph.size(), graph.values().stream().mapToInt(Set::size).sum());
    return graph;
  }

  private void initNodes(Map<String, Set<String>> graph, Set<String> shas) {
    for (String sha : shas) {
      graph.putIfAbsent(sha, new LinkedHashSet<>());
    }
  }

  /**
   * Adds an edge from each commit to the next within the same branch to preserve commit order.
   */
  private void addIntraOrderEdges(Map<String, Set<String>> graph, Set<String> shas,
      String branchLabel, MergeTracer tracer) {
    List<String> ordered = new ArrayList<>(shas);
    for (int i = 0; i < ordered.size() - 1; i++) {
      String from = ordered.get(i);
      String to = ordered.get(i + 1);
      graph.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
      if (tracer != null) {
        tracer.trace("buildGraph", "intra-branch edge (branch " + branchLabel + "): "
            + from + " -> " + to);
      }
    }
  }

  /**
   * For each commit in {@code source}, checks whether its consequential footprint overlaps
   * the original footprint of any commit in {@code target}. If so, adds a source -> target edge.
   */
  private void addInterBranchEdges(
      Map<String, Set<String>> graph,
      Map<String, List<ChangeDto>> source,
      Map<String, List<ChangeDto>> target,
      MergeTracer tracer) {

    for (Map.Entry<String, List<ChangeDto>> entryA : source.entrySet()) {
      String shaA = entryA.getKey();
      Set<String> consequentialFp = buildConsequentialFootprint(entryA.getValue());
      if (consequentialFp.isEmpty()) {
        continue;
      }

      for (Map.Entry<String, List<ChangeDto>> entryB : target.entrySet()) {
        String shaB = entryB.getKey();
        Set<String> originalFp = buildOriginalFootprint(entryB.getValue());

        // Check if the two footprints overlap (share at least one element-feature key).
        boolean overlaps = consequentialFp.stream().anyMatch(originalFp::contains);
        if (overlaps) {
          graph.computeIfAbsent(shaA, k -> new LinkedHashSet<>()).add(shaB);
          if (tracer != null) {
            tracer.trace("buildGraph",
                "inter-branch edge (consequential overlap): " + shaA + " -> " + shaB);
          }
        }
      }
    }
  }

  /**
   * Builds a set of {@code "uuid|feature"} keys from changes where {@code isConsequential()}.
   */
  private Set<String> buildConsequentialFootprint(List<ChangeDto> changes) {
    Set<String> fp = new LinkedHashSet<>();
    for (ChangeDto dto : changes) {
      if (dto.isConsequential() && dto.getAffectedElementUuid() != null) {
        fp.add(footprintKey(dto));
      }
    }
    return fp;
  }

  /**
   * Builds a set of {@code "uuid|feature"} keys from changes where {@code !isConsequential()}.
   */
  private Set<String> buildOriginalFootprint(List<ChangeDto> changes) {
    Set<String> fp = new LinkedHashSet<>();
    for (ChangeDto dto : changes) {
      if (!dto.isConsequential() && dto.getAffectedElementUuid() != null) {
        fp.add(footprintKey(dto));
      }
    }
    return fp;
  }

  private static String footprintKey(ChangeDto dto) {
    return dto.getAffectedElementUuid() + "|" + dto.getFeatureName();
  }
}
