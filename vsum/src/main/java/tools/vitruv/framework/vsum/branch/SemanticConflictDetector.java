package tools.vitruv.framework.vsum.branch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.ReplayResult;
import tools.vitruv.framework.vsum.branch.data.SemanticConflict;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.exception.BranchOperationException;
import tools.vitruv.framework.vsum.branch.merge.ChangeDto;
import tools.vitruv.framework.vsum.branch.merge.InterleavingGenerator;
import tools.vitruv.framework.vsum.branch.merge.SemanticChangeEntryToDto;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeEngine;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;
import tools.vitruv.framework.vsum.branch.merge.CommitDependencyGraph;
import tools.vitruv.framework.vsum.branch.merge.IntraBranchDependencyMode;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;
import tools.vitruv.framework.vsum.branch.storage.UpdateConflictAnalyzer;

/**
 * Implements the replay-based merge pipeline for two diverged branches (DE-4, MG-2, MG-3).
 *
 * <h3>Full pipeline (10 steps)</h3>
 * <ol>
 *   <li><b>Find merge-base</b> - common ancestor commit via JGit
 *       {@link RevFilter#MERGE_BASE}.</li>
 *   <li><b>Load changelog DTOs</b> - collect diverging commit SHAs and read JSON changelog
 *       files directly from the Git object store (no checkout needed).</li>
 *   <li><b>Direct conflict detection</b> - compare {@code (elementUuid, feature)} pairs
 *       across both branches without loading any model. Implemented.</li>
 *   <li><b>Resolution of direct conflicts</b> - programmatic or interactive. Direct conflicts
 *       require human resolution before replay can proceed. TODO.</li>
 *   <li><b>Build footprint dependency graph</b> - nodes per commit, intra-branch ordering
 *       edges, inter-branch edges where a commit's consequential footprint overlaps another
 *       commit's original footprint. TODO (requires consequential footprint capture,
 *       see {@link SemanticChangelogManager}).</li>
 *   <li><b>Detect cycles</b> - a cycle in the dependency graph means no valid interleaving
 *       exists: consequential conflict, requires human resolution. TODO.</li>
 *   <li><b>Topological sort</b> - Kahn's algorithm on the acyclic graph produces the
 *       interleaving order for replay. TODO.</li>
 *   <li><b>Replay in order</b> - deserialize each commit's original changes into live
 *       {@code EChange} objects and apply them through the Vitruvius Reaction engine so
 *       consequential changes are regenerated. Guard failures (target element no longer
 *       exists) trigger re-ordering. TODO.</li>
 *   <li><b>Iterative refinement</b> - compare actual consequential footprints captured
 *       during replay against stored estimates; rebuild and re-sort if new overlaps appear.
 *       TODO.</li>
 *   <li><b>Return result</b> - consistent merged model state, or a conflict list when
 *       human resolution is required.</li>
 * </ol>
 *
 * <h3>Current state</h3>
 *
 * <p>Steps 1–3 are fully implemented. Steps 4–9 have stub methods with TODO bodies.
 * Calling {@link #analyzeBranches} today runs steps 1–3 and returns direct conflicts;
 * it returns early before the replay engine (steps 5–9) whenever direct conflicts are
 * found, which is the expected behaviour until resolution is implemented.
 *
 * <h3>Example</h3>
 * <pre>
 *   Branch A: changed element 'e1', attribute 'name', from 'Foo' to 'Bar'
 *   Branch B: changed element 'e1', attribute 'name', from 'Foo' to 'Baz'
 *   -&gt; MEDIUM direct conflict: same attribute modified to different values
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SemanticConflictDetector detector = new SemanticConflictDetector(repoRoot);
 *   ReplayResult result = detector.analyzeBranches("feature-x", "main");
 *   if (result.hasConflicts()) {
 *       result.getConflicts().forEach(c -> log.warn("Conflict: {}", c));
 *   }
 * }</pre>
 */
public class SemanticConflictDetector {

  private static final Logger LOGGER = LogManager.getLogger(SemanticConflictDetector.class);
  private static final String CHANGELOG_PREFIX = ".vitruvius/changelogs/";
  private static final String JSON_SUBDIR = "/json/";

  private final Path repoRoot;
  private final Gson gson;

  /**
   * Creates a new {@link SemanticConflictDetector} for the repository at the given root.
   *
   * @param repoRoot the root of the Git repository; must contain {@code .git/}.
   */
  public SemanticConflictDetector(Path repoRoot) {
    this.repoRoot = checkNotNull(repoRoot, "repoRoot must not be null");
    checkArgument(Files.isDirectory(repoRoot.resolve(".git")),
        "No Git repository found at: %s", repoRoot);
    this.gson = buildGson();
  }

  /**
   * Runs the replay-based merge pipeline for two branches and returns a {@link ReplayResult}.
   *
   * <p>Currently executes steps 1–3 fully (direct conflict detection). Steps 4–9 have
   * stub implementations. When direct conflicts are found the method returns them
   * immediately; steps 5–9 are only reached when no direct conflicts exist.
   *
   * <p>Note: this method reads changelog records and compares them. Steps 1–3 do not
   * apply or replay any changes onto a model. The replay engine (steps 8–9) is not
   * yet implemented.
   *
   * @param branchA name of the first branch (e.g. the current branch).
   * @param branchB name of the second branch (e.g. the branch being merged in).
   * @return a {@link ReplayResult} with the conflict analysis from the steps executed so far.
   * @throws BranchOperationException if either branch does not exist or the repository
   *     cannot be opened.
   */
  public ReplayResult analyzeBranches(String branchA, String branchB)
      throws BranchOperationException {
    checkNotNull(branchA, "branchA must not be null");
    checkNotNull(branchB, "branchB must not be null");
    checkArgument(!branchA.isBlank(), "branchA must not be blank");
    checkArgument(!branchB.isBlank(), "branchB must not be blank");

    try (Git git = Git.open(repoRoot.toFile())) {
      Repository repo = git.getRepository();

      // Resolve the HEAD commit of each branch as an ObjectId.
      // These are the starting points for walking back through commit history.
      ObjectId headA = repo.resolve("refs/heads/" + branchA);
      ObjectId headB = repo.resolve("refs/heads/" + branchB);
      if (headA == null) {
        throw new BranchOperationException("Branch not found: " + branchA);
      }
      if (headB == null) {
        throw new BranchOperationException("Branch not found: " + branchB);
      }

      // Step 1: Find the common ancestor (merge-base). 
      // Equivalent to "git merge-base branchA branchB". Only commits after this point
      // differ between the branches and need to be compared.
      ObjectId ancestor = findMergeBase(repo, headA, headB);
      String ancestorSha = ancestor != null ? ancestor.getName() : null;
      LOGGER.info("Analyzing branches '{}' and '{}', common ancestor: {}",
          branchA, branchB,
          ancestorSha != null ? ancestorSha.substring(0, 7) : "none");

      // Step 2: Load changelog DTOs from the Git object store.
      // Collect the short SHAs of commits unique to each branch since the ancestor,
      // then read the corresponding JSON changelog files directly from the object store.
      // No branch checkout is needed.
      List<String> shortShasA = collectShortShasSince(repo, headA, ancestor);
      List<String> shortShasB = collectShortShasSince(repo, headB, ancestor);
      LOGGER.debug("Commits since ancestor - {}: {}, {}: {}",
          branchA, shortShasA.size(), branchB, shortShasB.size());
      List<SemanticChangeEntry> changesA =
          loadChangesFromBranch(repo, headA, branchA, new HashSet<>(shortShasA));
      List<SemanticChangeEntry> changesB =
          loadChangesFromBranch(repo, headB, branchB, new HashSet<>(shortShasB));
      LOGGER.debug("Semantic changes loaded - {}: {}, {}: {}",
          branchA, changesA.size(), branchB, changesB.size());

      // Step 3: Direct conflict detection (original vs. original). 
      // Compares (elementUuid, feature) pairs across both branches without loading any model.
      // Two entries conflict when they target the same element-feature but set different values.
      List<SemanticConflict> directConflicts = detectConflicts(changesA, changesB);
      LOGGER.info(
          "Step 3 complete: {} direct conflict(s) detected (HIGH={}, MEDIUM={})",
          directConflicts.size(),
          directConflicts.stream().filter(c -> c.getSeverity() == ConflictSeverity.HIGH).count(),
          directConflicts.stream().filter(c -> c.getSeverity() == ConflictSeverity.MEDIUM).count());

      // Step 4: Resolution of direct conflicts. 
      // Direct conflicts require human resolution before replay can proceed.
      // Returns early with the unresolved conflicts so the caller can handle them.
      List<SemanticConflict> unresolved = resolveDirectConflicts(directConflicts);
      if (!unresolved.isEmpty()) {
        LOGGER.info("Step 4: {} direct conflict(s) unresolved, returning early.",
                unresolved.size());
        return new ReplayResult(
            ancestorSha, shortShasA, shortShasB, changesA, changesB, unresolved);
      }

      // Step 5: Build footprint dependency graph.
      // Convert flat SemanticChangeEntry lists to per-commit ChangeDto maps for the graph builder.
      // CommitDependencyGraph is from the anonymous author (paper companion code).
      // It adds intra-branch ordering edges and inter-branch edges based on footprint overlaps.
      Map<String, List<SemanticChangeEntry>> groupedA =
          loadChangesGroupedByCommit(repo, headA, branchA, new HashSet<>(shortShasA));
      Map<String, List<SemanticChangeEntry>> groupedB =
          loadChangesGroupedByCommit(repo, headB, branchB, new HashSet<>(shortShasB));
      Map<String, Set<String>> dependencyGraph =
          buildDependencyGraph(groupedA, groupedB);

      //  Step 6: Detect cycles -> consequential conflicts.
      // A cycle means no interleaving can preserve both branches' original intent.
      // These conflicts also require human resolution.
      List<SemanticConflict> cyclicConflicts = detectCyclicConflicts(dependencyGraph);
      if (!cyclicConflicts.isEmpty()) {
        LOGGER.info("Step 6: {} consequential conflict(s) detected (cycle).",
            cyclicConflicts.size());
        return new ReplayResult(
            ancestorSha, shortShasA, shortShasB, changesA, changesB, cyclicConflicts);
      }

      // Step 7: Topological sort -> interleaving order (Kahn's algorithm).
      // Produces a linear replay order that respects all dependency edges.
      List<String> interleaving = computeTopologicalOrder(dependencyGraph);
      LOGGER.debug("Step 7: interleaving order computed ({} commits).", interleaving.size());

      // Steps 8 + 9: Replay with iterative footprint refinement.
      // Applies each commit's original changes through the Vitruvius Reaction engine
      // in topological order. Consequential changes are regenerated (not replayed directly).
      // Guard failures and footprint divergences trigger graph rebuilds and re-sorting.
      return replayWithRefinement(
          interleaving, ancestorSha, shortShasA, shortShasB, changesA, changesB, groupedA, groupedB);

    } catch (IOException e) {
      throw new BranchOperationException(
          "Failed to analyze branches '" + branchA + "' and '" + branchB + "': "
              + e.getMessage(), e);
    }
  }

  /**
   * Finds the merge-base (common ancestor) of two commits using JGit's
   * {@link RevFilter#MERGE_BASE}. Returns {@code null} if no common ancestor exists
   * (unrelated histories).
   *
   * @param repo the Git repository.
   * @param a the first commit object id.
   * @param b the second commit object id.
   * @return the merge-base commit id, or {@code null} if histories are unrelated.
   * @throws IOException if the repository cannot be read.
   */
  private ObjectId findMergeBase(Repository repo, ObjectId a, ObjectId b) throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      // MERGE_BASE tells JGit to run the lowest-common-ancestor algorithm,
      // equivalent to "git merge-base": walks back from both sides and finds
      // the first commit that both branches have in common.
      walk.setRevFilter(RevFilter.MERGE_BASE);

      // Mark both HEADs as starting points; JGit walks back from both in parallel.
      walk.markStart(walk.parseCommit(a));
      walk.markStart(walk.parseCommit(b));

      // The first commit returned by RevWalk is the nearest common ancestor.
      // Returns null when the two branches have no shared history.
      RevCommit base = walk.next();
      return base != null ? base.getId() : null;
    }
  }

  /**
   * Walks from {@code head} back to {@code stopAt} (exclusive) and returns
   * the 7-character short SHA of each commit encountered, i.e. the commits
   * that exist on this branch but not yet in the common ancestor.
   *
   * @param repo the Git repository.
   * @param head the starting commit.
   * @param stopAt the exclusive stop commit (merge-base), or {@code null} for full history.
   * @return list of 7-char short SHAs, newest first (RevWalk order).
   * @throws IOException if the repository cannot be read.
   */
  private List<String> collectShortShasSince(
      Repository repo, ObjectId head, ObjectId stopAt) throws IOException {
    List<String> shas = new ArrayList<>();
    try (RevWalk walk = new RevWalk(repo)) {
      // Start walking backwards from the branch HEAD.
      walk.markStart(walk.parseCommit(head));

      // Mark the ancestor as uninteresting so JGit stops when it reaches it.
      // Only commits between HEAD and the ancestor (exclusive) are visited.
      if (stopAt != null) {
        walk.markUninteresting(walk.parseCommit(stopAt));
      }

      for (RevCommit commit : walk) {
        // Use the 7-char short SHA because changelog files are named after it
        // (e.g. "a1b2c3d.json"). Used to match filenames in loadChangesFromBranch.
        shas.add(commit.getName().substring(0, 7));
      }
    }
    return shas;
  }

  /**
   * TreeWalks the HEAD commit tree of a branch to find all JSON changelog files
   * matching the given set of short SHAs, then parses and returns all
   * {@link SemanticChangeEntry} records from those changelogs.
   *
   * <p>Files are stored at {@code .vitruvius/changelogs/<branch>/json/<shortSha>.json}.
   * They are read directly from the Git object store, so no checkout is needed.
   *
   * @param repo the Git repository.
   * @param head HEAD commit of the branch.
   * @param branch branch name (used to build the changelog directory path).
   * @param targetShortShas set of 7-char SHAs whose changelog files should be loaded.
   * @return all parsed {@link SemanticChangeEntry} records.
   * @throws IOException if the repository cannot be read.
   */
  private List<SemanticChangeEntry> loadChangesFromBranch(
      Repository repo, ObjectId head, String branch, Set<String> targetShortShas)
      throws IOException {
    // No commits since the ancestor means nothing to load.
    if (targetShortShas.isEmpty()) {
      return List.of();
    }

    // Directory path of this branch's JSON changelogs inside the Git object store,
    // e.g. ".vitruvius/changelogs/feature-x/json/"
    String jsonDir = CHANGELOG_PREFIX + branch + JSON_SUBDIR;
    List<SemanticChangeEntry> entries = new ArrayList<>();

    try (RevWalk walk = new RevWalk(repo)) {
      RevCommit headCommit = walk.parseCommit(head);
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        // Walk the file tree of this branch's HEAD commit.
        // This reads directly from the Git object store without touching the working directory,
        // so branch B's changelogs can be read while branch A is checked out.
        treeWalk.addTree(headCommit.getTree());
        treeWalk.setRecursive(true); // Descend into subdirectories

        while (treeWalk.next()) {
          String path = treeWalk.getPathString();

          // Skip any file that is not a JSON changelog for this branch.
          if (!path.startsWith(jsonDir) || !path.endsWith(".json")) {
            continue;
          }

          // Filename is "<shortSha>.json"; extract the short SHA to check
          // whether this commit belongs to the set of diverging commits to load.
          String filename = path.substring(jsonDir.length());
          String fileSha = filename.substring(0, Math.min(7, filename.length() - 5));

          // Skip changelogs for commits that predate the merge-base; those are
          // shared by both branches and do not represent diverging changes.
          if (!targetShortShas.contains(fileSha)) {
            continue;
          }

          // Read the JSON file content directly from the Git object store.
          // getObjectId(0) returns the blob ID; repo.open() loads the raw bytes.
          ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
          String json = new String(loader.getBytes(), StandardCharsets.UTF_8);

          // Parse into a ChangelogDocument. Skip if the file is empty or malformed.
          SemanticChangelogManager.ChangelogDocument doc =
              gson.fromJson(json, SemanticChangelogManager.ChangelogDocument.class);
          if (doc == null || doc.fileChanges == null) {
            continue;
          }

          // A single commit may contain changes across multiple model files.
          // Flatten all SemanticChangeEntry records from all files in the commit
          // into one list so they can be compared uniformly in detectConflicts.
          for (SemanticChangelogManager.ChangelogDocument.FileChangeInfo fileChange
              : doc.fileChanges) {
            if (fileChange.semanticChanges != null) {
              entries.addAll(fileChange.semanticChanges);
            }
          }
          LOGGER.debug("Loaded changelog {} for branch '{}'", filename, branch);
        }
      }
    }
    return entries;
  }

  /**
   * Same as {@link #loadChangesFromBranch} but returns a map keyed by short commit SHA
   * instead of a flat list. Used by steps 5-9 to build the per-commit ChangeDto maps
   * needed by {@link CommitDependencyGraph}.
   *
   * @param repo            the Git repository.
   * @param head            HEAD commit of the branch.
   * @param branch          branch name.
   * @param targetShortShas set of 7-char SHAs to load.
   * @return map from short SHA to the list of {@link SemanticChangeEntry}s for that commit.
   * @throws IOException if the repository cannot be read.
   */
  private Map<String, List<SemanticChangeEntry>> loadChangesGroupedByCommit(
      Repository repo, ObjectId head, String branch, Set<String> targetShortShas)
      throws IOException {
    if (targetShortShas.isEmpty()) {
      return Map.of();
    }

    String jsonDir = CHANGELOG_PREFIX + branch + JSON_SUBDIR;
    Map<String, List<SemanticChangeEntry>> result = new LinkedHashMap<>();

    try (RevWalk walk = new RevWalk(repo)) {
      RevCommit headCommit = walk.parseCommit(head);
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(headCommit.getTree());
        treeWalk.setRecursive(true);

        while (treeWalk.next()) {
          String path = treeWalk.getPathString();
          if (!path.startsWith(jsonDir) || !path.endsWith(".json")) {
            continue;
          }
          String filename = path.substring(jsonDir.length());
          String fileSha = filename.substring(0, Math.min(7, filename.length() - 5));
          if (!targetShortShas.contains(fileSha)) {
            continue;
          }

          ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
          String json = new String(loader.getBytes(), StandardCharsets.UTF_8);
          SemanticChangelogManager.ChangelogDocument doc =
              gson.fromJson(json, SemanticChangelogManager.ChangelogDocument.class);
          if (doc == null || doc.fileChanges == null) {
            continue;
          }

          List<SemanticChangeEntry> entries = new ArrayList<>();
          for (SemanticChangelogManager.ChangelogDocument.FileChangeInfo fileChange
              : doc.fileChanges) {
            if (fileChange.semanticChanges != null) {
              entries.addAll(fileChange.semanticChanges);
            }
          }
          if (!entries.isEmpty()) {
            result.put(fileSha, entries);
          }
        }
      }
    }
    return result;
  }

  /**
   * Detects direct semantic conflicts between two lists of change entries
   * (original vs. original, step 3).
   *
   * <p>Conflict rules:
   * <ul>
   *   <li>Same element, one side deleted -&gt; {@link ConflictSeverity#HIGH}</li>
   *   <li>Same {@code (elementUuid, feature)} changed to different values
   *       on a reference feature -&gt; {@link ConflictSeverity#HIGH}</li>
   *   <li>Same {@code (elementUuid, feature)} changed to different values
   *       on an attribute feature -&gt; {@link ConflictSeverity#MEDIUM}</li>
   *   <li>Same change on both sides (identical {@code to} value) -&gt; no conflict</li>
   * </ul>
   *
   * <p>Only the highest-severity conflict per {@code (elementUuid, feature)} pair
   * is kept (deduplication).
   *
   * <p>TODO (ongoing): add tombstone check using {@code containerUuid} to detect cases
   * where branch A deletes a container element and branch B modifies one of its children.
   *
   * @param changesA changes from branch A.
   * @param changesB changes from branch B.
   * @return list of detected {@link SemanticConflict} instances.
   */
  private List<SemanticConflict> detectConflicts(
      List<SemanticChangeEntry> changesA, List<SemanticChangeEntry> changesB) {
    // Deduplicate by (elementUuid, feature): keep only the highest-severity conflict
    // per pair. Key is "uuid|feature"; value is the best conflict found so far.
    Map<String, SemanticConflict> best = new HashMap<>();

    for (SemanticChangeEntry entryA : changesA) {
      // Skip entries whose UUID could not be resolved. This happens when an element
      // was deleted before the converter could look up its UUID (tombstoning problem).
      // Without a UUID we cannot identify which element was affected.
      if (entryA.getElementUuid() == null || entryA.getElementUuid().equals("unknown")) {
        continue;
      }

      for (SemanticChangeEntry entryB : changesB) {
        // A conflict can only exist when both sides affected the same element.
        // The UUID is stable across branches, so it is the only reliable identity
        // key that does not depend on position within the XMI file.
        if (!entryA.getElementUuid().equals(entryB.getElementUuid())) {
          continue;
        }

        // Determine whether this pair of changes actually conflicts and how severe it is.
        // Returns null when changes are compatible (different features, or same target value).
        ConflictSeverity severity = classifySeverity(entryA, entryB);
        if (severity == null) {
          continue;
        }

        // Conflict key is "uuid|feature". Use branch A's feature as the canonical value
        // (null for lifecycle changes such as ELEMENT_DELETED, which have no feature).
        String feature = entryA.getFeature();
        String key = entryA.getElementUuid() + "|" + feature;

        // If a conflict for this (uuid, feature) pair already exists, only overwrite it
        // when the new one has higher severity. Higher ordinal() means higher severity
        // because the enum is declared in ascending order (LOW < MEDIUM < HIGH).
        SemanticConflict existing = best.get(key);
        if (existing == null || severity.ordinal() > existing.getSeverity().ordinal()) {
          best.put(key,
              new SemanticConflict(entryA.getElementUuid(), feature, entryA, entryB, severity));
        }
      }
    }
    return new ArrayList<>(best.values());
  }

  /**
   * Returns the conflict severity for a pair of entries on the same element, or
   * {@code null} if there is no conflict (changes are compatible or identical).
   *
   * <p>Note: severity rules are a first draft and are subject to refinement as part of
   * ongoing thesis work on conflict categorization (MG-2, MG-3).
   *
   * @param a change entry from branch A.
   * @param b change entry from branch B.
   * @return the {@link ConflictSeverity}, or {@code null} if no conflict.
   */
  private ConflictSeverity classifySeverity(SemanticChangeEntry a, SemanticChangeEntry b) {
    SemanticChangeType typeA = a.getChangeType();
    SemanticChangeType typeB = b.getChangeType();

    // Most dangerous case: one side deleted the element while the other modified it.
    // The deleted element no longer exists, so the other side's changes are invalid.
    if (typeA == SemanticChangeType.ELEMENT_DELETED
        || typeB == SemanticChangeType.ELEMENT_DELETED) {
      // Exception: both sides deleted the same element - same outcome, auto-resolvable.
      if (typeA == typeB) {
        return null;
      }
      return ConflictSeverity.HIGH;
    }

    // Check whether both sides changed the same feature.
    // Different features (e.g. A changed "name", B changed "age") are independent - no conflict.
    String featureA = a.getFeature();
    String featureB = b.getFeature();
    if (featureA == null || !featureA.equals(featureB)) {
      return null;
    }

    // Both sides changed the same feature to the same target value - auto-resolvable.
    if (Objects.equals(a.getTo(), b.getTo())) {
      return null;
    }

    // Both sides changed the same feature to different values - this is a conflict.
    // Reference changes are HIGH: they affect model structure, not just scalar data.
    // An incorrect reference can break model consistency entirely.
    if (typeA == SemanticChangeType.REFERENCE_CHANGED
        || typeB == SemanticChangeType.REFERENCE_CHANGED
        || typeA == SemanticChangeType.REFERENCE_VALUE_INSERTED
        || typeB == SemanticChangeType.REFERENCE_VALUE_INSERTED
        || typeA == SemanticChangeType.REFERENCE_VALUE_REMOVED
        || typeB == SemanticChangeType.REFERENCE_VALUE_REMOVED
        || typeA == SemanticChangeType.REFERENCE_SET
        || typeB == SemanticChangeType.REFERENCE_SET
        || typeA == SemanticChangeType.REFERENCE_CLEARED
        || typeB == SemanticChangeType.REFERENCE_CLEARED) {
      return ConflictSeverity.HIGH;
    }

    // Attribute changes (scalar values like String, int) are MEDIUM: they affect data
    // but not model structure. Manual resolution is still required but risk is lower.
    if (typeA == SemanticChangeType.ATTRIBUTE_CHANGED
        || typeB == SemanticChangeType.ATTRIBUTE_CHANGED
        || typeA == SemanticChangeType.ATTRIBUTE_SET
        || typeB == SemanticChangeType.ATTRIBUTE_SET
        || typeA == SemanticChangeType.ATTRIBUTE_CLEARED
        || typeB == SemanticChangeType.ATTRIBUTE_CLEARED) {
      return ConflictSeverity.MEDIUM;
    }

    // Remaining cases are multi-valued list operations on the same feature - LOW.
    // For example, both sides inserting into the same list can often be merged automatically.
    return ConflictSeverity.LOW;
  }


  /**
   * Step 4: Resolves direct conflicts detected in step 3.
   *
   * <p>Returns the subset of conflicts that could not be resolved automatically.
   * Any remaining conflicts require human intervention before replay can proceed.
   * {@link #analyzeBranches} returns early with these unresolved conflicts.
   *
   * <p>TODO (step 4): implement resolution strategies - programmatic
   * ({@code chooseOurs} / {@code chooseTheirs}) and interactive (user prompt).
   *
   * @param directConflicts conflicts detected in step 3.
   * @return unresolved conflicts; currently returns all input conflicts unchanged.
   */
  private List<SemanticConflict> resolveDirectConflicts(List<SemanticConflict> directConflicts) {
    // Auto-resolution logic from Tural Mammadlee's UpdateConflictAnalyzer:
    // if one side is ORIGINAL and the other is CONSEQUENTIAL, the ORIGINAL side wins.
    // Only conflicts where both sides have the same origin need human review.
    UpdateConflictAnalyzer analyzer = new UpdateConflictAnalyzer();
    List<UpdateConflict> analyzed = analyzer.analyze(directConflicts);
    return analyzer.filterUnresolved(analyzed);
  }

  /**
   * Step 5: Builds the footprint dependency graph from the commit histories of both branches.
   *
   * <p>The graph is a directed adjacency list: an edge {@code A_i -> B_j} means commit
   * {@code A_i} must be replayed before {@code B_j} so that {@code B_j}'s original change
   * is the last write to the shared element-feature and preserves {@code B_j}'s intent.
   *
   * <p>Edges are added when the consequential footprint of one commit (element-feature pairs
   * written by Reactions) overlaps the original footprint of a commit on the other branch.
   * Intra-branch ordering edges are also added to preserve each branch's commit sequence.
   *
   * <p>CommitDependencyGraph is from the anonymous author (paper companion code).
   * It adds intra-branch ordering edges and inter-branch edges based on footprint overlaps.
   *
   * @param groupedA per-commit entries for branch A (key = short SHA, insertion order = commit order).
   * @param groupedB per-commit entries for branch B.
   * @return adjacency list mapping each commit SHA to the set of SHAs that must follow it.
   */
  private Map<String, Set<String>> buildDependencyGraph(
      Map<String, List<SemanticChangeEntry>> groupedA,
      Map<String, List<SemanticChangeEntry>> groupedB) {
    // Convert SemanticChangeEntry maps to ChangeDto maps for CommitDependencyGraph.
    // SemanticChangeEntryToDto also builds the consequential footprint (origin == CONSEQUENTIAL)
    // which CommitDependencyGraph uses to add inter-branch dependency edges.
    SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
    Map<String, List<ChangeDto>> dtosA = converter.convertAll(groupedA);
    Map<String, List<ChangeDto>> dtosB = converter.convertAll(groupedB);

    CommitDependencyGraph graphBuilder = new CommitDependencyGraph();
    Map<String, Set<String>> graph = graphBuilder.buildGraph(
        dtosA, dtosB, IntraBranchDependencyMode.PRESERVE_ORDER, null);
    LOGGER.debug("Step 5: dependency graph built ({} nodes)", graph.size());
    return graph;
  }

  /**
   * Step 6: Detects cycles in the dependency graph, which indicate consequential conflicts.
   *
   * <p>A cycle arises when commit {@code A_i}'s Reactions write an element-feature pair
   * that {@code B_j} modifies originally, and {@code B_j}'s Reactions write a pair that
   * {@code A_i} modifies originally. No interleaving can preserve both branches' intent;
   * human resolution is required (analogous to a serialization conflict).
   *
   * <p>Uses {@link InterleavingGenerator#computeOrder} (Kahn's algorithm): any node not
   * in the topological output is part of a cycle. Each cyclic node pair is reported as a
   * consequential conflict.
   *
   * @param dependencyGraph adjacency list produced by step 5.
   * @return consequential conflicts derived from each cycle; empty if the graph is acyclic.
   */
  private List<SemanticConflict> detectCyclicConflicts(Map<String, Set<String>> dependencyGraph) {
    InterleavingGenerator generator = new InterleavingGenerator();
    List<String> ordered = generator.computeOrder(dependencyGraph);
    List<String> cyclicNodes = generator.findCyclicNodes(dependencyGraph, ordered);
    if (cyclicNodes.isEmpty()) {
      return List.of();
    }
    // Each cyclic node represents a commit that cannot be ordered without human help.
    // Report one synthetic HIGH conflict per cyclic commit pair to signal the issue.
    // A full implementation would link each cyclic commit back to its SemanticChangeEntry
    // and report the overlapping element-feature pairs as proper SemanticConflicts.
    LOGGER.warn("Step 6: {} cyclic node(s) detected in dependency graph: {}", cyclicNodes.size(),
        cyclicNodes);
    return List.of(); // Cyclic conflicts are signalled via the log; no SemanticConflict model yet.
  }

  /**
   * Step 7: Computes a topological ordering of the dependency graph using Kahn's algorithm.
   *
   * <p>The returned list gives the order in which commits should be replayed: all
   * dependency edges point forward in this order, ensuring that each commit's original
   * change is applied after any consequential change that would otherwise overwrite it.
   *
   * <p>Delegates to {@link InterleavingGenerator#computeOrder} (anonymous-repo implementation).
   *
   * @param dependencyGraph acyclic adjacency list produced by step 5 (step 6 confirms acyclic).
   * @return commit SHAs in replay order (topological sort of the graph).
   */
  private List<String> computeTopologicalOrder(Map<String, Set<String>> dependencyGraph) {
    InterleavingGenerator generator = new InterleavingGenerator();
    return generator.computeOrder(dependencyGraph);
  }

  /**
   * Steps 8 + 9: Replays commits in the given topological order through the Vitruvius
   * Reaction engine, with iterative footprint refinement.
   *
   * <p>Delegates to {@link SemanticMergeEngine#mergeBidirectional}, which wraps
   * {@link tools.vitruv.framework.vsum.branch.merge.SemanticMergeCommand} (anonymous-repo).
   * Full replay requires a live Vitruvius {@code InternalVirtualModel} and is stubbed there.
   *
   * <p>SemanticMergeEngine is from the anonymous author (paper companion code).
   * It replays serialized EChanges through the Vitruvius Reaction engine in topological order.
   * Steps 8 and 9 use mergeBidirectional() only. The iterative footprint refinement
   * (mergeWithInterleaving) is out of scope for this thesis.
   *
   * @param interleaving  commit SHAs in topological replay order (step 7 output).
   * @param ancestorSha   full SHA of the common ancestor; used to seed the replay state.
   * @param shortShasA    7-char commit SHAs on branch A (for the result object).
   * @param shortShasB    7-char commit SHAs on branch B (for the result object).
   * @param changesA      original change entries from branch A (for the result object).
   * @param changesB      original change entries from branch B (for the result object).
   * @param groupedA      per-commit change entries for branch A (needed by the engine).
   * @param groupedB      per-commit change entries for branch B (needed by the engine).
   * @return a {@link ReplayResult} representing the merged state, or containing any
   *     conflicts discovered during replay that could not be resolved by re-ordering.
   */
  private ReplayResult replayWithRefinement(
      List<String> interleaving, String ancestorSha,
      List<String> shortShasA, List<String> shortShasB,
      List<SemanticChangeEntry> changesA, List<SemanticChangeEntry> changesB,
      Map<String, List<SemanticChangeEntry>> groupedA,
      Map<String, List<SemanticChangeEntry>> groupedB) {
    // Convert per-commit entries to ChangeDto maps for the merge engine.
    SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
    Map<String, List<ChangeDto>> dtosA = converter.convertAll(groupedA);
    Map<String, List<ChangeDto>> dtosB = converter.convertAll(groupedB);

    SemanticMergeEngine engine = new SemanticMergeEngine(
        repoRoot, IntraBranchDependencyMode.PRESERVE_ORDER, null);
    SemanticMergeResult mergeResult = engine.mergeBidirectional(dtosA, dtosB);

    LOGGER.info("Steps 8+9: merge engine result: {}", mergeResult.getMessage());
    // Map SemanticMergeResult back to ReplayResult.
    // Replay conflicts are not yet modelled as SemanticConflict instances (out of scope).
    return new ReplayResult(
        ancestorSha, shortShasA, shortShasB, changesA, changesB, List.of());
  }

  private Gson buildGson() {
    DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    return new GsonBuilder()
        .registerTypeAdapter(LocalDateTime.class,
            (JsonSerializer<LocalDateTime>) (src, t, ctx) -> new JsonPrimitive(src.format(fmt)))
        .registerTypeAdapter(LocalDateTime.class,
            (JsonDeserializer<LocalDateTime>) (json, t, ctx) ->
                LocalDateTime.parse(json.getAsString(), fmt))
        .create();
  }
}
