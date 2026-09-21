package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shared executable contract between {@link Parser#parseOn(Context)} and
 * {@link Parser#fastParseOn(String, int)}.
 *
 * <p>The fast path and the slow path of a combinator must agree on
 * <ul>
 *   <li>success versus failure,</li>
 *   <li>the position reached on success,</li>
 *   <li>the sequence of child invocations (which child, at which position,
 *       in which order), which pins down choice rollback, sequence
 *       short-circuiting, repeating boundaries and lookahead re-entry, and</li>
 *   <li>the number of side-effecting actions performed.</li>
 * </ul>
 *
 * <p>Additionally the slow path is the only path that can be checked for its
 * resulting value and failure position/message.
 */
public final class FastSlowContract {

  /** Normalized description of one child activation. */
  public static final class Trace {

    final List<String> entries;

    Trace(List<String> entries) {
      this.entries = entries;
    }

    @Override
    public String toString() {
      return String.join(", ", entries);
    }
  }

  /** Outcome of a single slow-path parse. */
  public static final class SlowOutcome {

    final boolean success;
    final int position;
    final Object value;
    final String message;
    final Trace trace;

    SlowOutcome(boolean success, int position, Object value, String message,
        Trace trace) {
      this.success = success;
      this.position = position;
      this.value = value;
      this.message = message;
      this.trace = trace;
    }

    public boolean isSuccess() {
      return success;
    }

    public int getPosition() {
      return position;
    }

    public Object getValue() {
      return value;
    }

    public String getMessage() {
      return message;
    }

    public Trace getTrace() {
      return trace;
    }
  }

  private FastSlowContract() {
  }

  /** Runs the slow path and records its normalized child trace. */
  public static SlowOutcome slow(Parser parser, String input, int position,
      List<ObservableParser.Event> log) {
    log.clear();
    Result result = parser.parseOn(new Context(input, position));
    return new SlowOutcome(result.isSuccess(), result.getPosition(),
        result.isSuccess() ? result.get() : null,
        result.isFailure() ? result.getMessage() : null,
        normalize(log));
  }

  /** Runs the fast path and records its normalized child trace. */
  public static int fast(Parser parser, String input, int position,
      List<ObservableParser.Event> log) {
    log.clear();
    int result = parser.fastParseOn(input, position);
    return result;
  }

  private static Trace normalize(List<ObservableParser.Event> events) {
    List<String> entries = new ArrayList<>(events.size());
    for (ObservableParser.Event event : events) {
      entries.add(event.name + "@" + event.position);
    }
    return new Trace(entries);
  }

  /**
   * Asserts that both paths agree on success/failure, final position and the
   * observable child invocation trace. Returns the slow outcome so callers can
   * make further assertions on its value or failure.
   */
  public static SlowOutcome assertAgree(Parser parser, String input,
      int position, List<ObservableParser.Event> log) {
    SlowOutcome slow = slow(parser, input, position, log);
    int fastPosition = fast(parser, input, position, log);
    Trace fastTrace = normalize(log);

    if (slow.success) {
      assertTrue("fast path failed but parseOn succeeded for "
              + describe(parser, input, position), fastPosition >= 0);
      assertEquals("success position mismatch for "
              + describe(parser, input, position),
          slow.position, fastPosition);
    } else {
      assertTrue("fast path succeeded but parseOn failed for "
              + describe(parser, input, position), fastPosition < 0);
    }
    assertEquals("child invocation trace mismatch for "
            + describe(parser, input, position) + "; slow=" + slow.trace
            + ", fast=" + fastTrace,
        slow.trace.entries, fastTrace.entries);
    return slow;
  }

  /**
   * Like {@link #assertAgree} but additionally locks the number of
   * side-effecting action invocations: the fast path and the slow path each
   * have to trigger {@code expectedSideEffects} invocations of the tracked
   * action.
   */
  public static SlowOutcome assertSideEffects(Parser parser, String input,
      int position, List<ObservableParser.Event> log,
      AtomicInteger counter, int expectedSideEffects) {
    counter.set(0);
    SlowOutcome slow = slow(parser, input, position, log);
    int slowEffects = counter.getAndSet(0);
    log.clear();
    int fastPosition = parser.fastParseOn(input, position);
    int fastEffects = counter.get();
    Trace fastTrace = normalize(log);

    if (slow.success) {
      assertTrue("fast path failed but parseOn succeeded for "
              + describe(parser, input, position), fastPosition >= 0);
      assertEquals("success position mismatch for "
              + describe(parser, input, position),
          slow.position, fastPosition);
    } else {
      assertTrue("fast path succeeded but parseOn failed for "
              + describe(parser, input, position), fastPosition < 0);
    }
    assertEquals("child invocation trace mismatch for "
            + describe(parser, input, position) + "; slow=" + slow.trace
            + ", fast=" + fastTrace,
        slow.trace.entries, fastTrace.entries);
    assertEquals("side effects on slow path for "
            + describe(parser, input, position),
        expectedSideEffects, slowEffects);
    assertEquals("side effects on fast path for "
            + describe(parser, input, position),
        expectedSideEffects, fastEffects);
    return slow;
  }

  /**
   * Asserts both paths agree on outcome/position/trace and that the slow path
   * performs exactly {@code expectedSlowEffects} tracked action invocations
   * while the fast path performs exactly {@code expectedFastEffects}. This
   * locks down that the fast path of a pure {@code map} skips the action
   * whereas {@code mapWithSideEffects} deliberately runs it.
   */
  public static SlowOutcome assertSideEffectCounts(Parser parser,
      String input, int position, List<ObservableParser.Event> log,
      AtomicInteger counter, int expectedSlowEffects,
      int expectedFastEffects) {
    counter.set(0);
    SlowOutcome slow = slow(parser, input, position, log);
    int slowEffects = counter.getAndSet(0);
    log.clear();
    int fastPosition = parser.fastParseOn(input, position);
    int fastEffects = counter.get();
    Trace fastTrace = normalize(log);

    if (slow.success) {
      assertTrue("fast path failed but parseOn succeeded for "
              + describe(parser, input, position), fastPosition >= 0);
      assertEquals("success position mismatch for "
              + describe(parser, input, position),
          slow.position, fastPosition);
    } else {
      assertTrue("fast path succeeded but parseOn failed for "
              + describe(parser, input, position), fastPosition < 0);
    }
    assertEquals("child invocation trace mismatch for "
            + describe(parser, input, position) + "; slow=" + slow.trace
            + ", fast=" + fastTrace,
        slow.trace.entries, fastTrace.entries);
    assertEquals("side effects on slow path for "
            + describe(parser, input, position),
        expectedSlowEffects, slowEffects);
    assertEquals("side effects on fast path for "
            + describe(parser, input, position),
        expectedFastEffects, fastEffects);
    return slow;
  }

  private static String describe(Parser parser, String input, int position) {
    return parser + " on \"" + input + "\"@" + position;
  }
}
