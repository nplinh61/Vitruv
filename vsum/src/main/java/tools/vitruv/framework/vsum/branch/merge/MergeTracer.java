package tools.vitruv.framework.vsum.branch.merge;

// Source: anonymous author (paper companion code).
// Copied with package and import changes only.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Records and logs the steps taken during a semantic merge for debugging and traceability.
 *
 * <p>The tracer collects events from the merge pipeline (dependency graph construction,
 * interleaving, replay) and makes them available for inspection or export after the merge.
 */
public class MergeTracer {

  private static final Logger LOGGER = LogManager.getLogger(MergeTracer.class);

  private final List<String> events = new ArrayList<>();

  /**
   * Records and logs a merge pipeline event.
   *
   * @param step    the pipeline step name (e.g. "buildDependencyGraph").
   * @param message description of what happened.
   */
  public void trace(String step, String message) {
    String event = "[" + step + "] " + message;
    events.add(event);
    LOGGER.debug(event);
  }

  /**
   * Records and logs a warning event.
   *
   * @param step    the pipeline step name.
   * @param message description of the warning.
   */
  public void warn(String step, String message) {
    String event = "[" + step + "][WARN] " + message;
    events.add(event);
    LOGGER.warn(event);
  }

  /**
   * Returns all recorded events in insertion order.
   */
  public List<String> getEvents() {
    return Collections.unmodifiableList(events);
  }

  /**
   * Clears all recorded events.
   */
  public void reset() {
    events.clear();
  }
}
