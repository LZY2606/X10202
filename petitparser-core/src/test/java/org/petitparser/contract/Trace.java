package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shared harness for the {@code parseOn} / {@code fastParseOn} contract.
 *
 * <p>A fresh {@link Trace} is created for every run of every contract
 * scenario, so the recorded {@link #events} (one entry per child invocation)
 * and the {@link #sideEffects} counter start empty.
 */
final class Trace {

  /** One recorded child invocation, e.g. {@code a@1->ok:2}. */
  final List<String> events = new ArrayList<>();

  /** Number of times an action callback actually ran. */
  int sideEffects = 0;

  /** Clears the recorded state between the slow and fast runs. */
  void reset() {
    events.clear();
    sideEffects = 0;
  }

  private final String buffer;

  Trace(String buffer) {
    this.buffer = Objects.requireNonNull(buffer);
  }

  // ------------------------------------------------------------------
  // Observable child builders
  // ------------------------------------------------------------------

  /** Succeeds consuming one character when it equals {@code wanted}. */
  ObservableParser charOf(String name, char wanted) {
    return obs(name, pos -> pos < buffer.length()
        && buffer.charAt(pos) == wanted
        ? ObservableParser.Move.success(pos + 1)
        : ObservableParser.Move.failure(pos), wanted);
  }

  /** Always succeeds without consuming, producing {@code null}. */
  ObservableParser epsilon(String name) {
    return obs(name, ObservableParser.Move::success, null);
  }

  /** Always fails at the entry position without consuming. */
  ObservableParser failing(String name) {
    return obs(name, ObservableParser.Move::failure, null);
  }

  /**
   * Table-driven behaviour: at a position in {@code success} succeeds with the
   * mapped next position (possibly the same position for a zero-width move);
   * at a position in {@code failurePositions} fails at that position.
   */
  ObservableParser table(String name, Map<Integer, Integer> success,
      int... failurePositions) {
    Set<Integer> failures = new HashSet<>();
    for (int position : failurePositions) {
      failures.add(position);
    }
    return obs(name, pos -> success.containsKey(pos)
        ? ObservableParser.Move.success(success.get(pos))
        : failures.contains(pos)
        ? ObservableParser.Move.failure(pos)
        : ObservableParser.Move.failure(pos), "v" + name);
  }

  /** Wraps an existing parser while keeping every transition visible. */
  Parser watched(Parser parser) {
    return parser;
  }

  /** Callback that counts executions and tags the transformed value. */
  <T> Function<T, String> action(String tag) {
    return value -> {
      sideEffects++;
      return tag + "(" + value + ")";
    };
  }

  // ------------------------------------------------------------------
  // Contract assertions
  // ------------------------------------------------------------------

  private ObservableParser obs(String name,
      ObservableParser.Behaviour behaviour, Object value) {
    return new ObservableParser(name, this, behaviour, value);
  }

  /**
   * Runs both entry points on freshly built parsers and locks the contract.
   *
   * @param builder constructs the combinator under test for a single run
   * @param position entry position inside the buffer
   * @param expectSuccess required outcome shared by both paths
   * @param expectedEnd final position on success
   * @param expectedEvents exact child-invocation sequence of both paths
   * @param expectedValue value produced by {@code parseOn} on success
   * @param expectedFailurePosition failure position of {@code parseOn}
   * @param expectedMessage failure message of {@code parseOn} (ignored if null)
   * @param fastSideEffects action executions during the fast run
   * @param slowSideEffects action executions during the slow run
   */
  void assertContract(Supplier<Parser> builder, int position,
      boolean expectSuccess, int expectedEnd, List<String> expectedEvents,
      Object expectedValue, int expectedFailurePosition,
      String expectedMessage, int fastSideEffects, int slowSideEffects) {
    // Slow path (full result construction), on a freshly built parser.
    Parser slow = builder.get();
    Result result = slow.parseOn(new Context(buffer, position));
    assertEquals("parseOn success flag", expectSuccess, result.isSuccess());
    assertEquals("slow child transitions", expectedEvents,
        new ArrayList<>(events));
    assertEquals("slow side-effect executions", slowSideEffects, sideEffects);
    if (expectSuccess) {
      assertEquals("parseOn final position", expectedEnd,
          result.getPosition());
      assertValueEquals(expectedValue, result.get());
    } else {
      assertEquals("parseOn failure position", expectedFailurePosition,
          result.getPosition());
      if (expectedMessage != null) {
        assertEquals("parseOn failure message", expectedMessage,
            result.getMessage());
      }
    }

    // Fast path (position-only), on another freshly built parser sharing
    // the same instrumentation state.
    reset();
    Parser fast = builder.get();
    int fastPosition = fast.fastParseOn(buffer, position);
    if (expectSuccess) {
      assertTrue("fastParseOn expected to succeed", fastPosition >= 0);
      assertEquals("fastParseOn final position",
          expectedEnd, fastPosition);
    } else {
      assertEquals("fastParseOn expected to fail", -1, fastPosition);
    }
    assertEquals("fast child transitions", expectedEvents,
        new ArrayList<>(events));
    assertEquals("fast side-effect executions", fastSideEffects, sideEffects);

    // accept() is the public predicate built on top of the fast path; its
    // transition bookkeeping is the same and already checked above.
    if (position == 0) {
      reset();
      assertEquals("accept() agrees with fast path", expectSuccess,
          builder.get().accept(buffer));
    }
  }

  private static void assertValueEquals(Object expected, Object actual) {
    if (expected instanceof TokenExpectation) {
      TokenExpectation expectedToken = (TokenExpectation) expected;
      assertTrue("expected a Token, got " + actual, actual instanceof Token);
      Token token = (Token) actual;
      assertEquals("token start", expectedToken.start, token.getStart());
      assertEquals("token stop", expectedToken.stop, token.getStop());
      assertEquals("token value", expectedToken.value, token.getValue());
      assertEquals("token input", expectedToken.input, token.getInput());
    } else {
      assertEquals("parseOn value", expected, actual);
    }
  }

  static TokenExpectation token(int start, int stop, String input,
      Object value) {
    return new TokenExpectation(start, stop, input, value);
  }

  static final class TokenExpectation {
    final int start;
    final int stop;
    final String input;
    final Object value;

    TokenExpectation(int start, int stop, String input, Object value) {
      this.start = start;
      this.stop = stop;
      this.input = input;
      this.value = value;
    }
  }

  static List<String> trace(String... entries) {
    return new ArrayList<>(Arrays.asList(entries));
  }
}
