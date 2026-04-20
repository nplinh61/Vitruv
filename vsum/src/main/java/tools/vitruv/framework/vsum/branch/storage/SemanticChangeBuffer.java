package tools.vitruv.framework.vsum.branch.storage;

// Origin tracking logic (AnnotatedEChange inner class, drainAnnotatedChanges, and the
// identity-set approach for detecting consequential EChanges) adapted from
// Tural Mammadlee (feature/conflict-analyzer branch, SemanticChangeBuffer.java).

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.EObjectExistenceEChange;
import tools.vitruv.change.atomic.feature.FeatureEChange;
import tools.vitruv.change.atomic.root.RootEChange;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.composite.description.PropagatedChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;

/**
 * Accumulates atomic {@code EChange<EObject>} instances between commits, grouped by the URI
 * of the resource they affect. Each change is tagged with a {@link ChangeOrigin} label so the
 * merge step can tell apart human-made changes from engine-generated ones.
 *
 * <p>Register an instance as a {@link ChangePropagationListener} on the
 * {@link tools.vitruv.framework.vsum.branch.BranchAwareVirtualModel}. At commit time call
 * {@link #drainAnnotatedChanges()} to retrieve and clear the buffer.
 *
 * <p>How origin tagging works: after each propagation, the buffer collects all EChange
 * instances that appear inside any {@code consequentialChanges} set. Those are tagged as
 * {@link ChangeOrigin#CONSEQUENTIAL}. Everything else is tagged as
 * {@link ChangeOrigin#ORIGINAL}. The check uses object identity so it is fast and exact.
 *
 * <p>Thread-safety: all public methods and callbacks are {@code synchronized} on {@code this}.
 */
public class SemanticChangeBuffer implements ChangePropagationListener {

  private static final Logger LOGGER = LogManager.getLogger(SemanticChangeBuffer.class);

  /**
   * An EChange paired with its detected origin (ORIGINAL or CONSEQUENTIAL).
   */
  public static class AnnotatedEChange {

    private final EChange<EObject> change;
    private final ChangeOrigin origin;

    public AnnotatedEChange(EChange<EObject> change, ChangeOrigin origin) {
      this.change = Objects.requireNonNull(change);
      this.origin = Objects.requireNonNull(origin);
    }

    public EChange<EObject> getChange() {
      return change;
    }

    public ChangeOrigin getOrigin() {
      return origin;
    }

    @Override
    public String toString() {
      return "AnnotatedEChange{" + change.getClass().getSimpleName() + ", " + origin + '}';
    }
  }

  /**
   * Accumulated annotated changes, keyed by resource URI string.
   * LinkedHashMap preserves insertion order so replay is deterministic.
   */
  private final Map<String, List<AnnotatedEChange>> annotatedChangesByResource =
      new LinkedHashMap<>();

  /**
   * Total number of atomic changes accumulated since the last drain.
   */
  private int totalChanges = 0;

  @Override
  public synchronized void startedChangePropagation(VitruviusChange<Uuid> changeToPropagate) {
    // no-op
  }

  /**
   * Collects both original and consequential changes from every {@link PropagatedChange},
   * tagging each with its detected {@link ChangeOrigin}.
   *
   * <p>A change is CONSEQUENTIAL if its EChange instance appears inside the
   * {@code consequentialChanges} of any PropagatedChange (identity check).
   * All other changes are ORIGINAL.
   */
  @Override
  public synchronized void finishedChangePropagation(
      Iterable<PropagatedChange> propagatedChanges) {
    List<PropagatedChange> pcList = new ArrayList<>();
    propagatedChanges.forEach(pcList::add);

    // Build the set of all EChange objects that are reaction outputs.
    // Uses identity (==) comparison, not equals(), because the same instance
    // is shared between consequentialChanges and the subsequent originalChange.
    Set<Object> reactionEChanges = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PropagatedChange pc : pcList) {
      VitruviusChange<EObject> consequential = pc.getConsequentialChanges();
      if (consequential != null) {
        reactionEChanges.addAll(consequential.getEChanges());
      }
    }

    // Collect all original changes with their origin tag.
    for (PropagatedChange pc : pcList) {
      VitruviusChange<EObject> original = pc.getOriginalChange();
      if (original == null) {
        continue;
      }
      List<EChange<EObject>> eChanges = original.getEChanges();
      // If every EChange in this PC is a reaction output, the whole PC is CONSEQUENTIAL.
      boolean isReaction = !eChanges.isEmpty() && reactionEChanges.containsAll(eChanges);
      ChangeOrigin origin = isReaction ? ChangeOrigin.CONSEQUENTIAL : ChangeOrigin.ORIGINAL;

      for (EChange<EObject> change : eChanges) {
        String resourceUri = resolveResourceUri(change);
        annotatedChangesByResource
            .computeIfAbsent(resourceUri, k -> new ArrayList<>())
            .add(new AnnotatedEChange(change, origin));
        totalChanges++;
      }

      if (isReaction) {
        LOGGER.debug("Collected {} CONSEQUENTIAL change(s) from reaction PC", eChanges.size());
      }
    }

    // Also collect any consequential changes not already captured above.
    for (PropagatedChange pc : pcList) {
      VitruviusChange<EObject> consequential = pc.getConsequentialChanges();
      if (consequential == null) {
        continue;
      }
      for (EChange<EObject> change : consequential.getEChanges()) {
        String resourceUri = resolveResourceUri(change);
        List<AnnotatedEChange> existing = annotatedChangesByResource.get(resourceUri);
        // Skip if this exact instance was already collected via originalChange above.
        boolean alreadyCollected = existing != null && existing.stream()
            .anyMatch(a -> a.getChange() == change);
        if (!alreadyCollected) {
          annotatedChangesByResource
              .computeIfAbsent(resourceUri, k -> new ArrayList<>())
              .add(new AnnotatedEChange(change, ChangeOrigin.CONSEQUENTIAL));
          totalChanges++;
        }
      }
    }

    LOGGER.debug("Buffer now holds {} atomic change(s) across {} resource(s)",
        totalChanges, annotatedChangesByResource.size());
  }

  /**
   * Returns all accumulated changes with their origin tags, then clears the buffer.
   * Use this at commit time to write origin-tagged JSON changelog entries.
   *
   * @return immutable map of resource URI to ordered annotated changes.
   */
  public synchronized Map<String, List<AnnotatedEChange>> drainAnnotatedChanges() {
    Map<String, List<AnnotatedEChange>> snapshot = new LinkedHashMap<>();
    annotatedChangesByResource.forEach((uri, changes) ->
        snapshot.put(uri, Collections.unmodifiableList(new ArrayList<>(changes))));
    annotatedChangesByResource.clear();
    int drained = totalChanges;
    totalChanges = 0;
    LOGGER.info("Drained {} annotated change(s) from buffer for {} resource(s)",
        drained, snapshot.size());
    return Collections.unmodifiableMap(snapshot);
  }

  /**
   * Returns all accumulated changes without origin tags, then clears the buffer.
   * Kept for backward compatibility with callers that do not need origin information.
   *
   * @return immutable map of resource URI to ordered atomic changes.
   */
  public synchronized Map<String, List<EChange<EObject>>> drainChanges() {
    Map<String, List<EChange<EObject>>> snapshot = new LinkedHashMap<>();
    annotatedChangesByResource.forEach((uri, annotated) -> {
      List<EChange<EObject>> plain = new ArrayList<>();
      for (AnnotatedEChange a : annotated) {
        plain.add(a.getChange());
      }
      snapshot.put(uri, Collections.unmodifiableList(plain));
    });
    annotatedChangesByResource.clear();
    int drained = totalChanges;
    totalChanges = 0;
    LOGGER.info("Drained {} atomic change(s) from buffer for {} resource(s)",
        drained, snapshot.size());
    return Collections.unmodifiableMap(snapshot);
  }

  /**
   * Returns true if the buffer contains at least one change.
   */
  public synchronized boolean hasChanges() {
    return totalChanges > 0;
  }

  /**
   * Returns the total number of atomic changes currently in the buffer.
   */
  public synchronized int size() {
    return totalChanges;
  }

  /**
   * Determines the resource URI string for a given EChange.
   *
   * <ul>
   *   <li>For {@link RootEChange}: uses {@link RootEChange#getUri()} directly.</li>
   *   <li>For {@link FeatureEChange}: uses the resource of the affected element.</li>
   *   <li>For {@link EObjectExistenceEChange}: uses the affected element's resource.</li>
   *   <li>Fallback: {@code "unknown-resource"}.</li>
   * </ul>
   */
  private String resolveResourceUri(EChange<EObject> change) {
    if (change instanceof RootEChange<?> r) {
      String uri = r.getUri();
      return uri != null ? uri : "unknown-resource";
    }

    EObject element = null;
    if (change instanceof FeatureEChange<?, ?> f) {
      element = (EObject) f.getAffectedElement();
    } else if (change instanceof EObjectExistenceEChange<?> e) {
      element = (EObject) e.getAffectedElement();
    }

    if (element != null && element.eResource() != null) {
      URI uri = element.eResource().getURI();
      return uri != null ? uri.toString() : "unknown-resource";
    }

    LOGGER.debug(
        "Cannot determine resource URI for change of type '{}', filing under 'unknown-resource'",
        change.getClass().getSimpleName());
    return "unknown-resource";
  }
}
