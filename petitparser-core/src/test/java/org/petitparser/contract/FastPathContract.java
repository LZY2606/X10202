package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Shared executable contract between {@link Parser#parseOn(Context)} and
 * {@link Parser#fastParseOn(String, int)}.
 *
 * <p>For every input and starting position the two paths must agree on
 * success/failure and on the final position. {@code parseOn} additionally has
 * to produce the documented value and failure message, and the sequence of
 * observable child activations on each path has to follow the same transfer
 * order (same children, same positions) so that a change to either path's
 * position bookkeeping or roll-back order is detected independently.
 */
final class FastPathContract {

  private FastPathContract() {
  }

  /**
   * Asserts that both paths agree for the given {@code input}, starting at
   * {@code start}.
   */
  static void assertContract(Parser parser, ObservableParser.EventLog log,
      String input, int start) {
    assertContract(parser, log, input, start, null, -1, null);
  }

  /**
   * Asserts that both paths agree and that {@code parseOn} produces the
   * expected success/failure details.
   *
   * @param expectedValue   expected value on success, {@code null} to skip the
   *                        value assertion (use a sentinel for an expected
   *                        {@code null} value via
   *                        {@link #assertContractSuccessOrFailure})
   * @param failurePosition expected failure position, {@code -1} for success
   * @param failureMessage  expected failure message, {@code null} for success
   */
  static void assertContract(Parser parser, ObservableParser.EventLog log,
      String input, int start, Object expectedValue, int failurePosition,
      String failureMessage) {
    boolean expectSuccess = failurePosition < 0;

    log.clear();
    Result result = parser.parseOn(new Context(input, start));
    List<ObservableParser.Event> parseEvents = log.snapshot();

    log.clear();
    int fast = parser.fastParseOn(input, start);
    List<ObservableParser.Event> fastEvents = log.snapshot();

    if (expectSuccess) {
      assertTrue("parseOn expected success at " + start + " but got "
          + result, result.isSuccess());
      assertTrue("fastParseOn expected non-negative position at " + start
          + " but got " + fast, fast >= 0);
      assertEquals("final position", result.getPosition(), fast);
      if (expectedValue != null) {
        assertEquals("success value", expectedValue, result.get());
      }
      assertNull("no failure message on success", result.getMessage());
    } else {
      assertTrue("parseOn expected failure at " + start + " but got "
          + result, result.isFailure());
      assertEquals("fastParseOn expected -1 at " + start, -1, fast);
      assertEquals("failure position", failurePosition, result.getPosition());
      if (failureMessage != null) {
        assertEquals("failure message", failureMessage, result.getMessage());
      }
    }

    assertSameTransitions(start, parseEvents, fastEvents);
  }

  /** Asserts a successful contract with an expected value of {@code null}. */
  static void assertContractSuccessNull(Parser parser,
      ObservableParser.EventLog log, String input, int start, int finalPos) {
    log.clear();
    Result result = parser.parseOn(new Context(input, start));
    List<ObservableParser.Event> parseEvents = log.snapshot();

    log.clear();
    int fast = parser.fastParseOn(input, start);
    List<ObservableParser.Event> fastEvents = log.snapshot();

    assertTrue("expected success, got " + result, result.isSuccess());
    assertEquals("final position", finalPos, fast);
    assertEquals("final position", finalPos, result.getPosition());
    assertNull("expected null value", result.get());
    assertNull("no failure message", result.getMessage());
    assertSameTransitions(start, parseEvents, fastEvents);
  }

  /**
   * The two paths necessarily run on different primitives, so the transition
   * contract compares which observable child was activated at which position
   * in which order, but ignores the {@code parseOn} / {@code fastParseOn}
   * distinction. This still catches any independent change to position
   * bookkeeping or choice/repeat roll-back ordering.
   */
  private static void assertSameTransitions(int start,
      List<ObservableParser.Event> parseEvents,
      List<ObservableParser.Event> fastEvents) {
    assertEquals("activation order at " + start + " (parseOn="
            + describe(parseEvents) + ", fastParseOn="
            + describe(fastEvents) + ")",
        describe(parseEvents), describe(fastEvents));
  }

  private static List<String> describe(List<ObservableParser.Event> events) {
    return events.stream()
        .map(event -> event.name + "@" + event.position)
        .collect(Collectors.toList());
  }
}
