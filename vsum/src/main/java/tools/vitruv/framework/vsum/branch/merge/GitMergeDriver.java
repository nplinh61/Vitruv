package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import tools.vitruv.framework.vsum.branch.exception.BranchOperationException;

/**
 * High-level entry point that drives a full semantic merge for two Git branches.
 *
 * <p>Loads the per-commit {@link ChangeDto} lists from the Git object store using
 * {@link ChangeDtoDeserializer}, then delegates to {@link SemanticMergeCommand} to
 * run the graph-based merge pipeline.
 *
 * <p>This class is the anonymous-repo's top-level merge driver, analogous to
 * {@link tools.vitruv.framework.vsum.branch.SemanticConflictDetector} in the thesis
 * codebase. In the thesis integration, {@link SemanticConflictDetector} calls into
 * the merge package via {@link SemanticMergeCommand} directly; {@link GitMergeDriver}
 * is provided here for completeness.
 */
public class GitMergeDriver {

  private static final Logger LOGGER = LogManager.getLogger(GitMergeDriver.class);
  private static final String CHANGELOG_PREFIX = ".vitruvius/changelogs/";
  private static final String JSON_SUBDIR = "/json/";

  private final Path repositoryRoot;
  private final ChangeDtoDeserializer deserializer;

  /**
   * Creates a new {@link GitMergeDriver} for the repository at the given root.
   *
   * @param repositoryRoot root of the Git repository; must not be null.
   */
  public GitMergeDriver(Path repositoryRoot) {
    this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
    this.deserializer = new ChangeDtoDeserializer();
  }

  /**
   * Runs the semantic merge pipeline for two branches.
   *
   * <p>Reads JSON changelogs for each branch from the Git object store, builds the
   * dependency graph, topologically sorts it, and returns the merge result.
   *
   * @param branchA name of the first branch.
   * @param branchB name of the second branch.
   * @param mode    intra-branch ordering mode.
   * @return the {@link SemanticMergeResult} from the pipeline.
   * @throws BranchOperationException if the repository cannot be opened or a branch is missing.
   */
  public SemanticMergeResult merge(String branchA, String branchB, IntraBranchDependencyMode mode)
      throws BranchOperationException {
    Objects.requireNonNull(branchA, "branchA must not be null");
    Objects.requireNonNull(branchB, "branchB must not be null");
    Objects.requireNonNull(mode, "mode must not be null");

    try (Git git = Git.open(repositoryRoot.toFile())) {
      ObjectId headA = git.getRepository().resolve("refs/heads/" + branchA);
      ObjectId headB = git.getRepository().resolve("refs/heads/" + branchB);
      if (headA == null) {
        throw new BranchOperationException("Branch not found: " + branchA);
      }
      if (headB == null) {
        throw new BranchOperationException("Branch not found: " + branchB);
      }

      Map<String, List<ChangeDto>> commitsA = loadChangeDtos(git, headA, branchA);
      Map<String, List<ChangeDto>> commitsB = loadChangeDtos(git, headB, branchB);

      LOGGER.info("GitMergeDriver: loaded {} commit(s) for '{}', {} for '{}'",
          commitsA.size(), branchA, commitsB.size(), branchB);

      MergeTracer tracer = new MergeTracer();
      ConflictResolutionProvider provider = conflict -> ConflictResolution.ABORT;
      SemanticMergeCommand command = new SemanticMergeCommand(
          repositoryRoot, commitsA, commitsB, mode, provider, tracer);
      return command.execute();

    } catch (IOException e) {
      throw new BranchOperationException(
          "Failed to open repository for merge: " + e.getMessage(), e);
    }
  }

  /**
   * Loads all JSON changelog files for a branch from the Git object store and converts
   * each entry to a {@link ChangeDto}.
   */
  private Map<String, List<ChangeDto>> loadChangeDtos(Git git, ObjectId head, String branch)
      throws IOException {
    String jsonDir = CHANGELOG_PREFIX + branch + JSON_SUBDIR;
    Map<String, List<ChangeDto>> result = new LinkedHashMap<>();
    com.google.gson.Gson gson = new com.google.gson.Gson();

    try (RevWalk revWalk = new RevWalk(git.getRepository())) {
      RevCommit headCommit = revWalk.parseCommit(head);
      try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
        treeWalk.addTree(headCommit.getTree());
        treeWalk.setRecursive(true);

        while (treeWalk.next()) {
          String path = treeWalk.getPathString();
          if (!path.startsWith(jsonDir) || !path.endsWith(".json")) {
            continue;
          }
          ObjectLoader loader = git.getRepository().open(treeWalk.getObjectId(0));
          String json = new String(loader.getBytes(), StandardCharsets.UTF_8);
          tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument doc =
              gson.fromJson(json,
                  tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.class);
          if (doc != null) {
            Map<String, List<ChangeDto>> docDtos = deserializer.deserialize(doc);
            result.putAll(docDtos);
          }
        }
      }
    }
    return result;
  }
}
