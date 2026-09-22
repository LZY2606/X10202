package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;
import org.petitparser.parser.combinators.SettableParser;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.petitparser.contract.Trace.trace;

/**
 * Characterizes and pins down the shared semantics of
 * {@link Parser#parseOn(Context)} and
 * {@link Parser#fastParseOn(String, int)} across every combinator that
 * implements both entry points.
 *
 * <p>Every scenario uses instrumented {@link ObservableParser} children so
 * that both paths can be compared on success/failure and the final position,
 * the exact child-invocation sequence (including positions probed during
 * choice rollback and greedy unwind), the slow path's value/failure
 * position/message, and whether action callbacks run on the fast path.
 *
 * <p>Touching the position math or the rollback/probe order of either path in
 * isolation makes the expected transition list diverge and fails the test.
 */
public class FastParseContractTest {

  private static String failureMessage(String name) {
    return name + " failure";
  }

  // --- sequence ----------------------------------------------------------

  @Test
  public void sequenceEmptyInput() {
    check("", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b')),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void sequenceStartInMiddle() {
    check("abc", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b')),
        1, false, 1, trace("a@1->fail:1"), null, 1,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void sequenceExactBoundarySuccess() {
    check("ab", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b')),
        0, true, 2, trace("a@0->ok:1", "b@1->ok:2"),
        java.util.Arrays.asList('a', 'b'), -1, null, 0, 0);
  }

  @Test
  public void sequenceChildConsumesThenFails() {
    check("axc", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b'))
        .seq(trace.charOf("c", 'c')), 0, false, 0,
        trace("a@0->ok:1", "b@1->fail:1"), null, 1,
        failureMessage("b"), 0, 0);
  }

  @Test
  public void sequenceWithZeroWidthSuccess() {
    check("a", trace -> trace.charOf("a", 'a').seq(trace.epsilon("e"))
        .seq(trace.charOf("b", 'b')), 0, false, 0,
        trace("a@0->ok:1", "e@1->ok:1", "b@1->fail:1"), null, 1,
        failureMessage("b"), 0, 0);
  }

  // --- choice ------------------------------------------------------------

  @Test
  public void choiceFirstSucceeds() {
    check("ab", trace -> trace.charOf("a", 'a').or(trace.charOf("b", 'b')),
        0, true, 1, trace("a@0->ok:1"), 'a', -1, null, 0, 0);
  }

  @Test
  public void choiceRollsBackToSecond() {
    check("ba", trace -> trace.charOf("a", 'a').or(trace.charOf("b", 'b')),
        0, true, 1, trace("a@0->fail:0", "b@0->ok:1"), 'b', -1, null, 0, 0);
  }

  @Test
  public void choiceAllFailReturnsLastFailureAtStart() {
    check("cc", trace -> trace.charOf("a", 'a').or(trace.charOf("b", 'b')),
        0, false, 0, trace("a@0->fail:0", "b@0->fail:0"), null, 0,
        failureMessage("b"), 0, 0);
  }

  @Test
  public void choiceAllFailFromMiddle() {
    check("cc", trace -> trace.charOf("a", 'a').or(trace.charOf("b", 'b')),
        1, false, 1, trace("a@1->fail:1", "b@1->fail:1"), null, 1,
        failureMessage("b"), 0, 0);
  }

  @Test
  public void choiceFirstConsumesThenFailsStillRollsBack() {
    // The first child must be re-tried at the same entry position; even
    // though it consumes input, a failure restarts the next alternative.
    check("ax", trace -> trace.charOf("a", 'a').seq(trace.charOf("z", 'z'))
        .or(trace.charOf("a", 'a').seq(trace.charOf("x", 'x'))),
        0, true, 2,
        trace("a@0->ok:1", "z@1->fail:1", "a@0->ok:1", "x@1->ok:2"),
        java.util.Arrays.asList('a', 'x'), -1, null, 0, 0);
  }

  @Test
  public void choiceEmptyInput() {
    check("", trace -> trace.charOf("a", 'a').or(trace.epsilon("e")),
        0, true, 0, trace("a@0->fail:0", "e@0->ok:0"), null, -1, null, 0, 0);
  }

  // --- and-predicate -----------------------------------------------------

  @Test
  public void andSucceedsWithoutConsuming() {
    check("ab", trace -> trace.charOf("a", 'a').and().seq(trace.charOf("a", 'a')),
        0, true, 1,
        trace("a@0->ok:1", "a@0->ok:1"),
        java.util.Arrays.asList('a', 'a'), -1, null, 0, 0);
  }

  @Test
  public void andPropagatesFailure() {
    check("b", trace -> trace.charOf("a", 'a').and(),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void andFromMiddle() {
    check("bx", trace -> trace.charOf("a", 'a').and(),
        1, false, 1, trace("a@1->fail:1"), null, 1,
        failureMessage("a"), 0, 0);
  }

  // --- not-predicate -----------------------------------------------------

  @Test
  public void notSucceedsZeroWidthWhenDelegateFails() {
    check("b", trace -> trace.charOf("a", 'a').not(),
        0, true, 0, trace("a@0->fail:0"), null, -1, null, 0, 0);
  }

  @Test
  public void notFailsWhenDelegateSucceeds() {
    check("a", trace -> trace.charOf("a", 'a').not("nope"),
        0, false, 0, trace("a@0->ok:1"), null, 0, "nope", 0, 0);
  }

  @Test
  public void notFromMiddle() {
    check("aa", trace -> trace.charOf("a", 'a').not("nope"),
        1, false, 1, trace("a@1->ok:2"), null, 1, "nope", 0, 0);
  }

  // --- optional ----------------------------------------------------------

  @Test
  public void optionalConsumesOnSuccess() {
    check("a", trace -> trace.charOf("a", 'a').optional(),
        0, true, 1, trace("a@0->ok:1"), 'a', -1, null, 0, 0);
  }

  @Test
  public void optionalSucceedsAtSamePositionOnFailure() {
    check("b", trace -> trace.charOf("a", 'a').optional(),
        0, true, 0, trace("a@0->fail:0"), null, -1, null, 0, 0);
  }

  @Test
  public void optionalCustomOtherwise() {
    check("b", trace -> trace.charOf("a", 'a').optional("x"),
        0, true, 0, trace("a@0->fail:0"), "x", -1, null, 0, 0);
  }

  @Test
  public void optionalEmptyInput() {
    check("", trace -> trace.charOf("a", 'a').optional(),
        0, true, 0, trace("a@0->fail:0"), null, -1, null, 0, 0);
  }

  // --- possessive repeat -------------------------------------------------

  @Test
  public void possessiveStarConsumesUntilEnd() {
    check("aaa", trace -> trace.charOf("a", 'a').star(),
        0, true, 3,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->ok:3", "a@3->fail:3"),
        java.util.Arrays.asList('a', 'a', 'a'), -1, null, 0, 0);
  }

  @Test
  public void possessiveStarStopsAtNonMatch() {
    check("aab", trace -> trace.charOf("a", 'a').star(),
        0, true, 2,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->fail:2"),
        java.util.Arrays.asList('a', 'a'), -1, null, 0, 0);
  }

  @Test
  public void possessiveStarEmptyInput() {
    check("", trace -> trace.charOf("a", 'a').star(),
        0, true, 0, trace("a@0->fail:0"),
        java.util.Collections.emptyList(), -1, null, 0, 0);
  }

  @Test
  public void possessiveBoundedZeroWidthDoesNotLoop() {
    check("ab", trace -> trace.epsilon("e").repeat(0, 2),
        0, true, 0, trace("e@0->ok:0", "e@0->ok:0"),
        java.util.Arrays.asList(null, null), -1, null, 0, 0);
  }

  @Test
  public void possessivePlusMinNotMet() {
    check("b", trace -> trace.charOf("a", 'a').plus(),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void possessiveRepeatMinTwoOnlyOneAvailable() {
    check("ab", trace -> trace.charOf("a", 'a').repeat(2, 3),
        0, false, 0, trace("a@0->ok:1", "a@1->fail:1"), null, 1,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void possessiveExactBoundaryProbesNoFurther() {
    check("aa", trace -> trace.charOf("a", 'a').repeat(2, 2),
        0, true, 2, trace("a@0->ok:1", "a@1->ok:2"),
        java.util.Arrays.asList('a', 'a'), -1, null, 0, 0);
  }

  @Test
  public void possessiveExactBoundaryInsufficient() {
    check("a", trace -> trace.charOf("a", 'a').repeat(2, 2),
        0, false, 0, trace("a@0->ok:1", "a@1->fail:1"), null, 1,
        failureMessage("a"), 0, 0);
  }

  // --- greedy repeat -----------------------------------------------------

  @Test
  public void greedyStarBacktracksToLimit() {
    check("aab", trace -> trace.charOf("a", 'a')
            .starGreedy(trace.charOf("b", 'b')),
        0, true, 2,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->fail:2", "b@2->ok:3"),
        java.util.Arrays.asList('a', 'a'), -1, null, 0, 0);
  }

  @Test
  public void greedyStarUnwindsPositionsInOrderWhenLimitFails() {
    // Locks the unwind order 2 -> 1 -> 0 and the reported failure at 0.
    check("aa", trace -> trace.charOf("a", 'a')
            .starGreedy(trace.failing("l")),
        0, false, 0,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->fail:2",
            "l@2->fail:2", "l@1->fail:1", "l@0->fail:0"),
        null, 0, failureMessage("l"), 0, 0);
  }

  @Test
  public void greedyPlusMinNotMet() {
    check("bb", trace -> trace.charOf("a", 'a')
            .plusGreedy(trace.charOf("b", 'b')),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void greedyBoundedZeroWidthDoesNotLoop() {
    check("b", trace -> trace.epsilon("e")
            .repeatGreedy(trace.charOf("b", 'b'), 0, 2),
        0, true, 0, trace("e@0->ok:0", "e@0->ok:0", "b@0->ok:1"),
        java.util.Arrays.asList(null, null), -1, null, 0, 0);
  }

  @Test
  public void greedyBoundedStopsAtMaxThenUnwinds() {
    // At max repetitions the delegate is never probed at position 2, even
    // though more input is available.
    check("aa", trace -> trace.charOf("a", 'a')
            .repeatGreedy(trace.failing("l"), 0, 2),
        0, false, 0,
        trace("a@0->ok:1", "a@1->ok:2",
            "l@2->fail:2", "l@1->fail:1", "l@0->fail:0"),
        null, 0, failureMessage("l"), 0, 0);
  }

  // --- lazy repeat -------------------------------------------------------

  @Test
  public void lazyStarMatchesLimitImmediately() {
    check("b", trace -> trace.charOf("a", 'a')
            .starLazy(trace.charOf("b", 'b')),
        0, true, 0, trace("b@0->ok:1"),
        java.util.Collections.emptyList(), -1, null, 0, 0);
  }

  @Test
  public void lazyStarRepeatsUntilLimit() {
    check("aab", trace -> trace.charOf("a", 'a')
            .starLazy(trace.charOf("b", 'b')),
        0, true, 2,
        trace("b@0->fail:0", "a@0->ok:1",
            "b@1->fail:1", "a@1->ok:2", "b@2->ok:3"),
        java.util.Arrays.asList('a', 'a'), -1, null, 0, 0);
  }

  @Test
  public void lazyPlusMinNotMet() {
    check("b", trace -> trace.charOf("a", 'a')
            .plusLazy(trace.charOf("b", 'b')),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void lazyBoundedFailsWhenMaxReached() {
    check("a", trace -> trace.charOf("a", 'a')
            .repeatLazy(trace.failing("l"), 0, 1),
        0, false, 0,
        trace("l@0->fail:0", "a@0->ok:1", "l@1->fail:1"),
        null, 1, failureMessage("l"), 0, 0);
  }

  @Test
  public void lazyFailsWhenDelegateFailsBeforeLimit() {
    check("b", trace -> trace.charOf("a", 'a')
            .starLazy(trace.failing("l")),
        0, false, 0, trace("l@0->fail:0", "a@0->fail:0"), null, 0,
        failureMessage("l"), 0, 0);
  }

  @Test
  public void lazyBoundedZeroWidthMatchesImmediately() {
    check("b", trace -> trace.epsilon("e")
            .repeatLazy(trace.charOf("b", 'b'), 0, 2),
        0, true, 0, trace("b@0->ok:1"),
        java.util.Collections.emptyList(), -1, null, 0, 0);
  }

  // --- flatten -----------------------------------------------------------

  @Test
  public void flattenSuccess() {
    check("ab", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b'))
        .flatten(), 0, true, 2, trace("a@0->ok:1", "b@1->ok:2"),
        "ab", -1, null, 0, 0);
  }

  @Test
  public void flattenFailure() {
    check("ab", trace -> trace.charOf("a", 'a').seq(trace.charOf("c", 'c'))
        .flatten(), 0, false, 0, trace("a@0->ok:1", "c@1->fail:1"), null, 1,
        failureMessage("c"), 0, 0);
  }

  @Test
  public void flattenZeroWidth() {
    check("ab", trace -> trace.epsilon("e").flatten(),
        0, true, 0, trace("e@0->ok:0"), "", -1, null, 0, 0);
  }

  @Test
  public void flattenWithMessageUsesFastPathSuccess() {
    check("a", trace -> trace.charOf("a", 'a').flatten("boom"),
        0, true, 1, trace("a@0->ok:1"), "a", -1, null, 0, 0);
  }

  @Test
  public void flattenWithMessageReportsCustomFailure() {
    check("b", trace -> trace.charOf("a", 'a').flatten("boom"),
        0, false, 0, trace("a@0->fail:0"), null, 0, "boom", 0, 0);
  }

  // --- token -------------------------------------------------------------

  @Test
  public void tokenSuccess() {
    check("ab", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b'))
        .token(), 0, true, 2, trace("a@0->ok:1", "b@1->ok:2"),
        Trace.token(0, 2, "ab", java.util.Arrays.asList('a', 'b')),
        -1, null, 0, 0);
  }

  @Test
  public void tokenFailure() {
    check("ab", trace -> trace.charOf("a", 'a').seq(trace.charOf("c", 'c'))
        .token(), 0, false, 0, trace("a@0->ok:1", "c@1->fail:1"), null, 1,
        failureMessage("c"), 0, 0);
  }

  @Test
  public void tokenZeroWidth() {
    check("ab", trace -> trace.epsilon("e").token(),
        0, true, 0, trace("e@0->ok:0"),
        Trace.token(0, 0, "", null), -1, null, 0, 0);
  }

  // --- action side-effect contract --------------------------------------

  @Test
  public void plainMapRunsOnSlowPathOnly() {
    // Characterization: a plain (supposedly pure) action is skipped by the
    // fast path. This policy is part of the contract and must not change.
    check("a", trace -> trace.charOf("a", 'a').map(trace.action("m")),
        0, true, 1, trace("a@0->ok:1"), "m(a)", -1, null, 0, 1);
  }

  @Test
  public void plainMapFailureRunsNowhere() {
    check("b", trace -> trace.charOf("a", 'a').map(trace.action("m")),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void sideEffectingMapRunsOnBothPaths() {
    // mapWithSideEffects falls back to the slow path, so the callback runs.
    check("a", trace -> trace.charOf("a", 'a')
            .mapWithSideEffects(trace.action("s")),
        0, true, 1, trace("a@0->ok:1"), "s(a)", -1, null, 1, 1);
  }

  @Test
  public void nestedActionsFastFallbackExecutesWholeChain() {
    check("a", trace -> trace.charOf("a", 'a').map(trace.action("m"))
            .mapWithSideEffects(trace.action("s")),
        0, true, 1, trace("a@0->ok:1"), "s(m(a))", -1, null, 2, 2);
  }

  @Test
  public void actionAfterRepeatSkippedOnFastPath() {
    check("aaa", trace -> trace.charOf("a", 'a').star().map(trace.action("m")),
        0, true, 3,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->ok:3", "a@3->fail:3"),
        "m([a, a, a])", -1, null, 0, 1);
  }

  @Test
  public void sideEffectingActionAfterRepeatRunsOnFastPath() {
    check("aaa", trace -> trace.charOf("a", 'a').star()
            .mapWithSideEffects(trace.action("s")),
        0, true, 3,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->ok:3", "a@3->fail:3"),
        "s([a, a, a])", -1, null, 1, 1);
  }

  // --- trimming ----------------------------------------------------------

  @Test
  public void trimmingConsumesBothSides() {
    check(" a ", trace -> trace.charOf("a", 'a')
            .trim(trace.charOf("s", ' '), trace.charOf("s", ' ')),
        0, true, 3,
        trace("s@0->ok:1", "s@1->fail:1", "a@1->ok:2",
            "s@2->ok:3", "s@3->fail:3"),
        'a', -1, null, 0, 0);
  }

  @Test
  public void trimmingNothingToConsume() {
    check("a", trace -> trace.charOf("a", 'a')
            .trim(trace.charOf("s", ' '), trace.charOf("s", ' ')),
        0, true, 1,
        trace("s@0->fail:0", "a@0->ok:1", "s@1->fail:1"),
        'a', -1, null, 0, 0);
  }

  @Test
  public void trimmingFailureAfterLeftTrim() {
    check(" b", trace -> trace.charOf("a", 'a')
            .trim(trace.charOf("s", ' '), trace.charOf("s", ' ')),
        0, false, 0,
        trace("s@0->ok:1", "s@1->fail:1", "a@1->fail:1"),
        null, 1, failureMessage("a"), 0, 0);
  }

  // --- end of input ------------------------------------------------------

  @Test
  public void endOfInputAtEnd() {
    check("ab", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b'))
        .end(), 0, true, 2,
        trace("a@0->ok:1", "b@1->ok:2"),
        java.util.Arrays.asList('a', 'b'), -1, null, 0, 0);
  }

  @Test
  public void endOfInputFailsWithTrailingInput() {
    check("abc", trace -> trace.charOf("a", 'a').seq(trace.charOf("b", 'b'))
        .end("end"), 0, false, 0,
        trace("a@0->ok:1", "b@1->ok:2"), null, 2, "end", 0, 0);
  }

  // --- settable / delegate ----------------------------------------------

  @Test
  public void settableForwardsBothPathsSuccess() {
    check("a", trace -> SettableParser.with(trace.charOf("a", 'a')),
        0, true, 1, trace("a@0->ok:1"), 'a', -1, null, 0, 0);
  }

  @Test
  public void settableForwardsFailure() {
    check("b", trace -> SettableParser.with(trace.charOf("a", 'a')),
        0, false, 0, trace("a@0->fail:0"), null, 0,
        failureMessage("a"), 0, 0);
  }

  @Test
  public void plainDelegateEmulatesFastViaParseOn() {
    // Characterization: a bare DelegateParser does not override fastParseOn;
    // the default Parser emulation runs parseOn (and constructs a Result).
    // Its observable transitions must still agree with the slow path.
    check("a", trace -> new DelegateParser(trace.charOf("a", 'a')),
        0, true, 1, trace("a@0->ok:1"), 'a', -1, null, 0, 0);
  }

  @Test
  public void continuationEmulatesFastViaParseOn() {
    check("a", trace -> trace.charOf("a", 'a')
            .callCC((continuation, context) -> continuation.apply(context)),
        0, true, 1, trace("a@0->ok:1"), 'a', -1, null, 0, 0);
  }

  // --- nested flatten / token / action ----------------------------------

  @Test
  public void flattenOfTokenOfAction() {
    // Characterization: the no-message FlattenParser does not override the
    // fast path, so the default emulation runs the delegate's parseOn and the
    // inner (supposedly pure) action executes once even on the fast path.
    // This differs from a directly applied plain action (skipped on the fast
    // path) and is pinned here so a refactor cannot silently change it.
    check("aa", trace -> trace.charOf("a", 'a').star()
            .map(trace.action("m")).token().flatten(),
        0, true, 2,
        trace("a@0->ok:1", "a@1->ok:2", "a@2->fail:2"),
        "aa", -1, null, 1, 1);
  }

  // ------------------------------------------------------------------
  // Harness glue
  // ------------------------------------------------------------------

  @FunctionalInterface
  private interface CombinatorBuilder {
    Parser build(Trace trace);
  }

  private void check(String buffer, CombinatorBuilder builder, int position,
      boolean success, int end, java.util.List<String> events, Object value,
      int failurePosition, String message, int fastSideEffects,
      int slowSideEffects) {
    Trace trace = new Trace(buffer);
    trace.assertContract(() -> {
      trace.reset();
      return builder.build(trace);
    }, position, success, end, events, value, failurePosition, message,
        fastSideEffects, slowSideEffects);
  }

  private void assertWithShared(String buffer, Supplier<Parser> supplier,
      Trace trace, int position, boolean success, int end,
      java.util.List<String> events, Object value, int failurePosition,
      String message, int fastSideEffects, int slowSideEffects) {
    trace.assertContract(supplier, position, success, end, events, value,
        failurePosition, message, fastSideEffects, slowSideEffects);
  }
}
