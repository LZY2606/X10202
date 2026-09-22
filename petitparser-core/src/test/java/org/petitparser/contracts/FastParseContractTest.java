package org.petitparser.contracts;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;
import org.petitparser.utils.FailureJoiner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Executable contract that keeps {@link Parser#parseOn(Context)} (the slow,
 * result/failure building path) and {@link Parser#fastParseOn(String, int)}
 * (the allocation-free fast path) honest for every combinator.
 *
 * <p>Each case builds a combinator around observable scripted child parsers
 * (see {@link ScriptedParser}). Both paths are driven from the same starting
 * position against the same scripted transition table, and the test asserts
 * that:
 * <ul>
 *   <li>success and the final position agree on both paths;</li>
 *   <li>the observed child calls (identity and position, in order, including
 *       retries after choice backtracking and greedy limit probes) agree on
 *       both paths, so that neither the position arithmetic nor the rollback
 *       order can be changed on one side alone;</li>
 *   <li>on the slow path additionally the produced value, failure position
 *       and failure message match the recorded characterization.</li>
 * </ul>
 *
 * <p>This file is deliberately a characterization suite: it locks in the
 * semantics the two paths currently share. Where a scenario highlights an
 * asymmetry it is documented in place instead of silently picking one side as
 * the truth.
 */
public class FastParseContractTest {

  // ---------------------------------------------------------------------------
  // Observable child parser
  // ---------------------------------------------------------------------------

  /** A single scripted transition of a {@link ScriptedParser}. */
  private static final class Step {
    final int at;
    final boolean success;
    final int next;
    final Object value;
    final String message;

    private Step(int at, boolean success, int next, Object value,
        String message) {
      this.at = at;
      this.success = success;
      this.next = next;
      this.value = value;
      this.message = message;
    }

    static Step ok(int at, int next, Object value) {
      return new Step(at, true, next, value, null);
    }

    static Step okAt(int next, Object value) {
      return new Step(-1, true, next, value, null);
    }

    static Step failAt(int at, String message) {
      return new Step(at, false, at, null, message);
    }

    static Step failHere(String message) {
      return new Step(-1, false, -1, null, message);
    }
  }

  /**
   * A child parser whose behavior is fully scripted by the {@link Step}
   * table. Every invocation on either path is recorded together with the
   * position it was invoked at; this is what makes rollback order and
   * position plumbing observable.
   */
  private static final class ScriptedParser extends Parser {
    final String id;
    private final Recorder recorder;
    private final List<Step> table;
    private int cursor;

    ScriptedParser(String id, Recorder recorder, Step... steps) {
      this.id = id;
      this.recorder = recorder;
      this.table = new ArrayList<>(Arrays.asList(steps));
      this.cursor = 0;
    }

    void rewind() {
      cursor = 0;
    }

    private Step consume(int position) {
      if (cursor >= table.size()) {
        throw new AssertionError(id + " invoked " + (cursor + 1)
            + " times at " + position + ", only " + table.size()
            + " steps scripted");
      }
      Step step = table.get(cursor++);
      if (step.at >= 0 && step.at != position) {
        throw new AssertionError(id + " invocation #" + cursor
            + " expected at " + step.at + " but was at " + position);
      }
      return step;
    }

    @Override
    public Result parseOn(Context context) {
      int position = context.getPosition();
      Step step = consume(position);
      recorder.logSlow(id, position);
      if (step.success) {
        return context.success(step.value, step.next);
      }
      int failurePosition = step.next >= 0 ? step.next : position;
      return context.failure(step.message, failurePosition);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      Step step = consume(position);
      recorder.logFast(id, position);
      return step.success ? step.next : -1;
    }

    @Override
    public Parser copy() {
      throw new UnsupportedOperationException();
    }
  }

  /** Recorder shared by all scripted children participating in one case. */
  private static final class Recorder {
    final List<ScriptedParser> parsers = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    ScriptedParser parser(String id, Step... steps) {
      ScriptedParser parser = new ScriptedParser(id, this, steps);
      parsers.add(parser);
      return parser;
    }

    void logSlow(String id, int position) {
      events.add("S " + id + "@" + position);
    }

    void logFast(String id, int position) {
      events.add("F " + id + "@" + position);
    }

    void rewind() {
      events.clear();
      for (ScriptedParser parser : parsers) {
        parser.rewind();
      }
    }

    void assertExhausted() {
      for (ScriptedParser parser : parsers) {
        assertEquals("Unconsumed scripted steps in " + parser.id,
            parser.table.size(), parser.cursor);
      }
    }

    private List<String> positionsOnly() {
      List<String> result = new ArrayList<>();
      for (String event : events) {
        result.add(event.substring(2));
      }
      return result;
    }
  }

  // ---------------------------------------------------------------------------
  // Contract machinery
  // ---------------------------------------------------------------------------

  private Recorder recorder;

  /**
   * Drives both paths of {@code parser} on {@code input} starting at
   * {@code start} and asserts the shared contract.
   *
   * @param success expected outcome on both paths
   * @param end expected final position on success
   * @param failurePosition expected failure position on the slow path
   * @param message expected failure message, or {@code null} for success
   * @param value expected value on the slow path; ignored on failure
   * @param fastUsesFastChildren whether the combinator is expected to use the
   *     fast path of its children on its fast path (all allocation-free
   *     combinators do; action parsers with side effects deliberately fall
   *     back to the slow path, which is locked separately)
   */
  private void assertContract(Parser parser, String input, int start,
      boolean success, int end, int failurePosition, String message,
      Object value, boolean fastUsesFastChildren) {
    // Slow path.
    recorder.rewind();
    Result slow = parser.parseOn(new Context(input, start));
    List<String> slowEvents = new ArrayList<>(recorder.events);
    assertEquals(slowEvents.toString(), success, slow.isSuccess());
    if (success) {
      assertEquals("slow end position", end, slow.getPosition());
      assertEquals("slow value", value, slow.get());
    } else {
      assertEquals("slow failure position",
          failurePosition, slow.getPosition());
      assertEquals("slow failure message", message, slow.getMessage());
    }
    recorder.assertExhausted();

    // Fast path.
    recorder.rewind();
    int fast = parser.fastParseOn(input, start);
    List<String> fastEvents = new ArrayList<>(recorder.events);
    if (success) {
      assertEquals("fast end position", end, fast);
      assertTrue("fast path must report success", fast >= 0);
    } else {
      assertEquals("fast path must report failure", -1, fast);
    }
    recorder.assertExhausted();

    // Shared observable semantics: identical (child, position) calls in
    // identical order, and each side uses its own path of the children.
    List<String> slowPositions = stripMode(slowEvents);
    List<String> fastPositions = stripMode(fastEvents);
    assertEquals("child call sequence and positions",
        slowPositions, fastPositions);
    for (String event : slowEvents) {
      assertTrue("slow path must invoke slow children: " + event,
          event.startsWith("S "));
    }
    for (String event : fastEvents) {
      String expectedMode = fastUsesFastChildren ? "F " : "S ";
      assertTrue("fast path child mode: " + event,
          event.startsWith(expectedMode));
    }
  }

  private static List<String> stripMode(List<String> events) {
    List<String> result = new ArrayList<>();
    for (String event : events) {
      result.add(event.substring(2));
    }
    return result;
  }

  private Parser build(Supplier<Parser> supplier) {
    recorder = new Recorder();
    return supplier.get();
  }

  // ---------------------------------------------------------------------------
  // Sequence: threaded position, partial failure, zero width, empty input
  // ---------------------------------------------------------------------------

  @Test
  public void sequenceEmptySucceedsOnEmptyInput() {
    Parser parser = build(
        () -> new org.petitparser.parser.combinators.SequenceParser());
    assertContract(parser, "", 0, true, 0, -1, null,
        Collections.emptyList(), true);
  }

  @Test
  public void sequenceTwoChildrenThreadPositionFromMiddle() {
    Parser parser = build(() -> recorder.parser("a", Step.okAt(2, "A"))
        .seq(recorder.parser("b", Step.okAt(4, "B"))));
    assertContract(parser, "abcd", 1, true, 4, -1, null,
        Arrays.asList("A", "B"), true);
  }

  @Test
  public void sequenceStopsAtFirstFailureAfterConsumption() {
    Parser parser = build(() -> recorder.parser("a", Step.okAt(2, "A"))
        .seq(recorder.parser("b", Step.failAt(3, "b boom")),
            recorder.parser("c")));
    assertContract(parser, "abcd", 1, false, -1, 3, "b boom", null, true);
  }

  @Test
  public void sequenceFailsOnEmptyInput() {
    Parser parser = build(() -> recorder.parser("a",
        Step.failAt(0, "empty boom")));
    assertContract(parser, "", 0, false, -1, 0, "empty boom", null, true);
  }

  @Test
  public void sequenceAcceptsZeroWidthSuccesses() {
    Parser parser = build(() -> recorder.parser("a", Step.okAt(0, "A"))
        .seq(recorder.parser("b", Step.okAt(0, "B")),
            recorder.parser("c", Step.okAt(1, "C"))));
    assertContract(parser, "x", 0, true, 1, -1, null,
        Arrays.asList("A", "B", "C"), true);
  }

  // ---------------------------------------------------------------------------
  // Choice: ordered trial, rollback to the same start, failure joining
  // ---------------------------------------------------------------------------

  @Test
  public void choiceFirstSucceedsWithoutTryingRest() {
    Parser parser = build(() -> recorder.parser("a", Step.okAt(2, "A"))
        .or(recorder.parser("b"), recorder.parser("c")));
    assertContract(parser, "abcd", 0, true, 2, -1, null, "A", true);
  }

  @Test
  public void choiceRetriesFromOriginalPositionAfterRollback() {
    Parser parser = build(() -> recorder.parser("a",
            Step.failAt(2, "a boom"))
        .or(recorder.parser("b",
                Step.failAt(0, "b boom")),
            recorder.parser("c", Step.okAt(3, "C"))));
    // Every child is invoked at the original position 1, even though the
    // first child reported its failure at position 2: no position leaks
    // across alternatives on either path.
    assertContract(parser, "abcd", 1, true, 3, -1, null, "C", true);
  }

  @Test
  public void choiceAllFailReturnsJoinedFailureLast() {
    Parser parser = build(() -> recorder.parser("a",
            Step.failAt(1, "a boom"))
        .or(recorder.parser("b", Step.failAt(2, "b boom"))));
    assertContract(parser, "abcd", 0, false, -1, 2, "b boom", null, true);
  }

  @Test
  public void choiceAllFailReturnsJoinedFailureFirst() {
    Parser parser = build(() ->
        new org.petitparser.parser.combinators.ChoiceParser(
            new FailureJoiner.SelectFirst(),
            recorder.parser("a", Step.failAt(1, "a boom")),
            recorder.parser("b", Step.failAt(2, "b boom"))));
    assertContract(parser, "abcd", 0, false, -1, 1, "a boom", null, true);
  }

  @Test
  public void choiceAllFailReturnsFarthestFailure() {
    Parser parser = build(() ->
        new org.petitparser.parser.combinators.ChoiceParser(
            new FailureJoiner.SelectFarthest(),
            recorder.parser("a", Step.failAt(1, "a boom")),
            recorder.parser("b", Step.failAt(3, "b boom")),
            recorder.parser("c", Step.failAt(2, "c boom"))));
    assertContract(parser, "abcd", 0, false, -1, 3, "b boom", null, true);
  }

  @Test
  public void choiceEmptyInputAllFail() {
    Parser parser = build(() -> recorder.parser("a", Step.failAt(0, "no a"))
        .or(recorder.parser("b", Step.failAt(0, "no b"))));
    assertContract(parser, "", 0, false, -1, 0, "no b", null, true);
  }

  // ---------------------------------------------------------------------------
  // Positive and negative lookahead: always zero width
  // ---------------------------------------------------------------------------

  @Test
  public void andSucceedsZeroWidthAndKeepsValue() {
    Parser parser = build(() -> recorder.parser("a", Step.okAt(3, "A")).and());
    assertContract(parser, "abcd", 1, true, 1, -1, null, "A", true);
  }

  @Test
  public void andPropagatesFailure() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(4, "and boom")).and());
    assertContract(parser, "abcd", 1, false, -1, 4, "and boom", null, true);
  }

  @Test
  public void notSucceedsZeroWidthWhenDelegateFails() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(2, "unexpected thing")).not("unexpected"));
    assertContract(parser, "abcd", 1, true, 1, -1, null, null, true);
  }

  @Test
  public void notFailsAtOwnPositionIgnoringDelegateFailurePosition() {
    // Characterization: the not-predicate failure is always reported at the
    // position not() was invoked at, regardless of where its delegate
    // failed; the fast path only has success/failure semantics and agrees.
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(3, "A")).not("unexpected"));
    assertContract(parser, "abcd", 1, false, -1, 1, "unexpected", null, true);
  }

  @Test
  public void notOnEmptyInput() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "deeper")).not("unexpected"));
    assertContract(parser, "", 0, true, 0, -1, null, null, true);
  }

  @Test
  public void nestedNotAndLookahead() {
    // and(not(a)): not succeeds zero width, and succeeds zero width.
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "x")).not("unexpected").and());
    assertContract(parser, "", 0, true, 0, -1, null, null, true);
  }

  // ---------------------------------------------------------------------------
  // Optional
  // ---------------------------------------------------------------------------

  @Test
  public void optionalConsumesOnSuccess() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(2, "A")).optional("DEF"));
    assertContract(parser, "abcd", 0, true, 2, -1, null, "A", true);
  }

  @Test
  public void optionalSucceedsZeroWidthWithOtherwiseOnFailure() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(3, "boom")).optional("DEF"));
    assertContract(parser, "abcd", 1, true, 1, -1, null, "DEF", true);
  }

  @Test
  public void optionalZeroWidthSuccessAtEndOfInput() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(0, null)).optional());
    assertContract(parser, "", 0, true, 0, -1, null, null, true);
  }

  // ---------------------------------------------------------------------------
  // Possessive repeat: min/max boundaries, zero-width, failure, end of input
  // ---------------------------------------------------------------------------

  @Test
  public void possessiveStarEmptyInputSucceedsZeroWidth() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "end")).star());
    assertContract(parser, "", 0, true, 0, -1, null,
        Collections.emptyList(), true);
  }

  @Test
  public void possessiveStarConsumesUntilFailureAtBoundary() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"), Step.okAt(3, "C"),
            Step.failAt(3, "stop"))
        .star());
    // The delegate failing after consuming zero characters terminates the
    // repetition at position 3; that final failure is observed on both
    // paths.
    assertContract(parser, "abc", 0, true, 3, -1, null,
        Arrays.asList("A", "B", "C"), true);
  }

  @Test
  public void possessivePlusFailsWhenMinNotReached() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "need one")).plus());
    assertContract(parser, "", 0, false, -1, 0, "need one", null, true);
  }

  @Test
  public void possessiveExactCountSucceedsAtBoundary() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"),
            Step.failAt(2, "stop"))
        .times(2));
    assertContract(parser, "ab", 0, true, 2, -1, null,
        Arrays.asList("A", "B"), true);
  }

  @Test
  public void possessiveRepeatZeroWidthDelegateFailsMin() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "zw fail")).repeat(2, 3));
    assertContract(parser, "x", 0, false, -1, 0, "zw fail", null, true);
  }

  @Test
  public void possessiveRepeatZeroWidthSuccessTerminatesAtMax() {
    // A zero-width successful delegate with a bounded count must terminate
    // after exactly max repetitions and return the start position; an
    // unbounded repeat of zero-width success is intentionally not modeled
    // (it would loop forever in the current implementation, on both paths).
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(0, "Z"), Step.okAt(0, "Z"),
            Step.okAt(0, "Z"))
        .repeat(2, 3));
    assertContract(parser, "x", 0, true, 0, -1, null,
        Arrays.asList("Z", "Z", "Z"), true);
  }

  @Test
  public void possessiveRepeatStartedFromMiddle() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(3, "A"), Step.failAt(3, "stop"))
        .repeat(1, 2));
    assertContract(parser, "abcde", 2, true, 3, -1, null,
        Collections.singletonList("A"), true);
  }

  // ---------------------------------------------------------------------------
  // Greedy repeat: aggressive consumption followed by limit backtracking
  // ---------------------------------------------------------------------------

  @Test
  public void greedyStarLimitSucceedsAtFurthestPosition() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"),
            Step.failAt(2, "stop"))
        .starGreedy(recorder.parser("q", Step.ok(2, 2, "Q"))));
    assertContract(parser, "aab", 0, true, 2, -1, null,
        Arrays.asList("A", "B"), true);
  }

  @Test
  public void greedyStarBacktracksLimitProbesInReverseOrder() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"),
            Step.failAt(2, "stop"))
        .starGreedy(recorder.parser("q",
            Step.failAt(2, "q2"), Step.ok(1, 1, "Q"))));
    // Limit probes run furthest-to-earliest; one backtrack pops the last
    // element. The shared event list locks that probe/rollback order for
    // both paths.
    assertContract(parser, "aab", 0, true, 1, -1, null,
        Collections.singletonList("A"), true);
  }

  @Test
  public void greedyStarExhaustionReturnsLastLimitFailure() {
    Parser parser = build(() -> recorder
        .parser("a",
            Step.okAt(1, "A"), Step.okAt(2, "B"), Step.okAt(3, "C"),
            Step.failAt(3, "stop"))
        .starGreedy(recorder.parser("q",
            Step.failAt(3, "q3"), Step.failAt(2, "q2"),
            Step.failAt(1, "q1"), Step.failAt(0, "q0"))));
    assertContract(parser, "aaa", 0, false, -1, 0, "q0", null, true);
  }

  @Test
  public void greedyPlusMinFailureShortCircuitsBeforeLimit() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "need more"))
        .plusGreedy(recorder.parser("q")));
    assertContract(parser, "aaa", 0, false, -1, 0, "need more", null, true);
  }

  @Test
  public void greedyStarLimitMatchesBeforeAnyConsumption() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "no a"))
        .starGreedy(recorder.parser("q", Step.ok(0, 0, "Q"))));
    assertContract(parser, "aab", 0, true, 0, -1, null,
        Collections.emptyList(), true);
  }

  @Test
  public void greedyRepeatZeroWidthLimitAtStartFromMiddle() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(2, "no a"))
        .repeatGreedy(recorder.parser("q", Step.ok(2, 2, "Q")), 0, 2));
    assertContract(parser, "xxab", 2, true, 2, -1, null,
        Collections.emptyList(), true);
  }

  // ---------------------------------------------------------------------------
  // Lazy repeat: limit probed before each repetition
  // ---------------------------------------------------------------------------

  @Test
  public void lazyStarConsumesUntilLimitAfterTwoIterations() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"))
        .starLazy(recorder.parser("q",
            Step.failAt(0, "q0"), Step.failAt(1, "q1"),
            Step.ok(2, 2, "Q"))));
    // Limit is probed first, then the delegate: probe/delegate interleaving
    // must be identical on both paths.
    assertContract(parser, "aab", 0, true, 2, -1, null,
        Arrays.asList("A", "B"), true);
  }

  @Test
  public void lazyStarLimitMatchesImmediately() {
    Parser parser = build(() -> recorder
        .parser("a")
        .starLazy(recorder.parser("q", Step.ok(0, 0, "Q"))));
    assertContract(parser, "aab", 0, true, 0, -1, null,
        Collections.emptyList(), true);
  }

  @Test
  public void lazyPlusMinFailureBeforeAnyLimitProbe() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "need more"))
        .plusLazy(recorder.parser("q")));
    assertContract(parser, "aab", 0, false, -1, 0, "need more", null, true);
  }

  @Test
  public void lazyRepeatMaxReachedReturnsLimitFailure() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"))
        .repeatLazy(recorder.parser("q",
            Step.failAt(0, "q0"), Step.failAt(1, "q1"),
            Step.failAt(2, "q2")), 0, 2));
    assertContract(parser, "aab", 0, false, -1, 2, "q2", null, true);
  }

  @Test
  public void lazyRepeatStartedFromMiddle() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(4, "A"))
        .repeatLazy(recorder.parser("q",
            Step.failAt(3, "q3"), Step.ok(4, 4, "Q")), 0, 3));
    assertContract(parser, "abcde", 3, true, 4, -1, null,
        Collections.singletonList("A"), true);
  }

  // ---------------------------------------------------------------------------
  // Flatten, token, action nesting
  // ---------------------------------------------------------------------------

  @Test
  public void flattenReportsConsumedSubstring() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(4, Arrays.asList("X", "Y"))).flatten());
    assertContract(parser, "abcd", 1, true, 4, -1, null, "bcd", true);
  }

  @Test
  public void flattenZeroWidthReturnsEmptyString() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(2, "V")).flatten());
    assertContract(parser, "ab", 2, true, 2, -1, null, "", true);
  }

  @Test
  public void flattenPropagatesChildFailure() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(3, "deep boom")).flatten());
    assertContract(parser, "abcd", 1, false, -1, 3, "deep boom", null, true);
  }

  @Test
  public void flattenWithMessageFailsAtStartWithOwnMessage() {
    // Characterization: flatten(message) drives its delegate through the
    // fast path and, on failure, reports its own message at the flatten
    // start position (losing the delegate failure position/message). Both
    // entry points of the resulting parser share that behavior.
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(3, "deep boom"))
        .flatten("flat boom"));
    recorder.events.clear();
    Result slow = parser.parseOn(new Context("abcd", 1));
    assertFalse(slow.isSuccess());
    assertEquals(1, slow.getPosition());
    assertEquals("flat boom", slow.getMessage());
    List<String> flattenEvents = new ArrayList<>(recorder.events);
    recorder.rewind();
    assertEquals(-1, parser.fastParseOn("abcd", 1));
    assertEquals(stripMode(flattenEvents), stripMode(recorder.events));
  }

  @Test
  public void tokenWrapsValueRangeAndInput() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(4, "V")).token());
    recorder.rewind();
    Result slow = parser.parseOn(new Context("abcd", 1));
    assertTrue(slow.isSuccess());
    Token token = slow.get();
    assertEquals("abcd", token.getBuffer());
    assertEquals(1, token.getStart());
    assertEquals(4, token.getStop());
    assertEquals("bcd", token.getInput());
    assertEquals("V", token.getValue());
    recorder.assertExhausted();
    recorder.rewind();
    assertEquals(4, parser.fastParseOn("abcd", 1));
    recorder.assertExhausted();
  }

  @Test
  public void flattenTokenActionNested() {
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(4, "raw"))
        .flatten()
        .token()
        .map(value -> ((Token) value).getInput().toUpperCase()));
    assertContract(parser, "abcd", 1, true, 4, -1, null, "BCD", true);
  }

  @Test
  public void tokenFailurePropagates() {
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(0, "no token")).token());
    assertContract(parser, "", 0, false, -1, 0, "no token", null, true);
  }

  // ---------------------------------------------------------------------------
  // Trimming and end of input
  // ---------------------------------------------------------------------------

  @Test
  public void trimmingConsumesBothSidesAndKeepsValue() {
    Parser parser = build(() -> {
      Parser left = of(' ');
      Parser right = of(' ');
      return new org.petitparser.parser.actions.TrimmingParser(
          recorder.parser("a", Step.okAt(3, "A")), left, right);
    });
    assertContract(parser, "  A  x", 0, true, 5, -1, null, "A", true);
  }

  @Test
  public void trimmingFailureLeavesFailureOfDelegate() {
    Parser parser = build(() -> new org.petitparser.parser.actions
        .TrimmingParser(
        recorder.parser("a", Step.failAt(1, "delegate boom")),
        of(' '), of(' ')));
    assertContract(parser, " A", 0, false, -1, 1, "delegate boom", null, true);
  }

  @Test
  public void endOfInputSucceedsOnEmptyInput() {
    Parser parser = build(
        () -> new org.petitparser.parser.combinators.EndOfInputParser(
            "end of input expected"));
    assertContract(parser, "", 0, true, 0, -1, null, null, true);
  }

  @Test
  public void endOfInputFailsBeforeEnd() {
    Parser parser = build(
        () -> new org.petitparser.parser.combinators.EndOfInputParser(
            "end of input expected"));
    assertContract(parser, "abc", 1, false, -1, 1, "end of input expected",
        null, true);
  }

  @Test
  public void endOfInputSucceedsStartedAtBoundaryFromMiddle() {
    Parser parser = build(
        () -> new org.petitparser.parser.combinators.EndOfInputParser(
            "end of input expected"));
    assertContract(parser, "abc", 3, true, 3, -1, null, null, true);
  }

  // ---------------------------------------------------------------------------
  // Settable delegation
  // ---------------------------------------------------------------------------

  @Test
  public void settableDelegatesBothPaths() {
    Parser parser = build(() -> {
      org.petitparser.parser.combinators.SettableParser settable =
          org.petitparser.parser.combinators.SettableParser
              .with(recorder.parser("a", Step.okAt(2, "A")));
      return settable;
    });
    assertContract(parser, "abcd", 1, true, 2, -1, null, "A", true);
  }

  // ---------------------------------------------------------------------------
  // Action side-effect contract
  //
  // A pure map() must not evaluate its function on the fast path; an action
  // created with mapWithSideEffects() must. This is an explicit, locked
  // characterization of the current behavior and must not change silently
  // while refactoring the internal transitions.
  // ---------------------------------------------------------------------------

  @Test
  public void pureMapFastPathDoesNotRunAction() {
    int[] calls = {0};
    Parser parser = build(() -> recorder.parser("a", Step.okAt(4, "A"))
        .map(value -> {
          calls[0]++;
          return value + "!";
        }));

    recorder.rewind();
    Result slow = parser.parseOn(new Context("abcd", 1));
    assertEquals("A!", slow.get());
    assertEquals("slow path runs the action once", 1, calls[0]);
    recorder.assertExhausted();

    recorder.rewind();
    calls[0] = 0;
    int fast = parser.fastParseOn("abcd", 1);
    assertEquals(4, fast);
    assertEquals("fast path must skip the pure action", 0, calls[0]);
    assertEquals(Collections.singletonList("F a@1"), recorder.events);
    recorder.assertExhausted();
  }

  @Test
  public void pureMapInsideRepeatNeverRunsOnFastPath() {
    int[] calls = {0};
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(1, "A"), Step.okAt(2, "B"),
            Step.failAt(2, "stop"))
        .star()
        .map(value -> {
          calls[0]++;
          return value;
        }));

    recorder.rewind();
    Result slow = parser.parseOn(new Context("ab", 0));
    assertTrue(slow.isSuccess());
    assertEquals("star runs on slow path, outer map runs once", 1, calls[0]);
    recorder.assertExhausted();

    recorder.rewind();
    calls[0] = 0;
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("neither star nor map evaluate on the fast path",
        0, calls[0]);
    recorder.assertExhausted();
  }

  @Test
  public void sideEffectingMapFastPathRunsActionExactlyOncePerSuccess() {
    int[] calls = {0};
    Parser parser = build(() -> recorder
        .parser("a", Step.okAt(2, "A"))
        .mapWithSideEffects(value -> {
          calls[0]++;
          return value + "!";
        }));

    recorder.rewind();
    Result slow = parser.parseOn(new Context("abcd", 1));
    assertEquals("A!", slow.get());
    assertEquals(1, calls[0]);
    recorder.assertExhausted();

    // The side-effecting action deliberately falls back to the full parse:
    // the action runs and the child is observed through its slow path.
    recorder.rewind();
    calls[0] = 0;
    int fast = parser.fastParseOn("abcd", 1);
    assertEquals(2, fast);
    assertEquals("fast path must execute the side effect", 1, calls[0]);
    assertEquals(Collections.singletonList("S a@1"), recorder.events);
    recorder.assertExhausted();
  }

  @Test
  public void sideEffectingMapInsideChoiceRunsActionForSucceedingBranch() {
    int[] firstCalls = {0};
    int[] secondCalls = {0};
    Parser parser = build(() -> recorder
        .parser("a", Step.failAt(2, "a boom"))
        .mapWithSideEffects(value -> {
          firstCalls[0]++;
          return value;
        })
        .or(recorder.parser("b", Step.okAt(3, "B"))
            .mapWithSideEffects(value -> {
              secondCalls[0]++;
              return value;
            })));
    recorder.rewind();
    firstCalls[0] = secondCalls[0] = 0;
    assertEquals(3, parser.fastParseOn("abcd", 1));
    assertEquals("failed branch action must not run", 0, firstCalls[0]);
    assertEquals("succeeding branch action runs on fast path",
        1, secondCalls[0]);
    recorder.assertExhausted();
  }

  @Test
  public void actionNotInvokedOnFailureEitherPath() {
    int[] pureCalls = {0};
    int[] effectCalls = {0};
    recorder = new Recorder();
    Parser pure = recorder.parser("a", Step.failAt(2, "boom"))
        .map(value -> {
          pureCalls[0]++;
          return value;
        });
    Parser effecting = recorder.parser("b", Step.failAt(2, "boom"))
        .mapWithSideEffects(value -> {
          effectCalls[0]++;
          return value;
        });

    recorder.rewind();
    assertTrue(pure.parseOn(new Context("abcd", 1)).isFailure());
    assertTrue(effecting.parseOn(new Context("abcd", 1)).isFailure());
    assertEquals(0, pureCalls[0]);
    assertEquals(0, effectCalls[0]);
    recorder.assertExhausted();

    recorder.rewind();
    assertEquals(-1, pure.fastParseOn("abcd", 1));
    assertEquals(-1, effecting.fastParseOn("abcd", 1));
    assertEquals(0, pureCalls[0]);
    assertEquals(0, effectCalls[0]);
    recorder.assertExhausted();
  }
}
