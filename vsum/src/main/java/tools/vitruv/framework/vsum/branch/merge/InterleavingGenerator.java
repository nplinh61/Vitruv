package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Produces the replay order for a dependency graph using Kahn's algorithm (step 7).
 *
 * <p>Kahn's algorithm repeatedly picks a node with no incoming edges (in-degree zero),
 * emits it to the output, then removes all outgoing edges from that node. This produces
 * a topological ordering of the graph. If the graph has a cycle, the output list will
 * be shorter than the total number of nodes; the leftover nodes are part of a cycle.
 */
public class InterleavingGenerator {

  private static final Logger LOGGER = LogManager.getLogger(InterleavingGenerator.class);

  /**
   * Produces a linear replay order by topologically sorting the dependency graph.
   *
   * <p>If the graph is acyclic, the returned list contains all nodes. If there is a cycle,
   * the returned list contains only the nodes that could be ordered; the rest are in the cycle.
   * The caller (step 6) is responsible for detecting cycles before calling this method.
   *
   * @param graph adjacency list from {@link CommitDependencyGraph#buildGraph}
   *              (each key maps to the set of commits that must come after it).
   * @return commit SHAs in a valid replay order (topological sort).
   */
  public List<String> computeOrder(Map<String, Set<String>> graph) {
    Objects.requireNonNull(graph, "graph must not be null");

    // Build in-degree table: count how many nodes have an edge pointing to each node.
    Map<String, Integer> inDegree = new LinkedHashMap<>();
    for (String node : graph.keySet()) {
      inDegree.putIfAbsent(node, 0);
    }
    for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
      for (String successor : entry.getValue()) {
        inDegree.merge(successor, 1, Integer::sum);
      }
    }

    // Seed the queue with all nodes that have no incoming edges (in-degree 0).
    Queue<String> queue = new ArrayDeque<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
      }
    }

    List<String> order = new ArrayList<>();
    while (!queue.isEmpty()) {
      String node = queue.poll();
      order.add(node);

      // Remove outgoing edges: decrement in-degree of each successor.
      // When a successor reaches in-degree 0, it can be emitted next.
      Set<String> successors = graph.getOrDefault(node, Set.of());
      for (String successor : successors) {
        int newDegree = inDegree.merge(successor, -1, Integer::sum);
        if (newDegree == 0) {
          queue.add(successor);
        }
      }
    }

    LOGGER.debug("Interleaving: {} of {} nodes in topological order",
        order.size(), graph.size());
    if (order.size() < graph.size()) {
      LOGGER.warn("Cycle detected: {} node(s) could not be ordered (cycle in dependency graph)",
          graph.size() - order.size());
    }
    return order;
  }

  /**
   * Returns the set of nodes that are part of a cycle in the graph.
   *
   * <p>A node is in a cycle when it does not appear in the output of {@link #computeOrder}.
   * The result can be used by step 6 to report consequential conflicts.
   *
   * @param graph the dependency graph.
   * @param orderedNodes the nodes produced by {@link #computeOrder} (cycle-free subset).
   * @return nodes not in the topological order, i.e. those that form or depend on a cycle.
   */
  public List<String> findCyclicNodes(Map<String, Set<String>> graph, List<String> orderedNodes) {
    List<String> cyclic = new ArrayList<>(graph.keySet());
    cyclic.removeAll(orderedNodes);
    return cyclic;
  }
}
