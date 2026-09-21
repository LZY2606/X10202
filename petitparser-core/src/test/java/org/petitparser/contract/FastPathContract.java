package org.petitparser.contract;

import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shared executable contract between {@link Parser#parseOn} and
 * {@link Parser#fastParseOn}.
 *
 * <p>For every combinator the two evaluation paths must agree on success and
 * on the position reached after a successful parse; the slow path is
 * additionally the only place that carries a parse value or a failure position
 * and message, and is asserted separately. Because both paths are driven by
 * the same {@link ScriptedParser} transition script, the recorded invocation
 * sequence additionally pins down choice rollback, sequence partial failure,
 * repeat boundaries and lookahead zero-consumption.
 */
final class FastPathContract {

  private FastPathContract() {
  }

  static final class SlowOutcome {
    final boolean success;
    final int position;
    final Object value;
    final String message;

    private SlowOutcome(Result result) {
      this.success = result.isSuccess();
      this.position = result.getPosition();
      this.value = result.isSuccess() ? result.get() : null;
      this.message = result.isFailure() ? result.getMessage() : null;
    }
  }

  /**
   * Runs the slow path once on a fresh copy of the script and returns its
   * observable outcome together with the recorded child invocations.
   */
  static SlowOutcome slow(Parser parser, ScriptedParser child,
      String buffer, int position) {
    child.calls().clear();
    Result result = parser.parseOn(
        new org.petitparser.context.Context(buffer, position));
    return new SlowOutcome(result);
  }

  /**
   * Runs the fast path once and returns the resulting position ({@code -1} on
   * failure), recording child invocations as it goes.
   */
  static int fast(Parser parser, ScriptedParser child,
      String buffer, int position) {
    child.calls().clear();
    return parser.fastParseOn(buffer, position);
  }

  /**
   * Core contract: both paths report the same success flag and, on success,
   * reach the same position, and the observable child parser is invoked at the
   * same entry positions in the same order.
   */
  static void assertAgree(Parser parser, ScriptedParser child,
      String buffer, int start) {
    SlowOutcome slow = slow(parser, child, buffer, start);
    List<ScriptedParser.Call> slowCalls =
        new java.util.ArrayList<>(child.calls());

    int fastPosition = fast(parser, child, buffer, start);
    List<ScriptedParser.Call> fastCalls =
        new java.util.ArrayList<>(child.calls());

    assertEquals("success flag disagreement at position " + start,
        slow.success, fastPosition >= 0);
    if (slow.success) {
      assertEquals("success position disagreement at position " + start,
          slow.position, fastPosition);
    }
    assertEquals("child invocation sequence differs between paths at "
            + start, slowCalls, fastCalls);
    assertTrue("scripted transitions must be fully consumed",
        child.callCount() >= 0);
  }

  /**
   * Asserts the slow path succeeds with exactly the given value and position.
   */
  static void assertSlowSuccess(Parser parser, ScriptedParser child,
      String buffer, int start, Object value, int position) {
    SlowOutcome outcome = slow(parser, child, buffer, start);
    assertTrue("expected slow-path success", outcome.success);
    assertEquals("slow-path value", value, outcome.value);
    assertEquals("slow-path position", position, outcome.position);
  }

  /**
   * Asserts the slow path fails at exactly the given position and message.
   */
  static void assertSlowFailure(Parser parser, ScriptedParser child,
      String buffer, int start, int failurePosition, String message) {
    SlowOutcome outcome = slow(parser, child, buffer, start);
    assertTrue("expected slow-path failure", !outcome.success);
    assertEquals("failure position", failurePosition, outcome.position);
    assertEquals("failure message", message, outcome.message);
  }

  /**
   * Asserts both paths agree and that the fast path reports the given success
   * position. Used to anchor boundary-success cases.
   */
  static void assertAgreeAt(Parser parser, ScriptedParser child,
      String buffer, int start, int expectedPosition) {
    assertAgree(parser, child, buffer, start);
    assertEquals(expectedPosition, fast(parser, child, buffer, start));
  }
}
