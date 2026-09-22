package org.petitparser.contract;

import org.junit.Before;
import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.any;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.letter;
import static org.petitparser.parser.primitive.CharacterParser.of;
import static org.petitparser.parser.primitive.StringParser.of;

/**
 * Executable contract pinning the two evaluation paths of every combinator to
 * the same semantics.
 *
 * <p>Each combinator exposes (at least) two entry points:
 * {@link Parser#parseOn(Context)} builds a {@link Result} carrying a value, a
 * failure message and a position, whereas {@link Parser#fastParseOn(String,
 * int)} only reports the next position (or {@code -1}) and is expected to avoid
 * allocating result objects.
 *
 * <p>The combinators under test are driven by {@link ScriptedParser}, an
 * observable child whose two paths are generated from one shared outcome list
 * and therefore cannot drift. Every assertion compares both paths on:
 * <ol>
 *   <li>success vs. failure,</li>
 *   <li>the final position on success,</li>
 *   <li>the globally ordered list of child invocations (parser label + entry
 *       position + outcome), which catches a single path changing its rollback
 *       order, short circuiting a different child, or probing a lookahead /
 *       limit a different number of times, and</li>
 * </ol>
 * and, for {@code parseOn} only, the produced value together with the failure
 * position and message.
 *
 * <p>Deliberately asymmetric behaviours are locked in explicitly rather than
 * "normalized": a side-effect free {@code map} action must not run on the fast
 * path, while a {@code mapWithSideEffects} action must run on both paths.
 */
public class FastParseContractTest {

  /** Global, chronological trace of child invocations for the current run. */
  private static final List<String> TRACE = new ArrayList<>();

  /** Set while the fast path is driving, so trace entries carry a tag. */
  private static boolean fastPath;

  private static void record(String label, int entry, boolean success,
      int position) {
    TRACE.add((fastPath ? "F:" : "S:") + label + "@" + entry
        + (success ? "->" + position : "!@" + position));
  }

  /**
   * A programmable parser whose {@code parseOn} and {@code fastParseOn} are
   * generated from the same list of outcomes, so the two paths can never drift.
   */
  static class ScriptedParser extends Parser {

    /** One programmed transition: success or failure at a given position. */
    static final class Outcome {
      final boolean success;
      final int position;
      final Object value;
      final String message;

      private Outcome(boolean success, int position, Object value,
          String message) {
        this.success = success;
        this.position = position;
        this.value = value;
        this.message = message;
      }

      static Outcome success(int position, Object value) {
        return new Outcome(true, position, value, null);
      }

      static Outcome failure(int position, String message) {
        return new Outcome(false, position, null, message);
      }
    }

    final String label;
    final List<Outcome> outcomes;
    int invocation;

    ScriptedParser(String label, Outcome... outcomes) {
      this.label = label;
      this.outcomes = new ArrayList<>(Arrays.asList(outcomes));
    }

    private Outcome next(int position) {
      Outcome outcome = invocation < outcomes.size()
          ? outcomes.get(invocation)
          : FastParseContractTest.ScriptedParser.Outcome.failure(position, label + " exhausted");
      invocation++;
      return outcome;
    }

    @Override
    public Result parseOn(Context context) {
      int position = context.getPosition();
      Outcome outcome = next(position);
      record(label, position, outcome.success, outcome.position);
      return outcome.success
          ? context.success(outcome.value, outcome.position)
          : context.failure(outcome.message, outcome.position);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      Outcome outcome = next(position);
      record(label, position, outcome.success, outcome.position);
      return outcome.success ? outcome.position : -1;
    }

    @Override
    public Parser copy() {
      return new ScriptedParser(label,
          outcomes.toArray(new Outcome[0]));
    }
  }

  @Before
  public void clearTrace() {
    TRACE.clear();
  }

  private static String stripTag(String event) {
    return event.startsWith("F:") || event.startsWith("S:")
        ? event.substring(2) : event;
  }

  private static void reset(Parser parser) {
    for (Parser child : parser.getChildren()) {
      reset(child);
    }
    if (parser instanceof ScriptedParser) {
      ((ScriptedParser) parser).invocation = 0;
    }
  }

  private static List<String> normalizedTrace() {
    List<String> out = new ArrayList<>();
    for (String event : TRACE) {
      out.add(stripTag(event));
    }
    return out;
  }

  private static List<Parser> children(Parser parser) {
    return parser.getChildren();
  }

  /**
   * Drives both paths and asserts they agree on success/failure and final
   * position, and that the ordered child invocations are identical. Returns
   * the slow-path result so individual tests can pin value and failure.
   */
  private static Result assertContract(Parser parser, String buffer,
      int start) {
    reset(parser);
    TRACE.clear();
    fastPath = false;
    Result slow = parser.parseOn(new Context(buffer, start));
    List<String> slowTrace = new ArrayList<>(normalizedTrace());

    reset(parser);
    TRACE.clear();
    fastPath = true;
    int fast = parser.fastParseOn(buffer, start);
    List<String> fastTrace = new ArrayList<>(normalizedTrace());

    assertEquals("fast/slow child call sequence (entry order, positions, "
        + "short-circuit, lookahead and backtracking)", slowTrace, fastTrace);
    if (slow.isSuccess()) {
      assertTrue("fast path must succeed at " + slow.getPosition()
          + " but returned " + fast, fast >= 0);
      assertEquals("fast path final position", slow.getPosition(), fast);
    } else {
      assertEquals("fast path must fail (-1)", -1, fast);
    }
    return slow;
  }

  private static void assertSlowSuccess(Result result, Object value,
      int position) {
    assertTrue("expected success, got " + result, result.isSuccess());
    assertEquals("success position", position, result.getPosition());
    assertEquals("success value", value, result.get());
    assertNull("no failure message", result.getMessage());
  }

  private static void assertSlowFailure(Result result, int position,
      String message) {
    assertTrue("expected failure, got " + result, result.isFailure());
    assertEquals("failure position", position, result.getPosition());
    assertEquals("failure message", message, result.getMessage());
  }

  // ------------------------------------------------------------------
  // Sequence
  // ------------------------------------------------------------------

  @Test
  public void sequenceEmptyStart() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(3, "bc"));
    Parser parser = a.seq(b);
    Result result = assertContract(parser, "abc", 0);
    assertSlowSuccess(result, Arrays.asList('a', "bc"), 3);
  }

  @Test
  public void sequenceMidStart() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(3, 'x'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(4, 'y'));
    Parser parser = a.seq(b);
    Result result = assertContract(parser, "abcdef", 2);
    assertSlowSuccess(result, Arrays.asList('x', 'y'), 4);
  }

  @Test
  public void sequenceZeroWidthSuccess() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(2, "zero"));
    Parser c = new ScriptedParser("c", FastParseContractTest.ScriptedParser.Outcome.success(3, 'c'));
    Parser parser = a.seq(b, c);
    Result result = assertContract(parser, "abcd", 1);
    assertSlowSuccess(result, Arrays.asList('a', "zero", 'c'), 3);
  }

  @Test
  public void sequenceFirstFails() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(1, 'b'));
    Parser parser = a.seq(b);
    Result result = assertContract(parser, "b", 0);
    assertSlowFailure(result, 0, "no a");
  }

  @Test
  public void sequenceLaterChildFailsAfterConsuming() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.failure(5, "no b"));
    Parser c = new ScriptedParser("c", FastParseContractTest.ScriptedParser.Outcome.success(9, 'c'));
    Parser parser = a.seq(b, c);
    Result result = assertContract(parser, "abcdef", 0);
    assertSlowFailure(result, 5, "no b");
  }

  @Test
  public void sequenceFailureAtMidStart() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.failure(7, "no b"));
    Parser parser = a.seq(b);
    Result result = assertContract(parser, "abcdefghi", 2);
    assertSlowFailure(result, 7, "no b");
  }

  // ------------------------------------------------------------------
  // Choice
  // ------------------------------------------------------------------

  @Test
  public void choiceFirstSucceeds() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(2, 'b'));
    Parser parser = a.or(b);
    Result result = assertContract(parser, "ab", 0);
    assertSlowSuccess(result, 'a', 1);
  }

  @Test
  public void choiceRollsToSecond() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(1, "no a"));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(3, "bc"));
    Parser parser = a.or(b);
    Result result = assertContract(parser, "abc", 0);
    assertSlowSuccess(result, "bc", 3);
  }

  @Test
  public void choiceAllFailUsesLastFailure() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.failure(4, "no b"));
    Parser parser = a.or(b);
    Result result = assertContract(parser, "abcd", 0);
    assertSlowFailure(result, 4, "no b");
  }

  @Test
  public void choiceSelectFirstJoinerKeepsFirstFailure() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.failure(4, "no b"));
    Parser parser = a.or(new org.petitparser.utils.FailureJoiner
        .SelectFirst(), b);
    Result result = assertContract(parser, "abcd", 0);
    assertSlowFailure(result, 0, "no a");
  }

  @Test
  public void choiceFromMidStart() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(2, "no a"));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(5, "bcd"));
    Parser parser = a.or(b);
    Result result = assertContract(parser, "abcdefg", 2);
    assertSlowSuccess(result, "bcd", 5);
  }

  @Test
  public void choiceZeroWidthAlternative() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(0, "zero"));
    Parser parser = a.or(b);
    Result result = assertContract(parser, "", 0);
    assertSlowSuccess(result, "zero", 0);
  }

  // ------------------------------------------------------------------
  // Optional
  // ------------------------------------------------------------------

  @Test
  public void optionalPresent() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'));
    Parser parser = a.optional("x");
    Result result = assertContract(parser, "a", 0);
    assertSlowSuccess(result, 'a', 1);
  }

  @Test
  public void optionalAbsentKeepsPosition() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(2, "no a"));
    Parser parser = a.optional("x");
    Result result = assertContract(parser, "abc", 2);
    assertSlowSuccess(result, "x", 2);
  }

  @Test
  public void optionalZeroWidthPresent() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(3, "zero"));
    Parser parser = a.optional();
    Result result = assertContract(parser, "abc", 3);
    assertSlowSuccess(result, "zero", 3);
  }

  // ------------------------------------------------------------------
  // Positive lookahead (and)
  // ------------------------------------------------------------------

  @Test
  public void andSucceedsZeroWidth() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'));
    Parser parser = a.and();
    Result result = assertContract(parser, "abcde", 1);
    assertSlowSuccess(result, 'a', 1);
  }

  @Test
  public void andPropagatesFailure() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(4, "no a"));
    Parser parser = a.and();
    Result result = assertContract(parser, "abcd", 1);
    assertSlowFailure(result, 4, "no a");
  }

  @Test
  public void andAtEndZeroWidth() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(0, "v"));
    Parser parser = a.and();
    Result result = assertContract(parser, "", 0);
    assertSlowSuccess(result, "v", 0);
  }

  // ------------------------------------------------------------------
  // Negative lookahead (not)
  // ------------------------------------------------------------------

  @Test
  public void notSucceedsZeroWidthWhenDelegateFails() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(2, "seen a"));
    Parser parser = a.not("unexpected a");
    Result result = assertContract(parser, "ab", 0);
    assertSlowSuccess(result, null, 0);
  }

  @Test
  public void notFailsWithOwnMessageWhenDelegateSucceeds() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser parser = a.not("unexpected a");
    Result result = assertContract(parser, "ab", 0);
    assertSlowFailure(result, 0, "unexpected a");
  }

  @Test
  public void notFailureMessageAtStartPositionFromMidInput() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(6, 'a'));
    Parser parser = a.not("boom");
    Result result = assertContract(parser, "abcdefg", 3);
    assertSlowFailure(result, 3, "boom");
  }

  // ------------------------------------------------------------------
  // End of input
  // ------------------------------------------------------------------

  @Test
  public void endOfInputAtEnd() {
    Parser parser = new org.petitparser.parser.combinators
        .EndOfInputParser("end of input expected");
    Result result = assertContract(parser, "abc", 3);
    assertSlowSuccess(result, null, 3);
  }

  @Test
  public void endOfInputBeforeEnd() {
    Parser parser = new org.petitparser.parser.combinators
        .EndOfInputParser("end of input expected");
    Result result = assertContract(parser, "abc", 1);
    assertSlowFailure(result, 1, "end of input expected");
  }

  @Test
  public void endOfInputEmpty() {
    Parser parser = new org.petitparser.parser.combinators
        .EndOfInputParser("end of input expected");
    Result result = assertContract(parser, "", 0);
    assertTrue(result.isSuccess());
  }

  // ------------------------------------------------------------------
  // Possessive repeating
  // ------------------------------------------------------------------

  @Test
  public void possessiveConsumesUntilFailure() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(2, "done"));
    Parser parser = a.star();
    Result result = assertContract(parser, "aa", 0);
    assertSlowSuccess(result, Arrays.asList('a', 'a'), 2);
  }

  @Test
  public void possessiveZeroRepetitions() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser parser = a.star();
    Result result = assertContract(parser, "b", 0);
    assertSlowSuccess(result, new ArrayList<Object>(), 0);
  }

  @Test
  public void possessivePlusRequiresMinimum() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser parser = a.plus();
    Result result = assertContract(parser, "b", 0);
    assertSlowFailure(result, 0, "no a");
  }

  @Test
  public void possessiveExactlyBoundary() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser parser = a.times(2);
    Result result = assertContract(parser, "aa", 0);
    assertSlowSuccess(result, Arrays.asList('a', 'a'), 2);
  }

  @Test
  public void possessiveBoundedStopsAtMaxEvenWhenMoreAvailable() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'));
    Parser parser = a.repeat(0, 2);
    Result result = assertContract(parser, "aaa", 0);
    assertSlowSuccess(result, Arrays.asList('a', 'a'), 2);
  }

  @Test
  public void possessiveMinimumNotReachedFails() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "no more"));
    Parser parser = a.repeat(2, 3);
    Result result = assertContract(parser, "a", 0);
    assertSlowFailure(result, 1, "no more");
  }

  @Test
  public void possessiveFromMidStart() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(4, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(5, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(5, "done"));
    Parser parser = a.star();
    Result result = assertContract(parser, "xxaaa", 3);
    assertSlowSuccess(result, Arrays.asList('a', 'a'), 5);
  }

  // ------------------------------------------------------------------
  // Greedy (non-blind) repeating
  // ------------------------------------------------------------------

  @Test
  public void greedyGrowsThenBacktracksToLimit() {
    // delegate consumes three single characters; limit matches only at 3.
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(3, "no more"));
    Parser limit = new ScriptedParser("lim",
        // probed at 3, 2, 1 while backtracking, finally at 0? No: it
        // succeeds at 3 right after growth, so a single success outcome is
        // all that is observed.
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'L'));
    Parser parser = a.starGreedy(limit);
    Result result = assertContract(parser, "aaa", 0);
    // Three repetitions then the limit matches at 3, nothing is given back.
    assertSlowSuccess(result, Arrays.asList('a', 'a', 'a'), 3);
  }

  @Test
  public void greedyBacktracksConsumedInput() {
    // Delegate consumes to 3, but the limit only succeeds at position 1, so
    // two successful repetitions must be rolled back.
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(3, "no more"));
    Parser limit = new ScriptedParser("lim",
        // probed at 3, then 2, then 1 while backtracking
        FastParseContractTest.ScriptedParser.Outcome.failure(3, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.failure(2, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'L'));
    Parser parser = a.starGreedy(limit);
    Result result = assertContract(parser, "aaa", 0);
    assertSlowSuccess(result, Arrays.asList('a'), 1);
  }

  @Test
  public void greedyMinNotReachedFails() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "no more"));
    Parser limit = new ScriptedParser("lim", FastParseContractTest.ScriptedParser.Outcome.success(9, 'L'));
    Parser parser = a.repeatGreedy(limit, 2, org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    Result result = assertContract(parser, "a", 0);
    assertSlowFailure(result, 1, "no more");
  }

  @Test
  public void greedyLimitNeverMatchesReturnsLimitFailure() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "no more"));
    // probed at 1 after growth, then at 0 after giving the repetition back
    Parser limit = new ScriptedParser("lim",
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.failure(0, "lim"));
    Parser parser = a.starGreedy(limit);
    Result result = assertContract(parser, "a", 0);
    assertSlowFailure(result, 0, "lim");
  }

  @Test
  public void greedyZeroWidthMatchAtStart() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser limit = new ScriptedParser("lim", FastParseContractTest.ScriptedParser.Outcome.success(0, 'L'));
    Parser parser = a.starGreedy(limit);
    Result result = assertContract(parser, "", 0);
    assertSlowSuccess(result, new ArrayList<Object>(), 0);
  }

  @Test
  public void greedyMaxBoundsBacktracking() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser limit = new ScriptedParser("lim",
        // probed at 2 (max reached), limit fails there; backtrack to 1
        FastParseContractTest.ScriptedParser.Outcome.failure(2, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'L'));
    Parser parser = a.repeatGreedy(limit, 1, 2);
    Result result = assertContract(parser, "aaa", 0);
    assertSlowSuccess(result, Arrays.asList('a'), 1);
  }

  // ------------------------------------------------------------------
  // Lazy repeating
  // ------------------------------------------------------------------

  @Test
  public void lazyTakesEarliestLimit() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser limit = new ScriptedParser("lim",
        FastParseContractTest.ScriptedParser.Outcome.failure(0, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'L'));
    Parser parser = a.starLazy(limit);
    Result result = assertContract(parser, "aa", 0);
    assertSlowSuccess(result, Arrays.asList('a'), 1);
  }

  @Test
  public void lazyZeroRepetitionsWhenLimitMatchesImmediately() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "never"));
    Parser limit = new ScriptedParser("lim", FastParseContractTest.ScriptedParser.Outcome.success(0, 'L'));
    Parser parser = a.starLazy(limit);
    Result result = assertContract(parser, "x", 0);
    assertSlowSuccess(result, new ArrayList<Object>(), 0);
  }

  @Test
  public void lazyMinNotReachedFails() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "no a"));
    Parser limit = new ScriptedParser("lim", FastParseContractTest.ScriptedParser.Outcome.failure(0, "lim"));
    Parser parser = a.repeatLazy(limit, 1, org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    Result result = assertContract(parser, "x", 0);
    assertSlowFailure(result, 0, "no a");
  }

  @Test
  public void lazyLimitFailsAndDelegateFailsReportsLimitFailure() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "no more"));
    Parser limit = new ScriptedParser("lim",
        FastParseContractTest.ScriptedParser.Outcome.failure(0, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "lim"));
    Parser parser = a.starLazy(limit);
    Result result = assertContract(parser, "a", 0);
    assertSlowFailure(result, 1, "lim");
  }

  @Test
  public void lazyBoundedMaxReportsLimitFailureWhenMaxExhausted() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser limit = new ScriptedParser("lim",
        FastParseContractTest.ScriptedParser.Outcome.failure(0, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.failure(1, "lim"),
        FastParseContractTest.ScriptedParser.Outcome.failure(2, "lim"));
    Parser parser = a.repeatLazy(limit, 0, 2);
    Result result = assertContract(parser, "aa", 0);
    assertSlowFailure(result, 2, "lim");
  }

  // ------------------------------------------------------------------
  // Flatten
  // ------------------------------------------------------------------

  @Test
  public void flattenReturnsConsumedRange() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(2, Arrays.asList('x', 'y')));
    Parser parser = a.flatten();
    Result result = assertContract(parser, "abc", 1);
    assertSlowSuccess(result, "b", 2);
  }

  @Test
  public void flattenEmptyRange() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, null));
    Parser parser = a.flatten();
    Result result = assertContract(parser, "ab", 2);
    assertSlowSuccess(result, "", 2);
  }

  @Test
  public void flattenDelegateFailurePropagates() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(3, "inner"));
    Parser parser = a.flatten();
    Result result = assertContract(parser, "abc", 1);
    assertSlowFailure(result, 3, "inner");
  }

  @Test
  public void flattenWithMessageSucceeds() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(4, "ignored"));
    Parser parser = a.flatten("custom");
    Result result = assertContract(parser, "abcd", 1);
    assertSlowSuccess(result, "bcd", 4);
  }

  @Test
  public void flattenWithMessageReplacesFailure() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(5, "inner"));
    Parser parser = a.flatten("custom");
    Result result = assertContract(parser, "abcde", 1);
    // The message-bearing flatten reports its own failure at the start.
    assertSlowFailure(result, 1, "custom");
  }

  // ------------------------------------------------------------------
  // Token
  // ------------------------------------------------------------------

  @Test
  public void tokenWrapsRangeAndValue() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(4, 42));
    Parser parser = a.token();
    Result result = assertContract(parser, "abcd", 1);
    org.petitparser.context.Token token = result.get();
    assertEquals(1, token.getStart());
    assertEquals(4, token.getStop());
    assertEquals("bcd", token.getInput());
    assertEquals(Integer.valueOf(42), token.getValue());
    assertEquals(4, result.getPosition());
  }

  @Test
  public void tokenZeroWidth() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, "v"));
    Parser parser = a.token();
    Result result = assertContract(parser, "ab", 2);
    org.petitparser.context.Token token = result.get();
    assertEquals(2, token.getStart());
    assertEquals(2, token.getStop());
  }

  @Test
  public void tokenFailurePropagates() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(0, "inner"));
    Parser parser = a.token();
    Result result = assertContract(parser, "x", 0);
    assertSlowFailure(result, 0, "inner");
  }

  // ------------------------------------------------------------------
  // Action (map / mapWithSideEffects)
  // ------------------------------------------------------------------

  @Test
  public void pureActionTransformsValueOnSlowPath() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, "v"));
    Parser parser = a.map(value -> value + "!");
    Result result = assertContract(parser, "ab", 0);
    assertSlowSuccess(result, "v!", 2);
  }

  @Test
  public void pureActionFailurePropagates() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(2, "inner"));
    Parser parser = a.map(value -> value + "!");
    Result result = assertContract(parser, "ab", 0);
    assertSlowFailure(result, 2, "inner");
  }

  @Test
  public void pureActionIsNotInvokedOnFastPath() {
    // Characterization: the allocation-free fast path skips a pure action.
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(2, "v"),
        FastParseContractTest.ScriptedParser.Outcome.success(2, "v"));
    AtomicInteger calls = new AtomicInteger();
    Parser parser = a.map(value -> {
      calls.incrementAndGet();
      return value;
    });
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("pure action must not run during fastParseOn", 0,
        calls.get());
    Result result = parser.parseOn(new org.petitparser.context.Context("ab", 0));
    assertTrue(result.isSuccess());
    assertEquals("pure action runs during parseOn", 1, calls.get());
  }

  @Test
  public void sideEffectActionRunsOnBothPaths() {
    // Characterization: mapWithSideEffects forces the slow mode even on the
    // fast entry point so the effect is observed exactly once each time.
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(2, "v"),
        FastParseContractTest.ScriptedParser.Outcome.success(2, "v"));
    AtomicInteger calls = new AtomicInteger();
    Parser parser = a.mapWithSideEffects(value -> {
      calls.incrementAndGet();
      return value;
    });
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("side effect must run during fastParseOn", 1,
        calls.get());
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("side effect must run again", 2, calls.get());
  }

  @Test
  public void sideEffectActionFailureRunsNoEffect() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(2, "inner"));
    AtomicInteger calls = new AtomicInteger();
    Parser parser = a.mapWithSideEffects(value -> {
      calls.incrementAndGet();
      return value;
    });
    Result result = assertContract(parser, "ab", 0);
    assertSlowFailure(result, 2, "inner");
    assertEquals(0, calls.get());
  }

  // ------------------------------------------------------------------
  // Nested flatten / token / action
  // ------------------------------------------------------------------

  @Test
  public void actionAroundFlattenAroundToken() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(4, "raw"));
    Parser parser = a.token().flatten().map((String s) -> s.toUpperCase());
    Result result = assertContract(parser, "abcd", 1);
    // pure action is skipped on fast path but both paths agree on position;
    // slow path yields the transformed flat text.
    assertSlowSuccess(result, "BCD", 4);
  }

  @Test
  public void flattenNestedChoiceSequence() {
    // First alternative fails; the second (letter, then scripted yy) wins.
    Parser y = new ScriptedParser("y",
        FastParseContractTest.ScriptedParser.Outcome.success(4, "yy"));
    Parser seq = digit().seq(new ScriptedParser("x",
            FastParseContractTest.ScriptedParser.Outcome.failure(2, "no x")))
        .or(letter().seq(y));
    Parser parser = seq.flatten();
    Result result = assertContract(parser, "abYY", 1);
    assertSlowSuccess(result, "bYY", 4);
  }

  @Test
  public void tokenNestedRepeatAction() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(2, "done"));
    Parser parser = a.star().token().map(t -> "T");
    Result result = assertContract(parser, "aa", 0);
    assertSlowSuccess(result, "T", 2);
  }

  // ------------------------------------------------------------------
  // Settable / plain delegate
  // ------------------------------------------------------------------

  @Test
  public void settableForwardsBothPaths() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'));
    Parser parser = a.settable();
    Result result = assertContract(parser, "ab", 0);
    assertSlowSuccess(result, 'a', 2);
  }

  // ------------------------------------------------------------------
  // Trimming
  // ------------------------------------------------------------------

  @Test
  public void trimConsumesBothSides() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(3, "v"));
    Parser parser = a.trim(of(' '), of(' '));
    Result result = assertContract(parser, "  v  ", 2);
    assertSlowSuccess(result, "v", 5);
  }

  @Test
  public void trimNoWhitespace() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(1, "v"));
    Parser parser = a.trim(of(' '), of(' '));
    Result result = assertContract(parser, "x", 0);
    assertSlowSuccess(result, "v", 1);
  }

  @Test
  public void trimDelegateFailure() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.failure(3, "inner"));
    Parser parser = a.trim(of(' '), of(' '));
    Result result = assertContract(parser, "  x", 0);
    assertSlowFailure(result, 3, "inner");
  }

  // ------------------------------------------------------------------
  // Continuation (parseOn only: fast path is emulated through parseOn)
  // ------------------------------------------------------------------

  @Test
  public void continuationRunsOnSlowAndFastPath() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(2, "v"),
        FastParseContractTest.ScriptedParser.Outcome.success(2, "v"));
    AtomicInteger handlerCalls = new AtomicInteger();
    Parser parser = a.callCC((continuation, context) -> {
      handlerCalls.incrementAndGet();
      return continuation.apply(context);
    });
    Result result = parser.parseOn(new Context("ab", 0));
    assertTrue(result.isSuccess());
    assertEquals(2, result.getPosition());
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("fast path is emulated via the handler", 2,
        handlerCalls.get());
  }

  // ------------------------------------------------------------------
  // Mutation tests: prove the contract catches a single path being changed.
  // These intentionally define divergent parsers and assert the generic
  // comparison fails; they would regress if the contract were weakened.
  // ------------------------------------------------------------------

  /** Sequence whose fast path advances one extra character on success. */
  static class BrokenFastSequence
      extends org.petitparser.parser.combinators.SequenceParser {
    BrokenFastSequence(Parser... parsers) {
      super(parsers);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      int result = super.fastParseOn(buffer, position);
      return result >= 0 && result < buffer.length() ? result + 1 : result;
    }
  }

  /** Sequence whose fast path skips the last child (rollback order drift). */
  static class SkipLastFastSequence
      extends org.petitparser.parser.combinators.SequenceParser {
    SkipLastFastSequence(Parser... parsers) {
      super(parsers);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      Parser[] all = getChildren().toArray(new Parser[0]);
      for (int i = 0; i < all.length - 1; i++) {
        position = all[i].fastParseOn(buffer, position);
        if (position < 0) {
          return position;
        }
      }
      return position;
    }
  }

  /** Lookahead whose fast path incorrectly consumes input. */
  static class BrokenFastAnd
      extends org.petitparser.parser.combinators.AndParser {
    BrokenFastAnd(Parser delegate) {
      super(delegate);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      return getChildren().get(0).fastParseOn(buffer, position);
    }
  }

  /** Repeat whose fast path runs one fewer iteration (boundary drift). */
  static class BrokenFastRepeat
      extends org.petitparser.parser.repeating.PossessiveRepeatingParser {
    BrokenFastRepeat(Parser delegate) {
      super(delegate, 0, org.petitparser.parser.repeating.RepeatingParser
          .UNBOUNDED);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      int current = position;
      for (int i = 0; i < 2; i++) {
        int next = getChildren().get(0).fastParseOn(buffer, current);
        if (next < 0) {
          return current;
        }
        current = next;
      }
      return current;
    }
  }

  @Test
  public void contractDetectsFastPathPositionMutation() {
    Parser a = new ScriptedParser("a", FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'));
    Parser b = new ScriptedParser("b", FastParseContractTest.ScriptedParser.Outcome.success(2, 'b'));
    Parser parser = new BrokenFastSequence(a, b);
    expectContractFailure(parser, "abc", 0);
  }

  @Test
  public void contractDetectsFastPathSkippedChild() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'));
    Parser b = new ScriptedParser("b",
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'b'));
    Parser parser = new SkipLastFastSequence(a, b);
    expectContractFailure(parser, "abc", 0);
  }

  @Test
  public void contractDetectsFastPathLookaheadConsumption() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'));
    Parser parser = new BrokenFastAnd(a);
    expectContractFailure(parser, "abcd", 1);
  }

  @Test
  public void contractDetectsFastPathRepeatBoundary() {
    Parser a = new ScriptedParser("a",
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(3, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(3, "done"),
        FastParseContractTest.ScriptedParser.Outcome.success(1, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.success(2, 'a'),
        FastParseContractTest.ScriptedParser.Outcome.failure(2, "done"));
    Parser parser = new BrokenFastRepeat(a);
    expectContractFailure(parser, "aaa", 0);
  }

  private static void expectContractFailure(Parser parser, String buffer,
      int start) {
    boolean failed = false;
    try {
      assertContract(parser, buffer, start);
    } catch (AssertionError error) {
      failed = true;
    }
    assertTrue("contract assertion must detect the divergent fast path",
        failed);
  }
}
