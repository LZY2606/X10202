package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shared assertions of the binary contract between {@link Parser#parseOn} and
 * {@link Parser#fastParseOn}.
 *
 * <p>Every combinator is driven through both entry points from the same input
 * and starting position. The contract locks:
 * <ul>
 *   <li>both paths agree on success versus failure;</li>
 *   <li>both paths agree on the final position on success;</li>
 *   <li>the full path reports the expected value, failure position and
 *       failure message.</li>
 * </ul>
 * Changing the position computation or backtracking order in only one of the
 * two paths makes these assertions fail.
 */
final class Contract {

  private Contract() {
  }

  /** Asserts the full/fast contract, without expectations on value/failure. */
  static void assertContract(Parser parser, String input, int start) {
    Result result = parser.parseOn(new Context(input, start));
    int fast = parser.fastParseOn(input, start);
    assertEquals("fast/slow success agreement at " + start,
        result.isSuccess(), fast >= 0);
    if (result.isSuccess()) {
      assertEquals("fast/slow final position at " + start,
          result.getPosition(), fast);
    }
  }

  /** Asserts a successful full/fast contract and the full path value. */
  static void assertSuccess(Parser parser, String input, int start,
      Object expectedValue, int expectedPosition) {
    Result result = parser.parseOn(new Context(input, start));
    int fast = parser.fastParseOn(input, start);
    assertTrue("expected success, got " + describe(result), result.isSuccess());
    assertEquals("slow final position", expectedPosition, result.getPosition());
    assertEquals("fast final position", expectedPosition, fast);
    assertDeepEquals("slow value", expectedValue, result.get());
  }

  /** Asserts a failing full/fast contract and the full path failure data. */
  static void assertFailure(Parser parser, String input, int start,
      int expectedPosition, String expectedMessage) {
    Result result = parser.parseOn(new Context(input, start));
    int fast = parser.fastParseOn(input, start);
    assertTrue("expected failure, but got success at "
        + result.getPosition(), result.isFailure());
    assertEquals("fast path must fail", -1, fast);
    assertEquals("failure position", expectedPosition, result.getPosition());
    if (expectedMessage != null) {
      assertEquals("failure message", expectedMessage, result.getMessage());
    }
  }

  /**
   * Runs both paths and asserts that each observable child sees the same
   * position sequence on both paths, and that the entry points used match
   * {@code slowModes} (full path) and {@code fastModes} (fast path).
   */
  static void runBothPaths(Parser parser, String input, int start,
      List<String> slowModes, List<Integer> slowPositions,
      List<String> fastModes, List<Integer> fastPositions,
      ObservableParser... observables) {
    for (ObservableParser observable : observables) {
      observable.reset();
    }
    parser.parseOn(new Context(input, start));
    List<String> actualSlowModes = new java.util.ArrayList<>();
    List<Integer> actualSlowPositions = new java.util.ArrayList<>();
    for (ObservableParser observable : observables) {
      actualSlowModes.addAll(observable.modes());
      actualSlowPositions.addAll(observable.positions());
    }
    assertEquals("slow path child entry points", slowModes, actualSlowModes);
    assertEquals("slow path child positions", slowPositions,
        actualSlowPositions);

    for (ObservableParser observable : observables) {
      observable.reset();
    }
    parser.fastParseOn(input, start);
    List<String> actualFastModes = new java.util.ArrayList<>();
    List<Integer> actualFastPositions = new java.util.ArrayList<>();
    for (ObservableParser observable : observables) {
      actualFastModes.addAll(observable.modes());
      actualFastPositions.addAll(observable.positions());
    }
    assertEquals("fast path child entry points", fastModes, actualFastModes);
    assertEquals("fast path child positions", fastPositions,
        actualFastPositions);

    assertEquals("backtracking/invocation order must be shared by both paths",
        slowPositions, fastPositions);
  }

  private static void assertDeepEquals(String label, Object expected,
      Object actual) {
    if (expected != null && expected.getClass().isArray()) {
      assertTrue(label + ": " + describeValue(actual),
          Objects.deepEquals(expected, actual));
    } else {
      assertEquals(label, expected, actual);
    }
  }

  private static String describe(Result result) {
    return result.isSuccess()
        ? "success at " + result.getPosition()
        : "failure at " + result.getPosition() + ": " + result.getMessage();
  }

  private static String describeValue(Object value) {
    return String.valueOf(value);
  }
}
