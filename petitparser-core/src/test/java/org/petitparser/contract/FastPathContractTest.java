package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.AndParser;
import org.petitparser.parser.combinators.ChoiceParser;
import org.petitparser.parser.combinators.EndOfInputParser;
import org.petitparser.parser.combinators.NotParser;
import org.petitparser.parser.combinators.OptionalParser;
import org.petitparser.parser.combinators.SequenceParser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.actions.FlattenParser;
import org.petitparser.parser.actions.TokenParser;
import org.petitparser.parser.actions.TrimmingParser;
import org.petitparser.parser.repeating.GreedyRepeatingParser;
import org.petitparser.parser.repeating.LazyRepeatingParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;
import org.petitparser.utils.FailureJoiner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Characterizes and enforces the contract between the two parser entry points
 * {@link Parser#parseOn(Context)} and {@link Parser#fastParseOn(String, int)}.
 *
 * <p>For every combinator and every scenario both paths must agree on:
 * <ul>
 *   <li>whether the parse succeeds or fails;</li>
 *   <li>the final position on success ({@code -1} on the fast path);</li>
 *   <li>the exact order of child invocations and the positions they are
 *       (re)started at, which pins down choice rollback, sequence partial
 *       failure and repeating backtracking order.</li>
 * </ul>
 *
 * <p>For the slow path the produced value, the failure position and the failure
 * message are additionally asserted. Scenarios cover empty input, starting in
 * the middle of the input, boundary-exact successes, child failure after
 * consumption, zero-width successes, all repeating flavors, both lookahead
 * predicates and nested flatten/token/action parsers.
 */
public class FastPathContractTest {

  /** Sentinel for values that are not expected to be asserted. */
  private static final Object NO_VALUE = new Object();

  // ------------------------------------------------------------------
  // Shared harness
  // ------------------------------------------------------------------

  /**
   * Runs both entry points on freshly built parsers and compares the observable
   * behavior. Both builds share the supplied scripted child parsers, whose
   * invocation traces are reset in between the two runs.
   */
  private static void assertContract(
      String label,
      Function<List<ObservableParser>, Parser> build,
      List<ObservableParser> children,
      String input, int start,
      boolean expectedSuccess, int expectedEnd,
      Object expectedValue) {
    assertContract(label, build, children, input, start, expectedSuccess,
        expectedEnd, expectedValue, -1, null);
  }

  private static void assertContract(
      String label,
      Function<List<ObservableParser>, Parser> build,
      List<ObservableParser> children,
      String input, int start,
      boolean expectedSuccess, int expectedEnd,
      Object expectedValue,
      int expectedFailurePosition, String expectedFailureMessage) {

    // Slow path.
    for (ObservableParser child : children) {
      child.resetTrace();
    }
    Parser slowParser = build.apply(children);
    Result slow = slowParser.parseOn(new Context(input, start));
    List<Integer> slowTrace = invocationPositions(children);

    // Fast path.
    for (ObservableParser child : children) {
      child.resetTrace();
    }
    Parser fastParser = build.apply(children);
    int fast = fastParser.fastParseOn(input, start);
    List<Integer> fastTrace = invocationPositions(children);

    // Shared semantics: success flag.
    if (expectedSuccess) {
      assertTrue(label + ": slow success", slow.isSuccess());
      assertFalse(label + ": slow failure", slow.isFailure());
      assertTrue(label + ": fast success, got " + fast, fast >= 0);
      assertEquals(label + ": end position", expectedEnd, slow.getPosition());
      assertEquals(label + ": fast end position", expectedEnd, fast);
      assertNull(label + ": no failure message", slow.getMessage());
      if (expectedValue != NO_VALUE) {
        assertEquals(label + ": value", expectedValue, slow.get());
      }
    } else {
      assertTrue(label + ": slow failure", slow.isFailure());
      assertFalse(label + ": slow success", slow.isSuccess());
      assertEquals(label + ": fast failure", -1, fast);
      assertEquals(label + ": failure position",
          expectedFailurePosition, slow.getPosition());
      assertEquals(label + ": failure message",
          expectedFailureMessage, slow.getMessage());
    }

    // Shared semantics: child invocation order and (rolled back) positions.
    assertEquals(label + ": child invocation positions",
        slowTrace, fastTrace);
  }

  private static List<Integer> invocationPositions(
      List<ObservableParser> children) {
    List<Integer> positions = new ArrayList<>();
    for (ObservableParser child : children) {
      positions.addAll(child.slowOrFastStartPositions());
    }
    return positions;
  }

  private static List<ObservableParser> children(ObservableParser... parsers) {
    return new ArrayList<>(Arrays.asList(parsers));
  }

  private static ObservableParser obs() {
    return new ObservableParser();
  }

  // ------------------------------------------------------------------
  // Sequence
  // ------------------------------------------------------------------

  @Test
  public void testSequenceEmptyInput() {
    ObservableParser child = obs().failAt(0, "boom");
    assertContract("sequence/empty",
        c -> new SequenceParser(c.get(0)),
        children(child), "", 0, false, -1, NO_VALUE, 0, "boom");
  }

  @Test
  public void testSequenceMiddleStartAllSucceed() {
    ObservableParser first = obs().succeedAt(2);
    ObservableParser second = obs().succeedAt(3);
    assertContract("sequence/middle",
        c -> new SequenceParser(c.get(0), c.get(1)),
        children(first, second), "abc", 1, true, 3,
        Arrays.asList(1001, 1002));
  }

  @Test
  public void testSequenceBoundaryExact() {
    ObservableParser first = obs().succeedAt(3);
    ObservableParser second = obs().succeedAt(4);
    assertContract("sequence/boundary",
        c -> new SequenceParser(c.get(0), c.get(1)),
        children(first, second), "abcd", 2, true, 4,
        Arrays.asList(1002, 1003));
  }

  @Test
  public void testSequenceSecondChildFailsAfterConsumption() {
    ObservableParser first = obs().succeedAt(2);
    ObservableParser second = obs().failAt(3, "later");
    assertContract("sequence/partial-failure",
        c -> new SequenceParser(c.get(0), c.get(1)),
        children(first, second), "abcd", 1, false, -1, NO_VALUE, 3,
        "later");
  }

  @Test
  public void testSequenceZeroWidthSuccesses() {
    ObservableParser first = obs().succeedAt(2);
    ObservableParser second = obs().succeedAt(2);
    assertContract("sequence/zero-width",
        c -> new SequenceParser(c.get(0), c.get(1)),
        children(first, second), "abcd", 2, true, 2,
        Arrays.asList(1002, 1002));
  }

  @Test
  public void testSequenceEmpty() {
    assertContract("sequence/empty-list",
        c -> new SequenceParser(),
        children(), "abc", 1, true, 1, new ArrayList<>());
  }

  // ------------------------------------------------------------------
  // Choice
  // ------------------------------------------------------------------

  @Test
  public void testChoiceRollbackToSecondAlternative() {
    ObservableParser first = obs().failAt(0, "one");
    ObservableParser second = obs().succeedAt(2);
    assertContract("choice/rollback",
        c -> new ChoiceParser(c.get(0), c.get(1)),
        children(first, second), "ab", 0, true, 2, 1000);
  }

  @Test
  public void testChoiceAllFailSelectsLastFailure() {
    ObservableParser first = obs().failAt(0, "one");
    ObservableParser second = obs().failAt(2, "two");
    assertContract("choice/all-fail",
        c -> new ChoiceParser(c.get(0), c.get(1)),
        children(first, second), "ab", 0, false, -1, NO_VALUE, 2, "two");
  }

  @Test
  public void testChoiceAllFailSelectFirstFailure() {
    ObservableParser first = obs().failAt(0, "one");
    ObservableParser second = obs().failAt(2, "two");
    assertContract("choice/all-fail/first",
        c -> new ChoiceParser(new FailureJoiner.SelectFirst(), c.get(0),
            c.get(1)),
        children(first, second), "ab", 0, false, -1, NO_VALUE, 0, "one");
  }

  @Test
  public void testChoiceEmptyInput() {
    ObservableParser first = obs().failAt(0, "one");
    ObservableParser second = obs().failAt(0, "two");
    assertContract("choice/empty",
        c -> new ChoiceParser(c.get(0), c.get(1)),
        children(first, second), "", 0, false, -1, NO_VALUE, 0, "two");
  }

  @Test
  public void testChoiceMiddleStartZeroWidth() {
    ObservableParser first = obs().failAt(2, "one");
    ObservableParser second = obs().succeedAt(2);
    assertContract("choice/middle-zero-width",
        c -> new ChoiceParser(c.get(0), c.get(1)),
        children(first, second), "abcd", 2, true, 2, 1002);
  }

  // ------------------------------------------------------------------
  // Optional
  // ------------------------------------------------------------------

  @Test
  public void testOptionalPresent() {
    ObservableParser child = obs().succeedAt(3);
    assertContract("optional/present",
        c -> new OptionalParser(c.get(0), null),
        children(child), "abc", 1, true, 3, 1001);
  }

  @Test
  public void testOptionalAbsentKeepsPosition() {
    ObservableParser child = obs().failAt(2, "nope");
    assertContract("optional/absent",
        c -> new OptionalParser(c.get(0), null),
        children(child), "abc", 2, true, 2, null);
  }

  @Test
  public void testOptionalAbsentWithOtherwise() {
    ObservableParser child = obs().failAt(0, "nope");
    assertContract("optional/otherwise",
        c -> new OptionalParser(c.get(0), "else"),
        children(child), "", 0, true, 0, "else");
  }

  // ------------------------------------------------------------------
  // Lookahead
  // ------------------------------------------------------------------

  @Test
  public void testAndSucceedsZeroWidth() {
    ObservableParser child = obs().succeedAt(3);
    assertContract("and/success",
        c -> new AndParser(c.get(0)),
        children(child), "abc", 1, true, 1, 1001);
  }

  @Test
  public void testAndPropagatesFailure() {
    ObservableParser child = obs().failAt(3, "blocked");
    assertContract("and/failure",
        c -> new AndParser(c.get(0)),
        children(child), "abc", 1, false, -1, NO_VALUE, 3, "blocked");
  }

  @Test
  public void testNotSucceedsZeroWidth() {
    ObservableParser child = obs().failAt(2, "blocked");
    assertContract("not/success",
        c -> new NotParser(c.get(0), "unexpected"),
        children(child), "abc", 2, true, 2, null);
  }

  @Test
  public void testNotFailsAtStartPosition() {
    ObservableParser child = obs().succeedAt(2);
    assertContract("not/failure",
        c -> new NotParser(c.get(0), "unexpected"),
        children(child), "abc", 1, false, -1, NO_VALUE, 1, "unexpected");
  }

  // ------------------------------------------------------------------
  // End of input, delegate, settable
  // ------------------------------------------------------------------

  @Test
  public void testEndOfInputAtBoundary() {
    assertContract("end/at-end",
        c -> new EndOfInputParser("end of input expected"),
        children(), "abc", 3, true, 3, null);
  }

  @Test
  public void testEndOfInputInMiddle() {
    assertContract("end/middle",
        c -> new EndOfInputParser("end of input expected"),
        children(), "abc", 1, false, -1, NO_VALUE, 1,
        "end of input expected");
  }

  @Test
  public void testSettableDelegates() {
    ObservableParser success = obs().succeedAt(2);
    ObservableParser failure = obs().failAt(0, "gone");
    assertContract("settable/success",
        c -> SettableParser.with(c.get(0)),
        children(success), "ab", 0, true, 2, 1000);
    assertContract("settable/failure",
        c -> SettableParser.with(c.get(0)),
        children(failure), "ab", 0, false, -1, NO_VALUE, 0, "gone");
  }

  // ------------------------------------------------------------------
  // Possessive repeat
  // ------------------------------------------------------------------

  @Test
  public void testPossessiveStarConsumesUntilFailure() {
    ObservableParser child = obs()
        .succeedAt(1).succeedAt(2).succeedAt(3).failAt(3, "stop");
    assertContract("possessive/star",
        c -> new PossessiveRepeatingParser(c.get(0), 0,
            PossessiveRepeatingParser.UNBOUNDED),
        children(child), "abc", 0, true, 3,
        Arrays.asList(1000, 2001, 3002));
  }

  @Test
  public void testPossessivePlusBoundaryFailure() {
    ObservableParser child = obs().failAt(1, "need-one");
    assertContract("possessive/plus-min-failure",
        c -> new PossessiveRepeatingParser(c.get(0), 1,
            PossessiveRepeatingParser.UNBOUNDED),
        children(child), "abc", 0, false, -1, NO_VALUE, 1, "need-one");
  }

  @Test
  public void testPossessiveBoundedExactMax() {
    ObservableParser child = obs()
        .succeedAt(1).succeedAt(2).succeedAt(3);
    assertContract("possessive/bounded",
        c -> new PossessiveRepeatingParser(c.get(0), 2, 2),
        children(child), "abc", 0, true, 2,
        Arrays.asList(1000, 2001));
  }

  @Test
  public void testPossessiveZeroWidthStarAtEnd() {
    // A zero-width success followed by a failure must not loop forever; the
    // terminal failure produces the elements collected so far.
    ObservableParser child = obs().succeedAt(3).failAt(3, "stop");
    assertContract("possessive/zero-width",
        c -> new PossessiveRepeatingParser(c.get(0), 0,
            PossessiveRepeatingParser.UNBOUNDED),
        children(child), "abc", 3, true, 3, Arrays.asList(1003));
  }

  @Test
  public void testPossessiveMinGreaterThanAvailable() {
    ObservableParser child = obs().succeedAt(1).failAt(1, "stop");
    assertContract("possessive/min-unmet",
        c -> new PossessiveRepeatingParser(c.get(0), 2,
            PossessiveRepeatingParser.UNBOUNDED),
        children(child), "a", 0, false, -1, NO_VALUE, 1, "stop");
  }

  // ------------------------------------------------------------------
  // Lazy repeat
  // ------------------------------------------------------------------

  @Test
  public void testLazyStopsAtEarliestLimit() {
    ObservableParser child = obs()
        .succeedAt(1).succeedAt(2).succeedAt(3);
    ObservableParser limit = obs()
        .failAt(0, "l0").failAt(1, "l1").succeedAt(2);
    assertContract("lazy/earliest",
        c -> new LazyRepeatingParser(c.get(0), c.get(1), 0,
            LazyRepeatingParser.UNBOUNDED),
        children(child, limit), "abc", 0, true, 2,
        Arrays.asList(1000, 2001));
  }

  @Test
  public void testLazyMinFailure() {
    ObservableParser child = obs().failAt(0, "need");
    ObservableParser limit = obs().succeedAt(0);
    assertContract("lazy/min-failure",
        c -> new LazyRepeatingParser(c.get(0), c.get(1), 1,
            LazyRepeatingParser.UNBOUNDED),
        children(child, limit), "", 0, false, -1, NO_VALUE, 0, "need");
  }

  @Test
  public void testLazyLimitNeverMatchesAndDelegateDies() {
    ObservableParser child = obs().succeedAt(1).failAt(1, "no-more");
    ObservableParser limit = obs().failAt(0, "l0").failAt(1, "l1");
    assertContract("lazy/dead-end",
        c -> new LazyRepeatingParser(c.get(0), c.get(1), 0,
            LazyRepeatingParser.UNBOUNDED),
        children(child, limit), "a", 0, false, -1, NO_VALUE, 1, "l1");
  }

  @Test
  public void testLazyMaxExceeded() {
    ObservableParser child = obs().succeedAt(1).succeedAt(2);
    ObservableParser limit = obs().failAt(0, "l0").failAt(1, "l1");
    assertContract("lazy/max-exceeded",
        c -> new LazyRepeatingParser(c.get(0), c.get(1), 0, 1),
        children(child, limit), "ab", 0, false, -1, NO_VALUE, 1, "l1");
  }

  // ------------------------------------------------------------------
  // Greedy repeat
  // ------------------------------------------------------------------

  @Test
  public void testGreedyBacktracksToLimit() {
    // The delegate can consume all three characters, but the limit only
    // succeeds after two, so the parser must backtrack once and restart the
    // limit at the earlier position.
    ObservableParser child = obs()
        .succeedAt(1).succeedAt(2).succeedAt(3).failAt(3, "stop");
    ObservableParser limit = obs().failAt(3, "l3").succeedAt(2);
    assertContract("greedy/backtrack",
        c -> new GreedyRepeatingParser(c.get(0), c.get(1), 0,
            GreedyRepeatingParser.UNBOUNDED),
        children(child, limit), "abc", 0, true, 2,
        Arrays.asList(1000, 2001));
  }

  @Test
  public void testGreedyMinUnmet() {
    ObservableParser child = obs().failAt(0, "need");
    ObservableParser limit = obs().succeedAt(0);
    assertContract("greedy/min-failure",
        c -> new GreedyRepeatingParser(c.get(0), c.get(1), 1,
            GreedyRepeatingParser.UNBOUNDED),
        children(child, limit), "", 0, false, -1, NO_VALUE, 0, "need");
  }

  @Test
  public void testGreedyLimitNeverMatches() {
    ObservableParser child = obs().succeedAt(1).failAt(1, "stop");
    ObservableParser limit = obs().failAt(1, "l1").failAt(0, "l0");
    assertContract("greedy/limit-fails",
        c -> new GreedyRepeatingParser(c.get(0), c.get(1), 0,
            GreedyRepeatingParser.UNBOUNDED),
        children(child, limit), "a", 0, false, -1, NO_VALUE, 0, "l0");
  }

  @Test(timeout = 5000)
  public void testGreedyZeroWidthBacktracksToEmpty() {
    // Finite zero-width scenario: the delegate zero-width-succeeds once and
    // then fails; the limit only matches at the start. Both paths must
    // over-consume once, probe the limit at 0 (fail) and at 0 again after
    // backtracking (succeed), yielding the empty list at the start position.
    //
    // CHARACTERIZATION NOTE: an unbounded zero-width delegate that keeps
    // succeeding diverges (loops forever) in both entry points today; that
    // pre-existing hazard is shared behavior and is deliberately not probed.
    ObservableParser delegate = obs().succeedAt(0).failAt(0, "stop");
    ObservableParser limit = obs().failAt(0, "l0").succeedAt(0);
    assertContract("greedy/zero-width-backtrack",
        c -> new GreedyRepeatingParser(c.get(0), c.get(1), 0,
            GreedyRepeatingParser.UNBOUNDED),
        children(delegate, limit), "", 0, true, 0, new ArrayList<>());
  }

  @Test(timeout = 5000)
  public void testPossessiveZeroWidthTerminatesBothPaths() {
    // Guard against the fast and slow possessive loops diverging on a
    // zero-width success followed by a failure.
    ObservableParser slow = obs().succeedAt(2).failAt(2, "stop");
    Result result = new PossessiveRepeatingParser(slow, 0,
        PossessiveRepeatingParser.UNBOUNDED).parseOn(new Context("ab", 2));
    assertTrue(result.isSuccess());
    assertEquals(2, result.getPosition());

    ObservableParser fast = obs().succeedAt(2).failAt(2, "stop");
    int position = new PossessiveRepeatingParser(fast, 0,
        PossessiveRepeatingParser.UNBOUNDED).fastParseOn("ab", 2);
    assertEquals(2, position);
  }

  @Test
  public void testGreedyBoundedBacktracks() {
    ObservableParser child = obs().succeedAt(1).succeedAt(2).succeedAt(3);
    ObservableParser limit = obs().failAt(2, "l2").succeedAt(1);
    assertContract("greedy/bounded-backtrack",
        c -> new GreedyRepeatingParser(c.get(0), c.get(1), 0, 2),
        children(child, limit), "abc", 0, true, 1,
        Arrays.asList(1000));
  }

  // ------------------------------------------------------------------
  // Flatten / token / action nesting
  // ------------------------------------------------------------------

  @Test
  public void testFlattenSuccess() {
    ObservableParser child = obs().succeedAt(3);
    assertContract("flatten/success",
        c -> new FlattenParser(c.get(0)),
        children(child), "abcdef", 1, true, 3, "bc");
  }

  @Test
  public void testFlattenZeroWidth() {
    ObservableParser child = obs().succeedAt(2);
    assertContract("flatten/zero-width",
        c -> new FlattenParser(c.get(0)),
        children(child), "abcd", 2, true, 2, "");
  }

  @Test
  public void testFlattenFailure() {
    ObservableParser child = obs().failAt(3, "inner");
    assertContract("flatten/failure",
        c -> new FlattenParser(c.get(0)),
        children(child), "abcd", 1, false, -1, NO_VALUE, 3, "inner");
  }

  @Test
  public void testFlattenWithMessageReplacesFailure() {
    // The message-bearing flatten delegates to the fast path even when slow
    // parsing is requested and reports its own message at the start position.
    ObservableParser child = obs().failAt(3, "inner");
    assertContract("flatten/message",
        c -> new FlattenParser(c.get(0), "flat expected"),
        children(child), "abcd", 1, false, -1, NO_VALUE, 1,
        "flat expected");
  }

  @Test
  public void testFlattenWithMessageSuccess() {
    ObservableParser child = obs().succeedAt(3);
    assertContract("flatten/message-success",
        c -> new FlattenParser(c.get(0), "flat expected"),
        children(child), "abcdef", 1, true, 3, "bc");
  }

  @Test
  public void testTokenSuccess() {
    ObservableParser shared = obs().succeedAt(3);
    ObservableParser slowChild = obs().succeedAt(3);
    TokenParser slow = new TokenParser(slowChild);
    Result result = slow.parseOn(new Context("abcdef", 1));
    assertTrue(result.isSuccess());
    Token token = result.get();
    // getInput() is the consumed sub-range; getBuffer() keeps the full input.
    assertEquals("bc", token.getInput());
    assertEquals("abcdef", token.getBuffer());
    assertEquals(1, token.getStart());
    assertEquals(3, token.getStop());
    assertEquals((Object) 1001, token.getValue());

    assertContract("token/contract",
        c -> new TokenParser(c.get(0)),
        children(shared), "abcdef", 1, true, 3, NO_VALUE);
  }

  @Test
  public void testTokenFailure() {
    ObservableParser child = obs().failAt(2, "tok");
    assertContract("token/failure",
        c -> new TokenParser(c.get(0)),
        children(child), "abc", 1, false, -1, NO_VALUE, 2, "tok");
  }

  @Test
  public void testPureActionValue() {
    ObservableParser child = obs().succeedAt(2);
    assertContract("action/pure",
        c -> c.get(0).map(value -> "v" + value),
        children(child), "abcd", 1, true, 2, "v1001");
  }

  @Test
  public void testActionFailurePropagates() {
    ObservableParser child = obs().failAt(2, "a-fail");
    assertContract("action/failure",
        c -> c.get(0).map(value -> "v" + value),
        children(child), "abcd", 1, false, -1, NO_VALUE, 2, "a-fail");
  }

  // ------------------------------------------------------------------
  // Side effect policy (characterization lock).
  //
  // A pure {@link org.petitparser.parser.actions.ActionParser} ({@code map})
  // skips the transformation on the fast path and delegates recognition
  // directly. An action created with {@code mapWithSideEffects} instead falls
  // back to the slow {@code parseOn} machinery even when entered through the
  // fast entry point, so the side effect is observed exactly once per attempt.
  // These tests pin down that distinction so the refactoring cannot silently
  // change when side effects are allowed to run.
  // ------------------------------------------------------------------

  @Test
  public void testSideEffectActionRunsOnFastPath() {
    int[] slowSideEffects = new int[1];
    int[] fastSideEffects = new int[1];
    ObservableParser slowChild = new ObservableParser().succeedAt(1);
    Parser slow = slowChild.mapWithSideEffects(value -> {
      slowSideEffects[0]++;
      return value;
    });
    ObservableParser fastChild = new ObservableParser().succeedAt(1);
    Parser fast = fastChild.mapWithSideEffects(value -> {
      fastSideEffects[0]++;
      return value;
    });

    Result result = slow.parseOn(new Context("a", 0));
    assertTrue(result.isSuccess());
    assertEquals(1, result.getPosition());
    int fastPosition = fast.fastParseOn("a", 0);
    assertEquals(1, fastPosition);

    // CHARACTERIZATION: both paths execute the side effect exactly once.
    assertEquals("slow path executes the side effect", 1L,
        slowSideEffects[0]);
    assertEquals("fast path executes the side effect", 1L,
        fastSideEffects[0]);
  }

  @Test
  public void testPureActionFastPathSkipsFunction() {
    // The pure fast path must delegate straight to the child without invoking
    // the (potentially non-idempotent) function. A throwing function proves
    // that the fast path never touches it.
    ObservableParser child = obs().succeedAt(1);
    Parser parser = child.map(value -> {
      throw new AssertionError("function must not run on fast path");
    });
    assertEquals(1, parser.fastParseOn("a", 0));
  }

  @Test
  public void testNestedActionInsideSequenceSideEffects() {
    int[] sideEffects = new int[1];
    ObservableParser child = obs().succeedAt(1);
    Parser sequence = child.mapWithSideEffects(value -> {
      sideEffects[0]++;
      return value;
    }).seq(of('b'));

    // Slow path: action runs exactly once.
    Result slow = sequence.parseOn(new Context("ab", 0));
    assertTrue(slow.isSuccess());
    assertEquals(1L, sideEffects[0]);

    // Fast path: the side-effecting action falls back to the slow path, so the
    // side effect runs once more and the final position still advances.
    assertEquals(2, sequence.fastParseOn("ab", 0));
    assertEquals(2L, sideEffects[0]);
  }

  // ------------------------------------------------------------------
  // Trimming and deep nesting
  // ------------------------------------------------------------------

  @Test
  public void testFlattenOverRepeat() {
    ObservableParser child = obs()
        .succeedAt(1).succeedAt(2).succeedAt(3).failAt(3, "stop");
    assertContract("flatten/star",
        c -> new PossessiveRepeatingParser(c.get(0), 0,
            PossessiveRepeatingParser.UNBOUNDED).flatten(),
        children(child), "abc", 0, true, 3, "abc");
  }

  @Test
  public void testTokenAroundSequenceFailure() {
    ObservableParser first = obs().succeedAt(1);
    ObservableParser second = obs().failAt(2, "deep");
    assertContract("token/sequence/failure",
        c -> new TokenParser(new SequenceParser(c.get(0), c.get(1))),
        children(first, second), "ab", 0, false, -1, NO_VALUE, 2, "deep");
  }

  @Test
  public void testChoiceOfFlattenedSequences() {
    ObservableParser a1 = obs().succeedAt(1);
    ObservableParser a2 = obs().failAt(1, "a2");
    ObservableParser b1 = obs().succeedAt(1);
    ObservableParser b2 = obs().succeedAt(2);
    Parser choice = new ChoiceParser(
        new FlattenParser(new SequenceParser(a1, a2)),
        new FlattenParser(new SequenceParser(b1, b2)));
    // Only the aggregate success/failure and final position are compared; the
    // flattened value of the winning branch is asserted separately.
    Result slow = choice.parseOn(new Context("ab", 0));
    assertTrue(slow.isSuccess());
    assertEquals(2, slow.getPosition());
    assertEquals("ab", slow.get());
    a1.resetTrace();
    a2.resetTrace();
    b1.resetTrace();
    b2.resetTrace();
    assertEquals(2, choice.fastParseOn("ab", 0));
  }

  @Test
  public void testTrimmingFastAndSlowAgree() {
    // Trim with a whitespace delegate and an observable body; both paths must
    // consume the same surrounding input.
    Parser parser = of('b').trim();
    Result slow = parser.parseOn(new Context(" b ", 0));
    assertTrue(slow.isSuccess());
    assertEquals(3, slow.getPosition());
    assertEquals(3, parser.fastParseOn(" b ", 0));
  }

  @Test
  public void testTrimmingFailureAgrees() {
    Parser parser = of('b').trim();
    Result slow = parser.parseOn(new Context(" x ", 0));
    assertTrue(slow.isFailure());
    assertEquals(-1, parser.fastParseOn(" x ", 0));
  }

}
