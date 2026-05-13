package tools.vitruv.framework.vsum.branch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import tools.vitruv.framework.vsum.branch.data.BranchMetadata;
import tools.vitruv.framework.vsum.branch.data.BranchState;
import tools.vitruv.framework.vsum.branch.data.ModelMergeResult;
import tools.vitruv.framework.vsum.branch.data.ReplayResult;
import tools.vitruv.framework.vsum.branch.data.ValidationResult;
import tools.vitruv.framework.vsum.branch.exception.BranchOperationException;
import tools.vitruv.framework.vsum.branch.handler.PostMergeHandler;
import tools.vitruv.framework.vsum.branch.data.SemanticConflict;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolution;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolutionProvider;
import tools.vitruv.framework.vsum.branch.merge.GitStateLoader;
import tools.vitruv.framework.vsum.branch.merge.MergeConflict;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeEngine;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;
import tools.vitruv.framework.vsum.branch.util.MergeResultFile;
import tools.vitruv.framework.vsum.branch.util.MergeTriggerFile;

/**
 * Manages Git merge operations for Vitruvius model branches.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Perform three-way merge of a source branch into the current branch (MG-5)</li>
 *   <li>Detect fast-forward vs non-fast-forward merges</li>
 *   <li>Return a {@link ModelMergeResult} describing the outcome including conflicts</li>
 *   <li>Mark the source branch as MERGED on success (BR-5, BR-9)</li>
 *   <li>Optionally delete the source branch after successful merge (BR-5)</li>
 *   <li>Write the merge trigger file so VsumMergeWatcher validates the merged state</li>
 * </ul>
 */
public class MergeManager {

  private static final Logger LOGGER = LogManager.getLogger(MergeManager.class);
  private static final String METADATA_DIR = ".vitruvius/branches";

  private final Path repoRoot;
  private final MergeTriggerFile mergeTriggerFile;
  private boolean restMode = false;

  /**
   * Called after every successful merge to reload the V-SUM in-memory state from the
   * merged working tree. Default is a no-op (CLI mode); set via
   * {@link #setPostMergeReload(Runnable)} when running inside the REST server.
   */
  private Runnable postMergeReload = () -> {};

  /**
   * Optional handler that copies the source branch V-SUM state into the target branch
   * before the reload, so the target V-SUM knows about resources introduced on the source
   * branch. Null in CLI mode (the watcher handles this); set via
   * {@link #setPostMergeHandler(PostMergeHandler)} in REST mode.
   */
  private PostMergeHandler postMergeHandler;

  /**
   * Optional merge engine for steps 5-9 of the replay pipeline. {@code null} means only
   * steps 1-3 (direct conflict detection) are performed in the semantic pre-check.
   * Set via {@link #setMergeEngine(SemanticMergeEngine)} before calling {@link #merge}.
   *
   * <p>When {@link #conflictResolutionProvider} is also set, this engine is additionally
   * called to auto-resolve semantic conflicts instead of returning CONFLICTING immediately.
   * The engine must be constructed with a matching {@link ConflictResolutionProvider}.
   */
  private SemanticMergeEngine mergeEngine;

  /**
   * When non-null, semantic conflicts trigger auto-resolution via {@link #mergeEngine}
   * rather than returning {@link tools.vitruv.framework.vsum.branch.data.ModelMergeResult.MergeStatus#CONFLICTING}.
   * Both this field and {@link #mergeEngine} must be set for auto-resolution to activate.
   * Set via {@link #setConflictResolutionProvider(ConflictResolutionProvider)}.
   */
  private ConflictResolutionProvider conflictResolutionProvider;

  /**
   * Creates a new MergeManager for the Git repository at the given path.
   *
   * @param repoRoot the root directory of the Git repository.
   *
   * @throws IllegalArgumentException if the path is not a valid Git repository.
   */
  public MergeManager(Path repoRoot) {
    this.repoRoot = checkNotNull(repoRoot, "repository root must not be null");
    checkArgument(Files.isDirectory(repoRoot.resolve(".git")),
        "No Git repository found at: %s", repoRoot);
    this.mergeTriggerFile = new MergeTriggerFile(repoRoot);
  }

  /**
   * Disables writing the merge trigger file. Call this when running inside the REST
   * server, where {@code VsumMergeWatcher} is not active.
   */
  public void suppressTriggerFile() {
    this.restMode = true;
  }

  /**
   * Sets a callback that is invoked after every successful merge to reload the V-SUM
   * in-memory state from the merged working tree. Pass {@code branchModel::reload}
   * from the REST server so the merged files are immediately visible through the API.
   *
   * @param callback the reload runnable, must not be null.
   */
  public void setPostMergeReload(Runnable callback) {
    this.postMergeReload = checkNotNull(callback, "postMergeReload callback must not be null");
  }

  /**
   * Sets the handler used to copy the source branch V-SUM state into the target branch
   * before the post-merge reload. Required in REST mode so that resources introduced
   * on the source branch (and unknown to the target branch V-SUM) become visible after
   * the merge. Only called for conflict-free merges (SUCCESS and FAST_FORWARD).
   *
   * @param handler the post-merge handler, must not be null.
   */
  public void setPostMergeHandler(PostMergeHandler handler) {
    this.postMergeHandler = checkNotNull(handler, "postMergeHandler must not be null");
  }

  /**
   * Attaches a {@link SemanticMergeEngine} so that steps 5-9 of the replay pipeline run
   * during the semantic pre-check when no direct conflicts are found. Optional: when not
   * set, the pre-check performs steps 1-3 only (direct conflict detection).
   *
   * @param engine the merge engine to use; must not be null.
   */
  public void setMergeEngine(SemanticMergeEngine engine) {
    this.mergeEngine = checkNotNull(engine, "mergeEngine must not be null");
  }

  /**
   * Enables auto conflict resolution. When set, semantic conflicts trigger
   * {@link SemanticMergeEngine#mergeBidirectional} instead of returning CONFLICTING.
   * Requires {@link #setMergeEngine(SemanticMergeEngine)} to also be called with an
   * engine constructed with the same provider.
   *
   * @param provider the resolution strategy; must not be null.
   */
  public void setConflictResolutionProvider(ConflictResolutionProvider provider) {
    this.conflictResolutionProvider = checkNotNull(provider, "provider must not be null");
  }

  /**
   * Clears the conflict resolution provider so that subsequent merge calls return
   * {@link tools.vitruv.framework.vsum.branch.data.ModelMergeResult.MergeStatus#CONFLICTING}
   * instead of auto-resolving. Call this after a per-request resolution to restore the
   * default blocking behaviour.
   */
  public void clearConflictResolutionProvider() {
    this.conflictResolutionProvider = null;
  }

  /**
   * Probes a {@link ConflictResolutionProvider} with a single dummy conflict to determine
   * the dominant JGit merge strategy (THEIRS or OURS). Used when a resolution strategy
   * was supplied by the caller before any actual conflicts have been analysed.
   */
  private static MergeStrategy jgitStrategyFor(ConflictResolutionProvider provider) {
    List<ConflictResolution> probe = provider.resolve(
        List.of(new MergeConflict("_", MergeConflict.ConflictType.MODIFY_MODIFY,
                "_", null, null, null, null)));
    if (!probe.isEmpty() && probe.get(0).choice() == ConflictResolution.Choice.THEIRS) {
      return MergeStrategy.THEIRS;
    }
    return MergeStrategy.OURS;
  }

  /**
   * Merges the given source branch into the current branch using a three-way merge
   * (MG-5).
   *
   * <p>On success: marks source branch as MERGED, writes merge trigger for validation.
   *
   * <p>On fast-forward: same as success but no merge commit is created.
   *
   * <p>On conflict: returns {@link ModelMergeResult.MergeStatus#CONFLICTING} with the
   * list of conflicting files. The developer must resolve manually and commit.
   *
   * @param sourceBranch the name of the branch to merge into the current branch.
   * @return a {@link ModelMergeResult} describing the outcome.
   *
   * @throws BranchOperationException if the source branch does not exist or the
   *     repository cannot be opened.
   */
  public ModelMergeResult merge(String sourceBranch) throws BranchOperationException {
    return merge(sourceBranch, false);
  }

  /**
   * Merges the given source branch into the current branch, with an option to
   * automatically delete the source branch after a successful merge (BR-5).
   *
   * @param sourceBranch     the name of the branch to merge into the current branch.
   * @param deleteAfterMerge whether to delete the source branch after success.
   * @return a {@link ModelMergeResult} describing the outcome.
   *
   * @throws BranchOperationException if the source branch does not exist or the
   *     repository cannot be opened.
   */
  public ModelMergeResult merge(String sourceBranch, boolean deleteAfterMerge)
      throws BranchOperationException {
    checkNotNull(sourceBranch, "source branch must not be null");
    checkArgument(!sourceBranch.isBlank(), "source branch must not be blank");

    try (Git git = Git.open(repoRoot.toFile())) {
      Repository repo = git.getRepository();
      String targetBranch = repo.getBranch();
      LOGGER.info("Merging '{}' into '{}'", sourceBranch, targetBranch);

      Ref sourceRef = repo.findRef("refs/heads/" + sourceBranch);
      if (sourceRef == null) {
        throw new BranchOperationException(
            "Source branch does not exist: " + sourceBranch);
      }
      if (sourceBranch.equals(targetBranch)) {
        throw new BranchOperationException(
            "Cannot merge a branch into itself: " + sourceBranch);
      }

      // When a resolution strategy is supplied the caller is explicitly asking to retry a
      // conflicting merge. The previous attempt may have left MERGE_HEAD written. Abort
      // that state before starting fresh so JGit does not refuse to run a new merge.
      if (conflictResolutionProvider != null) {
        try {
          git.reset().setMode(ResetCommand.ResetType.HARD).call();
        } catch (GitAPIException resetEx) {
          LOGGER.debug("Pre-resolution hard-reset failed (non-critical): {}",
              resetEx.getMessage());
        }
        java.nio.file.Files.deleteIfExists(repoRoot.resolve(".git/MERGE_HEAD"));
      }

      // Fast-forward detection: compare branch tips without touching file content.
      // This replaces the implicit FF detection that was previously bundled into git.merge().
      try (RevWalk ffWalk = new RevWalk(repo)) {
        RevCommit oursCommit   = ffWalk.parseCommit(repo.resolve("HEAD"));
        RevCommit theirsCommit = ffWalk.parseCommit(sourceRef.getObjectId());

        if (ffWalk.isMergedInto(theirsCommit, oursCommit)) {
          String headSha = repo.resolve("HEAD").getName();
          LOGGER.info("Already up to date (HEAD: {})", headSha.substring(0, 7));
          return ModelMergeResult.fastForward(sourceBranch, targetBranch, headSha);
        }

        if (ffWalk.isMergedInto(oursCommit, theirsCommit)) {
          git.merge()
              .include(sourceRef)
              .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.FF_ONLY)
              .call();
          String sha = sourceRef.getObjectId().getName();
          LOGGER.info("Fast-forward merge completed, new HEAD: {}", sha.substring(0, 7));
          ModelMergeResult ff = ModelMergeResult.fastForward(sourceBranch, targetBranch, sha);
          markAsMerged(git, sourceBranch);
          copyChangelogsFromSourceToTarget(git, sourceBranch, targetBranch);
          writeMergeTrigger(ff, sourceBranch, targetBranch);
          if (postMergeHandler != null) {
            postMergeHandler.copyVsumFromSourceBranch(sourceBranch, targetBranch);
          }
          postMergeReload.run();
          if (deleteAfterMerge) {
            deleteSourceBranch(git, sourceBranch);
          }
          return ff;
        }
      }

      // Non-fast-forward: run semantic pre-check before touching any file content.
      List<String> semanticConflicts = runSemanticPreCheck(sourceBranch, targetBranch);
      if (!semanticConflicts.isEmpty()) {
        if (conflictResolutionProvider != null && mergeEngine != null) {
          return resolveWithEngine(
              git, sourceBranch, targetBranch, sourceRef, repo, deleteAfterMerge);
        }
        LOGGER.warn(
            "Semantic pre-check detected {} conflict(s) -- blocking merge of '{}' into '{}'",
            semanticConflicts.size(), sourceBranch, targetBranch);
        return ModelMergeResult.conflicting(sourceBranch, targetBranch, semanticConflicts);
      }

      // Primary merge path: engine replay, consistent with GitMergeDriver (CLI path).
      // JGit text merge is skipped entirely so XMI files are never written with <<<<<<< markers.
      // MERGE_HEAD is set manually so the resulting commit has two parents.
      if (mergeEngine != null && conflictResolutionProvider == null) {
        LOGGER.info("No semantic conflicts -- routing to engine replay (primary merge path)");
        repo.writeMergeHeads(List.of(sourceRef.getObjectId()));
        return mergeCleanWithEngineReplay(
            git, sourceBranch, targetBranch, sourceRef, repo, deleteAfterMerge);
      }

      // Fallback: provider set with no semantic conflicts (S2 -- state-based diff rename
      // that bypasses the changelog-based pre-check) or no engine configured at all.
      // The engine cannot handle renames not captured as EChanges; JGit file-level
      // OURS/THEIRS is the appropriate tool for this atypical case.
      MergeStrategy jgitStrategy = conflictResolutionProvider != null
          ? jgitStrategyFor(conflictResolutionProvider)
          : MergeStrategy.RECURSIVE;
      String mergeMessage = conflictResolutionProvider != null
          ? "Merge branch '" + sourceBranch + "' into '" + targetBranch
              + "' [auto-resolved:" + jgitStrategy.getName() + "]"
          : "Merge branch '" + sourceBranch + "' into '" + targetBranch + "'";

      MergeResult jgitResult = git.merge()
          .include(sourceRef)
          .setStrategy(jgitStrategy)
          .setCommit(true)
          .setMessage(mergeMessage)
          .call();

      ModelMergeResult result = buildResult(jgitResult, sourceBranch, targetBranch, repo);

      if (!result.isSuccessful()
          || result.getStatus() == ModelMergeResult.MergeStatus.CONFLICTING) {
        writeMergeMetadataDirectly(result, sourceBranch, targetBranch);
      }
      LOGGER.info("Merge result: {}", result);

      if (result.isSuccessful()) {
        markAsMerged(git, sourceBranch);
        copyChangelogsFromSourceToTarget(git, sourceBranch, targetBranch);
        writeMergeTrigger(result, sourceBranch, targetBranch);
        if (postMergeHandler != null) {
          postMergeHandler.copyVsumFromSourceBranch(sourceBranch, targetBranch);
        }
        postMergeReload.run();
        if (deleteAfterMerge) {
          deleteSourceBranch(git, sourceBranch);
        }
      }
      return result;

    } catch (GitAPIException e) {
      throw new BranchOperationException("Merge failed: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new BranchOperationException(
          "Failed to open repository: " + e.getMessage(), e);
    }
  }

  /**
   * Runs the semantic conflict pre-check for the proposed merge.
   *
   * <p>Creates a lightweight {@link SemanticConflictDetector} (steps 1–3 only) and
   * compares the semantic changelogs of both branches. Returns a list of conflict
   * descriptors in the form {@code "semantic://elementUuid/feature"} for each detected
   * direct conflict. Returns an empty list if no conflicts are found or if the check
   * cannot be performed (e.g. no changelogs exist yet).
   *
   * @param sourceBranch the branch being merged in.
   * @param targetBranch the branch receiving the merge.
   * @return conflict descriptors, or an empty list when the merge may proceed.
   */
  private List<String> runSemanticPreCheck(String sourceBranch, String targetBranch) {
    try {
      SemanticConflictDetector detector = new SemanticConflictDetector(repoRoot, mergeEngine);
      ReplayResult analysis = detector.analyzeBranches(targetBranch, sourceBranch);
      if (!analysis.hasConflicts()) {
        return List.of();
      }
      return analysis.getConflicts().stream()
          .map(c -> "semantic://" + c.getElementUuid() + "/"
              + (c.getFeature() != null ? c.getFeature() : "lifecycle"))
          .distinct()
          .toList();
    } catch (BranchOperationException e) {
      LOGGER.debug("Semantic pre-check skipped (non-fatal): {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Writes merge metadata directly when merge is triggered via API, since the conflict
   * file list is available from JGit but would be lost if passed through the trigger
   * file / watcher path. The watcher path (hook-triggered) writes its own metadata via
   * PostMergeHandler.
   *
   * @param result       the merge result.
   * @param sourceBranch the branch that was merged in.
   * @param targetBranch the branch that received the merge.
   */
  private void writeMergeMetadataDirectly(
      ModelMergeResult result, String sourceBranch, String targetBranch) {
    try {
      MergeResultFile resultFile = new MergeResultFile(repoRoot);
      String sha = result.getMergeCommitSha() != null
          ? result.getMergeCommitSha()
          : "no-commit-" + System.currentTimeMillis();
      ValidationResult validationResult = result.isSuccessful()
          ? ValidationResult.success()
          : ValidationResult.failure(List.of(
              "Merge resulted in conflicts: "
                  + String.join(", ", result.getConflictingFiles())));
      resultFile.writeMetadata(
          sha, sourceBranch, targetBranch, validationResult, result.getConflictingFiles());
      LOGGER.info("Merge metadata written directly for commit {}",
          sha.substring(0, Math.min(7, sha.length())));
    } catch (IOException e) {
      LOGGER.warn("Failed to write merge metadata directly (non-critical): {}",
          e.getMessage());
    }
  }

  /**
   * Translates a JGit {@link MergeResult} into a {@link ModelMergeResult}.
   *
   * @param jgitResult   the raw JGit merge result.
   * @param sourceBranch the branch that was merged in.
   * @param targetBranch the branch that received the merge.
   * @param repo         the Git repository, used to resolve HEAD for ALREADY_UP_TO_DATE.
   * @return the translated {@link ModelMergeResult}.
   *
   * @throws IOException if the repository cannot be read.
   */
  private ModelMergeResult buildResult(
      MergeResult jgitResult, String sourceBranch, String targetBranch,
      Repository repo) throws IOException {
    switch (jgitResult.getMergeStatus()) {

      case ALREADY_UP_TO_DATE:
        {
        ObjectId head = repo.resolve("HEAD");
        String sha = head != null ? head.getName() : "";
        LOGGER.info("Already up to date; treating as fast-forward (HEAD: {})",
            sha.substring(0, Math.min(7, sha.length())));
        return ModelMergeResult.fastForward(sourceBranch, targetBranch, sha);
        }

      case FAST_FORWARD:
      case FAST_FORWARD_SQUASHED:
        {
        ObjectId newHead = jgitResult.getNewHead();
        String sha = newHead != null ? newHead.getName() : "";
        LOGGER.info("Fast-forward merge completed, new HEAD: {}",
            sha.substring(0, Math.min(7, sha.length())));
        return ModelMergeResult.fastForward(sourceBranch, targetBranch, sha);
        }

      case MERGED:
      case MERGED_SQUASHED:
      case MERGED_NOT_COMMITTED:
        {
        ObjectId newHead = jgitResult.getNewHead();
        String sha = newHead != null ? newHead.getName() : "";
        LOGGER.info("Merge commit created: {}", sha.substring(0, Math.min(7, sha.length())));
        return ModelMergeResult.success(sourceBranch, targetBranch, sha);
        }

      case CONFLICTING:
        {
        // getConflicts() returns Map<String, int[][]> only need the file paths
        Map<String, int[][]> conflicts =
            jgitResult.getConflicts() != null ? jgitResult.getConflicts() : Map.of();
        // TODO: MG-2 conflict classification
        // TODO: MG-3 conflict priority assignment
        // TODO: MG-8 conflict resolution modes
        List<String> conflictingFiles = new ArrayList<>(conflicts.keySet());
        LOGGER.warn("Merge resulted in {} conflict(s): {}",
            conflictingFiles.size(), conflictingFiles);
        return ModelMergeResult.conflicting(sourceBranch, targetBranch, conflictingFiles);
        }

      case ABORTED:
      case CHECKOUT_CONFLICT:
      case FAILED:
      default:
        {
        // getFailingPaths() returns Map<String, MergeFailureReason> for CHECKOUT_CONFLICT
        String reason = jgitResult.getMergeStatus().toString();
        if (jgitResult.getFailingPaths() != null
            && !jgitResult.getFailingPaths().isEmpty()) {
          String failingFiles = String.join(", ", jgitResult.getFailingPaths().keySet());
          reason += " - failing paths: " + failingFiles;
        }
        LOGGER.error("Merge failed with status: {}", reason);
        return ModelMergeResult.failed(sourceBranch, targetBranch, reason);
        }
    }
  }

  /**
   * Marks the source branch metadata state as MERGED (BR-9) and commits the updated
   * metadata file so the status is persisted in git.
   * Non-fatal if the metadata file does not exist or the commit fails.
   *
   * @param git          the open Git instance for the current repository.
   * @param sourceBranch the name of the branch to mark as merged.
   */
  private void markAsMerged(Git git, String sourceBranch) {
    Path metadataFile =
        repoRoot.resolve(METADATA_DIR).resolve(sourceBranch + ".metadata");

    if (!Files.exists(metadataFile)) {
      LOGGER.debug("No metadata file for '{}', skipping MERGED status update", sourceBranch);
      return;
    }
    try {
      BranchMetadata metadata = BranchMetadata.readFrom(metadataFile);
      metadata.setState(BranchState.MERGED);
      metadata.writeTo(metadataFile);

      String relativePath = repoRoot.relativize(metadataFile).toString().replace('\\', '/');
      git.add().addFilepattern(relativePath).call();
      git.commit()
          .setMessage("[vitruvius] Branch '" + sourceBranch + "' marked as MERGED"
              + "\n\nVitruvius-System: true")
          .setNoVerify(true)
          .call();
      LOGGER.info("Branch '{}' marked as MERGED", sourceBranch);
    } catch (IOException | org.eclipse.jgit.api.errors.GitAPIException e) {
      LOGGER.warn("Failed to mark branch '{}' as MERGED (non-critical): {}",
          sourceBranch, e.getMessage());
    }
  }

  /**
   * Copies changelog JSON files from the source branch's git tree into the target branch's
   * changelog directory on disk and commits them. This makes the merged branch's history
   * accessible when listing changelogs for the target branch via the REST API.
   * Non-fatal if the copy or commit fails.
   *
   * @param git          the open Git instance.
   * @param sourceBranch the branch that was merged in.
   * @param targetBranch the branch that received the merge.
   */
  private void copyChangelogsFromSourceToTarget(Git git, String sourceBranch, String targetBranch) {
    try {
      Repository repo = git.getRepository();
      ObjectId sourceHead = repo.resolve(sourceBranch);
      if (sourceHead == null) {
        LOGGER.debug("Source branch '{}' not found for changelog copy", sourceBranch);
        return;
      }

      String sourceJsonPrefix = ".vitruvius/changelogs/" + sourceBranch + "/json/";
      Path targetJsonDir = repoRoot.resolve(".vitruvius/changelogs")
          .resolve(targetBranch).resolve("json");
      Files.createDirectories(targetJsonDir);

      List<Path> copiedFiles = new ArrayList<>();
      try (RevWalk rw = new RevWalk(repo);
           TreeWalk treeWalk = new TreeWalk(repo)) {
        RevCommit sourceCommit = rw.parseCommit(sourceHead);
        treeWalk.addTree(sourceCommit.getTree());
        treeWalk.setRecursive(true);
        while (treeWalk.next()) {
          String path = treeWalk.getPathString();
          if (!path.startsWith(sourceJsonPrefix) || !path.endsWith(".json")) {
            continue;
          }
          String fileName = path.substring(sourceJsonPrefix.length());
          Path targetFile = targetJsonDir.resolve(fileName);
          if (!Files.exists(targetFile)) {
            ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
            Files.write(targetFile, loader.getBytes());
            copiedFiles.add(targetFile);
          }
        }
      }

      if (!copiedFiles.isEmpty()) {
        for (Path file : copiedFiles) {
          String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
          git.add().addFilepattern(relativePath).call();
        }
        git.commit()
            .setMessage("[vitruvius] Import changelogs from '" + sourceBranch + "' after merge"
                + "\n\nVitruvius-System: true")
            .setNoVerify(true)
            .call();
        LOGGER.info("Copied {} changelog(s) from '{}' to '{}'",
            copiedFiles.size(), sourceBranch, targetBranch);
      }
    } catch (Exception e) {
      LOGGER.warn("Failed to copy changelogs from '{}' to '{}' (non-critical): {}",
          sourceBranch, targetBranch, e.getMessage());
    }
  }

  /**
   * Writes the merge trigger file so VsumMergeWatcher picks up and validates the merged
   * state. Non-fatal if writing fails - the merge has already completed successfully.
   *
   * @param result       the successful merge result.
   * @param sourceBranch the branch that was merged in.
   * @param targetBranch the branch that received the merge.
   */
  private void writeMergeTrigger(
      ModelMergeResult result, String sourceBranch, String targetBranch) {
    if (restMode) {
      LOGGER.debug("REST mode -- skipping merge trigger file");
      return;
    }
    try {
      String sha = result.getMergeCommitSha() != null
          ? result.getMergeCommitSha()
          : "fast-forward";
      mergeTriggerFile.createTrigger(sha, sourceBranch, targetBranch);
      LOGGER.debug("Merge trigger written for VsumMergeWatcher");
    } catch (IOException e) {
      LOGGER.warn("Failed to write merge trigger (non-critical): {}", e.getMessage());
    }
  }

  /**
   * Deletes the source branch from Git after a successful merge (BR-5).
   * Non-fatal if deletion fails - the merge has already completed.
   *
   * @param git          the open Git instance.
   * @param sourceBranch the branch to delete.
   */
  private void deleteSourceBranch(Git git, String sourceBranch) {
    try {
      git.branchDelete().setBranchNames(sourceBranch).setForce(false).call();
      LOGGER.info("Source branch '{}' deleted after merge (BR-5)", sourceBranch);
    } catch (GitAPIException e) {
      LOGGER.warn("Failed to delete source branch '{}' after merge (non-critical): {}",
          sourceBranch, e.getMessage());
    }
  }

  /**
   * Primary merge path for three-way non-fast-forward merges with no detected semantic
   * conflicts. Uses {@link SemanticMergeEngine#merge} to replay EChanges from both branches
   * onto the common base VSUM state, then copies the resulting model files to the working
   * directory and commits them as a proper two-parent merge commit.
   *
   * <p>MERGE_HEAD must already be set to {@code sourceRef} before calling this method.
   * This mirrors the behaviour of {@link tools.vitruv.framework.vsum.branch.merge.GitMergeDriver}
   * (the CLI entry point), ensuring that both the REST and CLI paths produce merge results
   * through the same semantic engine without JGit ever writing XMI conflict markers.
   */
  private ModelMergeResult mergeCleanWithEngineReplay(
      Git git, String sourceBranch, String targetBranch,
      Ref sourceRef, Repository repo, boolean deleteAfterMerge)
      throws IOException, BranchOperationException {

    try {
      // Find the common merge-base commit SHA
      String baseSha;
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit oursCommit = walk.parseCommit(repo.resolve("HEAD"));
        RevCommit theirsCommit = walk.parseCommit(sourceRef.getObjectId());
        walk.setRevFilter(RevFilter.MERGE_BASE);
        walk.markStart(oursCommit);
        walk.markStart(theirsCommit);
        RevCommit base = walk.next();
        if (base == null) {
          throw new BranchOperationException(
              "Cannot find common ancestor for '" + sourceBranch
              + "' and '" + targetBranch + "'");
        }
        baseSha = base.getName();
      }
      String oursSha = repo.resolve("HEAD").getName();
      String theirsSha = sourceRef.getObjectId().getName();
      LOGGER.info("Engine-replay merge: base={}, ours={}, theirs={}",
          baseSha.substring(0, 7), oursSha.substring(0, 7), theirsSha.substring(0, 7));

      // Replay source branch EChanges onto ours VSUM; returns merged state folder on success.
      tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult engineResult =
          mergeEngine.merge(baseSha, oursSha, theirsSha);

      if (!engineResult.isSuccess()) {
        LOGGER.warn("Engine replay detected unexpected conflicts for semantically-clean merge");
        // Clear the MERGE_HEAD that was written before calling this method so the
        // repository is left in a clean state and subsequent merge attempts can proceed.
        try {
          git.reset().setMode(ResetCommand.ResetType.HARD).call();
        } catch (GitAPIException resetEx) {
          LOGGER.warn("Hard reset after engine-replay failure failed: {}", resetEx.getMessage());
        }
        return ModelMergeResult.conflicting(sourceBranch, targetBranch,
            List.of("semantic://engine-replay-conflict"));
      }

      // Overlay the merged model files onto the working directory
      List<String> copiedFiles = List.of();
      Path mergedFolder = engineResult.getMergedStateFolder();
      if (mergedFolder != null && Files.isDirectory(mergedFolder)) {
        copiedFiles = copyMergedModelFiles(mergedFolder, repoRoot);
      }

      // Stage only the files that were copied from the engine output, not the entire
      // working directory. This prevents IDE config or build artefacts from slipping
      // into the merge commit. Fall back to staging everything if no files were copied
      // (e.g. engine produced an empty merged state).
      PersonIdent ident = new PersonIdent("Vitruvius", "vitruvius@system");
      if (copiedFiles.isEmpty()) {
        git.add().addFilepattern(".").call();
      } else {
        for (String path : copiedFiles) {
          git.add().addFilepattern(path).call();
        }
      }
      RevCommit mergeCommit = git.commit()
          .setAuthor(ident)
          .setCommitter(ident)
          .setMessage("Merge branch '" + sourceBranch + "' into '" + targetBranch
              + "' [semantic-replay]")
          .setNoVerify(true)
          .call();

      String sha = mergeCommit.getName();
      LOGGER.info("Engine-replay merge commit: {}", sha.substring(0, 7));

      ModelMergeResult result = ModelMergeResult.success(sourceBranch, targetBranch, sha);
      markAsMerged(git, sourceBranch);
      copyChangelogsFromSourceToTarget(git, sourceBranch, targetBranch);
      writeMergeTrigger(result, sourceBranch, targetBranch);
      if (postMergeHandler != null) {
        postMergeHandler.copyVsumFromSourceBranch(sourceBranch, targetBranch);
      }
      postMergeReload.run();
      if (deleteAfterMerge) {
        deleteSourceBranch(git, sourceBranch);
      }
      return result;

    } catch (GitAPIException e) {
      throw new BranchOperationException(
          "Engine-replay merge failed: " + e.getMessage(), e);
    } catch (Exception e) {
      if (e instanceof BranchOperationException boe) throw boe;
      if (e instanceof IOException ioe) throw ioe;
      throw new BranchOperationException(
          "Engine-replay merge failed: " + e.getMessage(), e);
    }
  }

  /**
   * Copies model files from the engine's merged-state folder to the repository working
   * directory, preserving relative paths. All files are copied except those under
   * {@code .vitruvius/} (VSUM metadata with stale absolute URIs) and {@code .git/}
   * (git internals). This is extension-agnostic: any model file extension is accepted,
   * consistent with the reference implementation in {@code GitStateLoader.checkoutStateAtCommit}.
   *
   * @return repo-relative forward-slash paths of every file that was successfully copied,
   *     for use as targeted {@code git add} patterns by the caller.
   */
  private List<String> copyMergedModelFiles(Path sourceFolder, Path targetFolder) {
    List<String> copied = new ArrayList<>();
    try (java.util.stream.Stream<Path> stream = Files.walk(sourceFolder)) {
      stream.filter(p -> !Files.isDirectory(p))
          .filter(p -> {
            Path rel = sourceFolder.relativize(p);
            String topLevel = rel.getName(0).toString();
            return !topLevel.equals(".vitruvius") && !topLevel.equals(".git");
          })
          .forEach(p -> {
            try {
              Path relative = sourceFolder.relativize(p);
              Path target = targetFolder.resolve(relative);
              Files.createDirectories(target.getParent());
              Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              copied.add(relative.toString().replace('\\', '/'));
              LOGGER.debug("Merged model file copied: {}", relative);
            } catch (IOException ex) {
              LOGGER.warn("Failed to copy merged model file {}: {}", p, ex.getMessage());
            }
          });
    } catch (IOException e) {
      LOGGER.warn("Failed to walk merged-state folder: {}", e.getMessage());
    }
    return copied;
  }

  /**
   * Auto-resolves semantic conflicts by replaying EChanges through the merge engine with
   * the registered {@link #conflictResolutionProvider} consulted per element.
   *
   * <p>This is the per-element counterpart to a file-level JGit strategy: the provider's
   * OURS/THEIRS choices are applied inside the engine's replay pipeline rather than as a
   * single coarse-grained {@link MergeStrategy} covering all model files. Non-conflicting
   * changes from both branches are preserved as-is.
   *
   * <p>Only called when both {@link #mergeEngine} and {@link #conflictResolutionProvider}
   * are set and conflicts have been detected by the semantic pre-check.
   */
  private ModelMergeResult resolveWithEngine(
      Git git, String sourceBranch, String targetBranch,
      Ref sourceRef, Repository repo, boolean deleteAfterMerge)
      throws IOException, BranchOperationException {

    // Write MERGE_HEAD before committing so the result is a proper two-parent merge commit.
    repo.writeMergeHeads(List.of(sourceRef.getObjectId()));

    try {
      String baseSha;
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit oursCommit = walk.parseCommit(repo.resolve("HEAD"));
        RevCommit theirsCommit = walk.parseCommit(sourceRef.getObjectId());
        walk.setRevFilter(RevFilter.MERGE_BASE);
        walk.markStart(oursCommit);
        walk.markStart(theirsCommit);
        RevCommit base = walk.next();
        if (base == null) {
          throw new BranchOperationException(
              "Cannot find common ancestor for '" + sourceBranch
              + "' and '" + targetBranch + "'");
        }
        baseSha = base.getName();
      }
      String oursSha = repo.resolve("HEAD").getName();
      String theirsSha = sourceRef.getObjectId().getName();
      LOGGER.info("Semantic-resolved merge: base={}, ours={}, theirs={}",
          baseSha.substring(0, 7), oursSha.substring(0, 7), theirsSha.substring(0, 7));

      // Create a provider-aware engine from the shared base engine. The provider is consulted
      // per conflicting element inside the replay pipeline, so OURS/THEIRS choices apply only
      // to the elements that actually conflict, not to every file in the repository.
      // Use merge() (directed replay) rather than mergeWithInterleaving(): only merge()
      // actually invokes conflictResolutionProvider.resolve() on detected conflicts.
      SemanticMergeEngine providerEngine =
          mergeEngine.withConflictResolutionProvider(conflictResolutionProvider);
      tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult engineResult =
          providerEngine.merge(baseSha, oursSha, theirsSha);

      if (!engineResult.isSuccess()) {
        LOGGER.warn("Semantic-resolved merge could not resolve all conflicts");
        try {
          git.reset().setMode(ResetCommand.ResetType.HARD).call();
        } catch (GitAPIException resetEx) {
          LOGGER.warn("Hard reset after resolution failure failed: {}", resetEx.getMessage());
        }
        java.nio.file.Files.deleteIfExists(repoRoot.resolve(".git/MERGE_HEAD"));
        return ModelMergeResult.conflicting(sourceBranch, targetBranch,
            List.of("semantic://unresolved-conflict"));
      }

      List<String> copiedFiles = List.of();
      Path mergedFolder = engineResult.getMergedStateFolder();
      if (mergedFolder != null && Files.isDirectory(mergedFolder)) {
        copiedFiles = copyMergedModelFiles(mergedFolder, repoRoot);
      }

      PersonIdent ident = new PersonIdent("Vitruvius", "vitruvius@system");
      if (copiedFiles.isEmpty()) {
        git.add().addFilepattern(".").call();
      } else {
        for (String path : copiedFiles) {
          git.add().addFilepattern(path).call();
        }
      }
      RevCommit mergeCommit = git.commit()
          .setAuthor(ident)
          .setCommitter(ident)
          .setMessage("Merge branch '" + sourceBranch + "' into '" + targetBranch
              + "' [semantic-resolved]")
          .setNoVerify(true)
          .call();

      String sha = mergeCommit.getName();
      LOGGER.info("Semantic-resolved merge commit: {}", sha.substring(0, 7));

      ModelMergeResult result = ModelMergeResult.success(sourceBranch, targetBranch, sha);
      markAsMerged(git, sourceBranch);
      copyChangelogsFromSourceToTarget(git, sourceBranch, targetBranch);
      writeMergeTrigger(result, sourceBranch, targetBranch);
      if (postMergeHandler != null) {
        postMergeHandler.copyVsumFromSourceBranch(sourceBranch, targetBranch);
      }
      postMergeReload.run();
      if (deleteAfterMerge) {
        deleteSourceBranch(git, sourceBranch);
      }
      return result;

    } catch (GitAPIException e) {
      throw new BranchOperationException(
          "Semantic-resolved merge failed: " + e.getMessage(), e);
    } catch (Exception e) {
      if (e instanceof BranchOperationException boe) throw boe;
      if (e instanceof IOException ioe) throw ioe;
      throw new BranchOperationException(
          "Semantic-resolved merge failed: " + e.getMessage(), e);
    }
  }
}
