package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Reads model files directly from the Git object store without checking out a branch.
 *
 * <p>Used by the replay engine (steps 8-9) to seed the merge state from the common
 * ancestor commit. All model files at the ancestor are read and provided as raw bytes
 * so the replay engine can reconstruct the EMF resource set.
 *
 * <p>No branch checkout is performed. Files are read via JGit's TreeWalk on the
 * commit's tree object.
 */
public class GitStateLoader {

  private static final Logger LOGGER = LogManager.getLogger(GitStateLoader.class);

  private final Path repositoryRoot;

  /**
   * Creates a new {@link GitStateLoader} for the repository at the given root.
   *
   * @param repositoryRoot the root directory of the Git repository; must not be null.
   */
  public GitStateLoader(Path repositoryRoot) {
    this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
  }

  /**
   * Reads all files in the given commit's tree that match the given path prefix,
   * returning their raw byte content keyed by their relative path.
   *
   * <p>Typically called with the ancestor commit SHA and a model directory prefix
   * (e.g. {@code "model/"}) to load the base state before replay begins.
   *
   * @param commitSha  full or 7-char SHA of the commit to read from.
   * @param pathPrefix prefix filter; only files whose path starts with this string are returned.
   * @return map from relative file path to raw file bytes.
   * @throws IOException if the repository or commit cannot be read.
   */
  public Map<String, byte[]> loadFilesAtCommit(String commitSha, String pathPrefix)
      throws IOException {
    Objects.requireNonNull(commitSha, "commitSha must not be null");
    Objects.requireNonNull(pathPrefix, "pathPrefix must not be null");

    Map<String, byte[]> files = new LinkedHashMap<>();

    try (Git git = Git.open(repositoryRoot.toFile())) {
      ObjectId oid = git.getRepository().resolve(commitSha);
      if (oid == null) {
        LOGGER.warn("Cannot resolve commit SHA '{}' in repository", commitSha);
        return files;
      }

      try (RevWalk revWalk = new RevWalk(git.getRepository())) {
        RevCommit commit = revWalk.parseCommit(oid);

        try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
          treeWalk.addTree(commit.getTree());
          treeWalk.setRecursive(true);

          while (treeWalk.next()) {
            String path = treeWalk.getPathString();
            if (!path.startsWith(pathPrefix)) {
              continue;
            }
            ObjectLoader loader = git.getRepository().open(treeWalk.getObjectId(0));
            files.put(path, loader.getBytes());
            LOGGER.debug("Loaded file '{}' from commit '{}'",
                path, commitSha.substring(0, Math.min(7, commitSha.length())));
          }
        }
      }
    }
    return files;
  }

  /**
   * Reads a single file from the given commit as a UTF-8 string.
   *
   * @param commitSha full or short SHA of the commit.
   * @param filePath  relative path of the file within the repository.
   * @return file contents as a string, or {@code null} if the file does not exist in that commit.
   * @throws IOException if the repository cannot be read.
   */
  public String loadFileAsString(String commitSha, String filePath) throws IOException {
    Map<String, byte[]> files = loadFilesAtCommit(commitSha, filePath);
    byte[] bytes = files.get(filePath);
    return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
  }
}
