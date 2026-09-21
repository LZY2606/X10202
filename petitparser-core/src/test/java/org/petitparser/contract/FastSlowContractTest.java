package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.petitparser.contract.FastSlowContract.SlowOutcome;
import static org.petitparser.contract.ObservableParser.Outcome;

/**
 * Executable contract matrix for parsers that implement both
 * {@code parseOn} and {@code fastParseOn}.
 *
 * <p>Every case runs the combinator through both entry points with observable
 * leaf parsers and asserts they agree on success/failure, final position and
 * the exact child invocation trace. The slow-path assertions additionally
 * check the produced value and the failure position/message.
 */
public class FastSlowContractTest {

  private final List<ObservableParser.Event> log = new ArrayList<>();

  private ObservableParser leaf(String name, Outcome outcome) {
    return new ObservableParser(name, log, outcome);
  }

  private ObservableParser leaf(String name,
      java.util.function.BiFunction<String, Integer, Outcome> behavior) {
    return new ObservableParser(name, log, behavior);
  }

  private static Outcome ok(int position, Object value) {
    return Outcome.success(position, value);
  }

  private static Outcome fail(int position, String message) {
    return Outcome.failure(position, message);
  }

  private static SlowOutcome assertSuccess(Parser parser, String input,
      int start, int expectedPosition, Object expectedValue,
      List<ObservableParser.Event> log) {
    SlowOutcome outcome =
        FastSlowContract.assertAgree(parser, input, start, log);
    assertEquals(true, outcome.isSuccess());
    assertEquals(expectedPosition, outcome.getPosition());
    assertEquals(expectedValue, outcome.getValue());
    assertNull(outcome.getMessage());
    return outcome;
  }

  private static SlowOutcome assertFailure(Parser parser, String input,
      int start, int expectedPosition, String expectedMessage,
      List<ObservableParser.Event> log) {
    SlowOutcome outcome =
        FastSlowContract.assertAgree(parser, input, start, log);
    assertEquals(false, outcome.isSuccess());
    assertEquals(expectedPosition, outcome.getPosition());
    assertEquals(expectedMessage, outcome.getMessage());
    return outcome;
  }

  // ------------------------------------------------------------------
  // Sequence
  // ------------------------------------------------------------------

  @Test
  public void sequenceEmptyInputFails() {
    Parser a = leaf("a", fail(0, "no-a"));
    Parser parser = a.seq(leaf("b", ok(1, 'b')));
    assertFailure(parser, "", 0, 0, "no-a", log);
    assertEquals(list("a@0"), names());
  }

  @Test
  public void sequenceStartsMidInput() {
    Parser a = leaf("a", ok(3, "A"));
    Parser parser = a.seq(leaf("b", ok(5, "B")));
    assertSuccess(parser, "hello world", 2, 5, list("A", "B"), log);
    assertEquals(list("a@2", "b@3"), names());
  }

  @Test
  public void sequenceBoundarySuccess() {
    Parser a = leaf("a", ok(6, 'a'));
    Parser parser = a.seq(leaf("b", ok(6, 'b')));
    assertSuccess(parser, "abcdef", 5, 6, list('a', 'b'), log);
    assertEquals(list("a@5", "b@6"), names());
  }

  @Test
  public void sequenceSecondChildFailsAfterConsumption() {
    Parser a = leaf("a", ok(2, 'a'));
    Parser b = leaf("b", fail(4, "no-b"));
    Parser parser = a.seq(b, leaf("c", ok(9, 'c')));
    assertFailure(parser, "abcdefghij", 0, 4, "no-b", log);
    assertEquals(list("a@0", "b@2"), names());
  }

  @Test
  public void sequenceZeroWidthSuccesses() {
    Parser a = leaf("a", ok(3, null));
    Parser b = leaf("b", ok(3, null));
    Parser parser = a.seq(b);
    assertSuccess(parser, "xyz", 3, 3, list(null, null), log);
    assertEquals(list("a@3", "b@3"), names());
  }

  @Test
  public void sequenceFirstFailsSkipsRest() {
    Parser a = leaf("a", fail(1, "boom"));
    Parser parser = a.seq(leaf("b", ok(2, 'b')));
    assertFailure(parser, "xy", 0, 1, "boom", log);
    assertEquals(list("a@0"), names());
  }

  // ------------------------------------------------------------------
  // Choice
  // ------------------------------------------------------------------

  @Test
  public void choiceEmptyInputAllFail() {
    Parser a = leaf("a", fail(0, "a?"));
    Parser b = leaf("b", fail(0, "b?"));
    Parser parser = a.or(b);
    assertFailure(parser, "", 0, 0, "b?", log);
    assertEquals(list("a@0", "b@0"), names());
  }

  @Test
  public void choiceStartsMidAndSecondWins() {
    Parser a = leaf("a", fail(4, "a?"));
    Parser b = leaf("b", ok(7, "B"));
    Parser c = leaf("c", ok(9, "C"));
    Parser parser = a.or(b, c);
    assertSuccess(parser, "hello world", 4, 7, "B", log);
    assertEquals(list("a@4", "b@4"), names());
  }

  @Test
  public void choiceRollsBackToSameStart() {
    Parser a = leaf("a", fail(3, "a?"));
    Parser b = leaf("b", fail(5, "b?"));
    Parser c = leaf("c", fail(3, "c?"));
    Parser parser = a.or(b, c);
    assertFailure(parser, "abcdefg", 3, 3, "c?", log);
    assertEquals(list("a@3", "b@3", "c@3"), names());
  }

  @Test
  public void choiceFirstWinsIsExclusive() {
    Parser a = leaf("a", ok(2, "A"));
    Parser b = leaf("b", ok(3, "B"));
    Parser parser = a.or(b);
    assertSuccess(parser, "abc", 0, 2, "A", log);
    assertEquals(list("a@0"), names());
  }

  @Test
  public void choiceZeroWidthSuccess() {
    Parser a = leaf("a", fail(1, "a?"));
    Parser b = leaf("b", ok(1, "B0"));
    Parser parser = a.or(b);
    assertSuccess(parser, "x", 1, 1, "B0", log);
    assertEquals(list("a@1", "b@1"), names());
  }

  // ------------------------------------------------------------------
  // Optional
  // ------------------------------------------------------------------

  @Test
  public void optionalEmptyInputUsesDefault() {
    Parser parser = leaf("a", fail(0, "no-a")).optional("def");
    assertSuccess(parser, "", 0, 0, "def", log);
    assertEquals(list("a@0"), names());
  }

  @Test
  public void optionalMidInputSuccess() {
    Parser parser = leaf("a", ok(4, "A")).optional("def");
    assertSuccess(parser, "hello", 2, 4, "A", log);
    assertEquals(list("a@2"), names());
  }

  @Test
  public void optionalFailureReturnsStartPosition() {
    Parser parser = leaf("a", fail(5, "no-a")).optional(null);
    assertSuccess(parser, "hello", 3, 3, null, log);
    assertEquals(list("a@3"), names());
  }

  @Test
  public void optionalZeroWidthSuccess() {
    Parser parser = leaf("a", ok(2, "Z")).optional("def");
    assertSuccess(parser, "ab", 2, 2, "Z", log);
    assertEquals(list("a@2"), names());
  }

  // ------------------------------------------------------------------
  // And / Not lookahead
  // ------------------------------------------------------------------

  @Test
  public void andLookaheadSucceedsZeroWidth() {
    Parser parser = leaf("a", ok(5, "A")).and();
    assertSuccess(parser, "hello", 2, 2, "A", log);
    assertEquals(list("a@2"), names());
  }

  @Test
  public void andLookaheadFails() {
    Parser parser = leaf("a", fail(3, "no-a")).and();
    assertFailure(parser, "abc", 2, 3, "no-a", log);
    assertEquals(list("a@2"), names());
  }

  @Test
  public void andLookaheadOnEmpty() {
    Parser parser = leaf("a", fail(0, "eof")).and();
    assertFailure(parser, "", 0, 0, "eof", log);
    assertEquals(list("a@0"), names());
  }

  @Test
  public void notLookaheadSucceedsZeroWidth() {
    Parser parser = leaf("a", fail(2, "a?")).not("unexpected-a");
    assertSuccess(parser, "ab", 1, 1, null, log);
    assertEquals(list("a@1"), names());
  }

  @Test
  public void notLookaheadFailsAtStart() {
    Parser parser = leaf("a", ok(3, "A")).not("unexpected-a");
    assertFailure(parser, "abc", 1, 1, "unexpected-a", log);
    assertEquals(list("a@1"), names());
  }

  @Test
  public void notLookaheadOnEmpty() {
    Parser parser = leaf("a", fail(0, "eof")).not("nope");
    assertSuccess(parser, "", 0, 0, null, log);
    assertEquals(list("a@0"), names());
  }

  // ------------------------------------------------------------------
  // Possessive repeating
  // ------------------------------------------------------------------

  @Test
  public void possessiveStarEmptyInput() {
    Parser parser = leaf("d", fail(0, "stop")).star();
    assertSuccess(parser, "", 0, 0, list(), log);
    assertEquals(list("d@0"), names());
  }

  @Test
  public void possessiveStarConsumesUntilFailure() {
    Parser d = leaf("d", (buffer, pos) ->
        pos < 3 ? ok(pos + 1, "x" + pos) : fail(pos, "stop"));
    Parser parser = d.star();
    assertSuccess(parser, "aaab", 0, 3, list("x0", "x1", "x2"), log);
    assertEquals(list("d@0", "d@1", "d@2", "d@3"), names());
  }

  @Test
  public void possessivePlusEmptyFails() {
    Parser parser = leaf("d", fail(0, "stop")).plus();
    assertFailure(parser, "", 0, 0, "stop", log);
    assertEquals(list("d@0"), names());
  }

  @Test
  public void possessivePlusFailsAfterConsumption() {
    Parser d = leaf("d", (buffer, pos) ->
        pos < 2 ? ok(pos + 1, "x" + pos) : fail(pos, "stop"));
    Parser parser = d.repeat(3, 3);
    assertFailure(parser, "aaab", 0, 2, "stop", log);
    assertEquals(list("d@0", "d@1", "d@2"), names());
  }

  @Test
  public void possessiveBoundedBoundary() {
    Parser d = leaf("d", (buffer, pos) ->
        pos < buffer.length() ? ok(pos + 1, "x" + pos)
            : fail(pos, "stop"));
    Parser parser = d.repeat(2, 4);
    assertSuccess(parser, "abc", 0, 3, list("x0", "x1", "x2"), log);
    assertEquals(list("d@0", "d@1", "d@2", "d@3"), names());
  }

  @Test
  public void possessiveZeroWidthBounded() {
    Parser parser = leaf("d", ok(2, "z")).repeat(3, 3);
    assertSuccess(parser, "ab", 0, 2, list("z", "z", "z"), log);
    assertEquals(list("d@0", "d@2", "d@2"), names());
  }

  @Test
  public void possessiveExactMinEqualsMax() {
    Parser d = leaf("d", (buffer, pos) ->
        pos < 2 ? ok(pos + 1, "x" + pos) : fail(pos, "stop"));
    Parser parser = d.times(2);
    assertSuccess(parser, "ab", 0, 2, list("x0", "x1"), log);
    assertEquals(list("d@0", "d@1"), names());
  }

  // ------------------------------------------------------------------
  // Greedy repeating
  // ------------------------------------------------------------------

  private ObservableParser consumeChars(String name, int upto) {
    return leaf(name, (buffer, pos) ->
        pos < upto ? ok(pos + 1, "c" + pos) : fail(pos, "stop"));
  }

  private ObservableParser limitAt(String name, int target, String msg) {
    return leaf(name, (buffer, pos) ->
        pos == target ? ok(pos, null) : fail(pos, msg));
  }

  @Test
  public void greedyEmptyImmediateLimit() {
    Parser parser = consumeChars("d", 3).starGreedy(limitAt("l", 0, "no-l"));
    assertSuccess(parser, "aaa", 0, 0, list(), log);
    assertEquals(list("d@0", "d@1", "d@2", "d@3", "l@3", "l@2", "l@1",
        "l@0"), names());
  }

  @Test
  public void greedyConsumesUpToLimit() {
    Parser parser = consumeChars("d", 4).starGreedy(limitAt("l", 3, "no-l"));
    assertSuccess(parser, "aaab", 0, 3, list("c0", "c1", "c2"), log);
    assertEquals(list("d@0", "d@1", "d@2", "d@3", "d@4", "l@4", "l@3"),
        names());
  }

  @Test
  public void greedyBacktracksToEarlierLimit() {
    Parser d = consumeChars("d", 4);
    Parser parser = d.starGreedy(limitAt("l", 2, "no-l"));
    assertSuccess(parser, "aaab", 0, 2, list("c0", "c1"), log);
    assertEquals(
        list("d@0", "d@1", "d@2", "d@3", "d@4", "l@4", "l@3", "l@2"),
        names());
  }

  @Test
  public void greedyMinNotMetFails() {
    Parser parser = leaf("d", fail(0, "stop"))
        .plusGreedy(limitAt("l", 0, "no-l"));
    assertFailure(parser, "", 0, 0, "stop", log);
    assertEquals(list("d@0"), names());
  }

  @Test
  public void greedyNoLimitFailsAfterBacktracking() {
    Parser d = consumeChars("d", 2);
    Parser parser = d.starGreedy(leaf("l", fail(0, "no-l")));
    assertFailure(parser, "aab", 0, 0, "no-l", log);
    assertEquals(list("d@0", "d@1", "d@2", "l@2", "l@1", "l@0"),
        names());
  }

  @Test
  public void greedyBoundedByMax() {
    Parser d = consumeChars("d", 4);
    Parser parser = d.repeatGreedy(limitAt("l", 2, "no-l"), 0, 2);
    assertSuccess(parser, "aaab", 0, 2, list("c0", "c1"), log);
    assertEquals(list("d@0", "d@1", "l@2"), names());
  }

  // ------------------------------------------------------------------
  // Lazy repeating
  // ------------------------------------------------------------------

  @Test
  public void lazyEmptyImmediateLimit() {
    Parser parser = consumeChars("d", 3).starLazy(limitAt("l", 0, "no-l"));
    assertSuccess(parser, "aaa", 0, 0, list(), log);
    assertEquals(list("l@0"), names());
  }

  @Test
  public void lazyConsumesUntilEarliestLimit() {
    Parser d = consumeChars("d", 4);
    Parser parser = d.starLazy(limitAt("l", 2, "no-l"));
    assertSuccess(parser, "aaab", 0, 2, list("c0", "c1"), log);
    assertEquals(list("l@0", "d@0", "l@1", "d@1", "l@2"), names());
  }

  @Test
  public void lazyMinSatisfied() {
    Parser d = consumeChars("d", 4);
    Parser parser = d.repeatLazy(limitAt("l", 2, "no-l"), 2, 2);
    assertSuccess(parser, "aaab", 0, 2, list("c0", "c1"), log);
    assertEquals(list("d@0", "d@1", "l@2"), names());
  }

  @Test
  public void lazyMaxReachedFailsWithLimitFailure() {
    Parser d = consumeChars("d", 4);
    Parser parser = d.repeatLazy(leaf("l", fail(1, "no-l")), 0, 1);
    assertFailure(parser, "aaab", 0, 1, "no-l", log);
    assertEquals(list("l@0", "d@0", "l@1"), names());
  }

  @Test
  public void lazyDelegateFailsBeforeLimit() {
    Parser d = leaf("d", fail(0, "stop"));
    Parser parser = d.starLazy(leaf("l", fail(0, "no-l")));
    assertFailure(parser, "", 0, 0, "no-l", log);
    assertEquals(list("l@0", "d@0"), names());
  }

  @Test
  public void lazyMidInputStart() {
    Parser d = consumeChars("d", 6);
    Parser parser = d.starLazy(limitAt("l", 5, "no-l"));
    assertSuccess(parser, "xxaaab", 2, 5, list("c2", "c3", "c4"), log);
    assertEquals(list("l@2", "d@2", "l@3", "d@3", "l@4", "d@4", "l@5"),
        names());
  }

  // ------------------------------------------------------------------
  // Flatten / token / action
  // ------------------------------------------------------------------

  @Test
  public void flattenSuccessReturnsSubstring() {
    Parser parser = leaf("a", ok(5, "ignored")).flatten();
    assertSuccess(parser, "hello world", 2, 5, "llo", log);
    assertEquals(list("a@2"), names());
  }

  @Test
  public void flattenFailureKeepsDelegateFailure() {
    Parser parser = leaf("a", fail(4, "no-a")).flatten();
    assertFailure(parser, "hello", 2, 4, "no-a", log);
    assertEquals(list("a@2"), names());
  }

  @Test
  public void flattenZeroWidth() {
    Parser parser = leaf("a", ok(3, "x")).flatten();
    assertSuccess(parser, "abc", 3, 3, "", log);
    assertEquals(list("a@3"), names());
  }

  @Test
  public void flattenWithMessageOverridesFailure() {
    Parser parser = leaf("a", fail(2, "inner")).flatten("custom");
    assertFailure(parser, "abc", 0, 0, "custom", log);
    assertEquals(list("a@0"), names());
  }

  @Test
  public void tokenWrapsValueAndRange() {
    Parser parser = leaf("a", ok(5, "V")).token();
    SlowOutcome outcome =
        FastSlowContract.assertAgree(parser, "hello world", 2, log);
    assertEquals(true, outcome.isSuccess());
    assertEquals(5, outcome.getPosition());
    org.petitparser.context.Token token =
        (org.petitparser.context.Token) outcome.getValue();
    assertEquals("V", token.getValue());
    assertEquals(2, token.getStart());
    assertEquals(5, token.getStop());
    assertEquals("llo", token.getInput());
    assertEquals(list("a@2"), names());
  }

  @Test
  public void tokenZeroWidth() {
    Parser parser = leaf("a", ok(3, "V")).token();
    SlowOutcome outcome =
        FastSlowContract.assertAgree(parser, "abc", 3, log);
    assertEquals(true, outcome.isSuccess());
    assertEquals(3, outcome.getPosition());
    org.petitparser.context.Token token =
        (org.petitparser.context.Token) outcome.getValue();
    assertEquals(3, token.getStart());
    assertEquals(3, token.getStop());
    assertEquals("", token.getInput());
  }

  @Test
  public void pureMapDoesNotRunFunctionOnFastPath() {
    // Characterizes the current fast-path contract: a pure map is skipped
    // entirely on the fast path (no allocation, no evaluation), while the
    // slow path evaluates the function exactly once.
    AtomicInteger calls = new AtomicInteger();
    Parser child = leaf("a", ok(3, "v"));
    Parser parser = child.map(value -> {
      calls.incrementAndGet();
      return "M(" + value + ")";
    });
    SlowOutcome outcome = FastSlowContract.assertSideEffectCounts(
        parser, "abc", 0, log, calls, 1, 0);
    assertEquals(true, outcome.isSuccess());
    assertEquals(3, outcome.getPosition());
    assertEquals("M(v)", outcome.getValue());
  }

  @Test
  public void pureMapFailureDoesNotRunFunction() {
    AtomicInteger calls = new AtomicInteger();
    Parser child = leaf("a", fail(2, "boom"));
    Parser parser = child.map(value -> {
      calls.incrementAndGet();
      return "M";
    });
    SlowOutcome outcome = FastSlowContract.assertSideEffectCounts(
        parser, "ab", 0, log, calls, 0, 0);
    assertEquals(false, outcome.isSuccess());
    assertEquals(2, outcome.getPosition());
    assertEquals("boom", outcome.getMessage());
  }

  @Test
  public void sideEffectMapRunsOnBothPaths() {
    AtomicInteger calls = new AtomicInteger();
    Parser child = leaf("a", ok(4, "v"));
    Parser parser = child.mapWithSideEffects(value -> {
      calls.incrementAndGet();
      return "M(" + value + ")";
    });
    SlowOutcome outcome = FastSlowContract.assertSideEffectCounts(
        parser, "abcd", 1, log, calls, 1, 1);
    assertEquals(true, outcome.isSuccess());
    assertEquals(4, outcome.getPosition());
    assertEquals("M(v)", outcome.getValue());
  }

  @Test
  public void sideEffectMapRunsForEachRepetitionOnFastPath() {
    AtomicInteger calls = new AtomicInteger();
    Parser child = leaf("d", (buffer, pos) ->
        pos < 3 ? ok(pos + 1, "v" + pos) : fail(pos, "stop"));
    Parser parser = child.mapWithSideEffects(value -> {
      calls.incrementAndGet();
      return value;
    }).star();
    FastSlowContract.assertSideEffectCounts(
        parser, "aaab", 0, log, calls, 3, 3);
  }

  @Test
  public void sideEffectMapNotRunWhenDelegateFails() {
    AtomicInteger calls = new AtomicInteger();
    Parser child = leaf("a", fail(1, "nope"));
    Parser parser = child.mapWithSideEffects(value -> {
      calls.incrementAndGet();
      return value;
    });
    FastSlowContract.assertSideEffectCounts(
        parser, "x", 0, log, calls, 0, 0);
  }

  // ------------------------------------------------------------------
  // Trimming
  // ------------------------------------------------------------------

  private ObservableParser charConsumer(String name, String chars) {
    return leaf(name, (buffer, pos) ->
        pos < buffer.length() && chars.indexOf(buffer.charAt(pos)) >= 0
            ? ok(pos + 1, buffer.charAt(pos)) : fail(pos, name + " stop"));
  }

  @Test
  public void trimConsumesBothSides() {
    Parser parser = leaf("a", ok(4, "V"))
        .trim(charConsumer("ws", " "));
    // Right trim probes one character beyond the delegate and fails, so the
    // reported stop position is the delegate stop, not the failed probe.
    assertSuccess(parser, "  V  x", 0, 5, "V", log);
    assertEquals(list("ws@0", "ws@1", "ws@2", "a@2", "ws@4", "ws@5"),
        names());
  }

  @Test
  public void trimNoWhitespace() {
    Parser parser = leaf("a", ok(2, "V"))
        .trim(charConsumer("ws", " "));
    assertSuccess(parser, "ab", 0, 2, "V", log);
    assertEquals(list("ws@0", "a@0", "ws@2"), names());
  }

  @Test
  public void trimDelegateFails() {
    Parser parser = leaf("a", fail(3, "no-a"))
        .trim(charConsumer("ws", " "));
    assertFailure(parser, "  x", 0, 3, "no-a", log);
    assertEquals(list("ws@0", "ws@1", "ws@2", "a@2"), names());
  }

  // ------------------------------------------------------------------
  // End of input / settable
  // ------------------------------------------------------------------

  @Test
  public void endOfInputAtEnd() {
    Parser eoi = new org.petitparser.parser.combinators
        .EndOfInputParser("end expected");
    assertSuccess(eoi, "abc", 3, 3, null, log);
    assertEquals(list(), names());
  }

  @Test
  public void endOfInputBeforeEnd() {
    Parser eoi = new org.petitparser.parser.combinators
        .EndOfInputParser("end expected");
    assertFailure(eoi, "abc", 1, 1, "end expected", log);
  }

  @Test
  public void endOfInputEmpty() {
    Parser eoi = new org.petitparser.parser.combinators
        .EndOfInputParser("end expected");
    assertSuccess(eoi, "", 0, 0, null, log);
  }

  @Test
  public void settableForwardsToDelegate() {
    Parser parser = org.petitparser.parser.combinators
        .SettableParser.with(leaf("a", ok(2, "V")));
    assertSuccess(parser, "ab", 0, 2, "V", log);
    assertEquals(list("a@0"), names());
  }

  // ------------------------------------------------------------------
  // Nested combinators
  // ------------------------------------------------------------------

  @Test
  public void nestedFlattenOfTokenOfAction() {
    AtomicInteger calls = new AtomicInteger();
    Parser parser = leaf("a", ok(5, "v"))
        .map(value -> {
          calls.incrementAndGet();
          return "M(" + value + ")";
        })
        .token()
        .flatten();
    // Characterizes the current behavior: FlattenParser does not override
    // fastParseOn, so its fast path emulates the slow path and evaluates the
    // pure map once on BOTH paths.
    SlowOutcome outcome = FastSlowContract.assertSideEffectCounts(
        parser, "hello", 1, log, calls, 1, 1);
    assertEquals(true, outcome.isSuccess());
    assertEquals(5, outcome.getPosition());
    assertEquals("ello", outcome.getValue());
  }

  @Test
  public void nestedChoiceOfSequencesRollbackAndWin() {
    Parser s1 = leaf("s1a", ok(2, "a1"))
        .seq(leaf("s1b", fail(3, "s1b?")));
    Parser s2 = leaf("s2a", ok(2, "a2"))
        .seq(leaf("s2b", ok(4, "b2")));
    Parser parser = s1.or(s2);
    assertSuccess(parser, "abcd", 0, 4, list("a2", "b2"), log);
    assertEquals(list("s1a@0", "s1b@2", "s2a@0", "s2b@2"), names());
  }

  @Test
  public void nestedSequenceWithLookaheadAndRepeat() {
    Parser word = leaf("w", (buffer, pos) ->
        pos < buffer.length() && Character.isLetter(buffer.charAt(pos))
            ? ok(pos + 1, buffer.charAt(pos)) : fail(pos, "letter?"));
    Parser parser = word.and().seq(word.star()).seq(
        leaf("end", (buffer, pos) ->
            pos == 4 ? ok(4, "E") : fail(pos, "end?")));
    assertSuccess(parser, "abcd!", 0, 4,
        list('a', list('a', 'b', 'c', 'd'), "E"), log);
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private List<String> names() {
    List<String> result = new ArrayList<>();
    for (ObservableParser.Event event : log) {
      result.add(event.name + "@" + event.position);
    }
    return result;
  }

  private static List<Object> list(Object... elements) {
    List<Object> result = new ArrayList<>();
    for (Object element : elements) {
      result.add(element);
    }
    return result;
  }
}
