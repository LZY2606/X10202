package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.ChoiceParser;
import org.petitparser.parser.combinators.SequenceParser;
import org.petitparser.parser.repeating.GreedyRepeatingParser;
import org.petitparser.parser.repeating.LazyRepeatingParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.petitparser.contract.Contract.assertContract;
import static org.petitparser.contract.Contract.assertFailure;
import static org.petitparser.contract.Contract.assertSuccess;
import static org.petitparser.contract.Contract.runBothPaths;
import static org.petitparser.parser.primitive.CharacterParser.any;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Full/fast contract for possessive, greedy and lazy repeating parsers.
 */
public class RepeatContractTest {

  private static final String A_EXPECTED = "'a' expected";
  private static final String B_EXPECTED = "'b' expected";

  // ------------------------------------------------------------ possessive

  @Test
  public void possessiveStarEmptyInput() {
    Parser parser = of('a').star();
    assertSuccess(parser, "", 0, Collections.emptyList(), 0);
    assertSuccess(parser, "b", 0, Collections.emptyList(), 0);
  }

  @Test
  public void possessiveStarGreedyToEnd() {
    Parser parser = of('a').star();
    assertSuccess(parser, "aaa", 0, Arrays.asList('a', 'a', 'a'), 3);
    assertSuccess(parser, "xaaax", 1, Arrays.asList('a', 'a', 'a'), 4);
  }

  @Test
  public void possessivePlusRequiresMinOne() {
    Parser parser = of('a').plus();
    assertFailure(parser, "", 0, 0, A_EXPECTED);
    assertFailure(parser, "b", 0, 0, A_EXPECTED);
    assertSuccess(parser, "a", 0, Collections.singletonList('a'), 1);
  }

  @Test
  public void possessiveBoundedExactBoundaries() {
    Parser twoToFour = new PossessiveRepeatingParser(of('a'), 2, 4);
    assertFailure(twoToFour, "a", 0, 1, A_EXPECTED);
    assertSuccess(twoToFour, "aa", 0, Arrays.asList('a', 'a'), 2);
    assertSuccess(twoToFour, "aaaa", 0,
        Arrays.asList('a', 'a', 'a', 'a'), 4);
    // Possessive: stops at max and never looks past it.
    assertSuccess(twoToFour, "aaaaa", 0,
        Arrays.asList('a', 'a', 'a', 'a'), 4);
    Parser zeroTimes = new PossessiveRepeatingParser(of('a'), 0, 0);
    assertSuccess(zeroTimes, "aaa", 0, Collections.emptyList(), 0);
  }

  @Test
  public void possessiveAfterChildConsumesThenFailsSucceedsAtMin() {
    // Blind repetition: a failing child after at least min successes ends
    // the repetition successfully at the current position.
    Parser parser = of('a').repeat(1, 3);
    assertSuccess(parser, "aab", 0, Arrays.asList('a', 'a'), 2);
  }

  @Test
  public void possessiveStarChildInvocationOrder() {
    ObservableParser child = new ObservableParser(of('a'));
    Parser parser = new PossessiveRepeatingParser(child, 0,
        org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    // Child is tried at every position, once more at the final stop.
    runBothPaths(parser, "aab", 0,
        Arrays.asList("parseOn", "parseOn", "parseOn"),
        Arrays.asList(0, 1, 2),
        Arrays.asList("fastParseOn", "fastParseOn", "fastParseOn"),
        Arrays.asList(0, 1, 2),
        child);
  }

  // ---------------------------------------------------------------- greedy

  @Test
  public void greedyStarSucceedsAtStartWhenLimitMatchesImmediately() {
    Parser parser = any().starGreedy(of('b'));
    assertSuccess(parser, "b", 0, Collections.emptyList(), 0);
  }

  @Test
  public void greedyConsumesThenBacktracksToLimit() {
    Parser parser = any().starGreedy(of('b'));
    assertSuccess(parser, "aaab", 0,
        Arrays.asList('a', 'a', 'a'), 3);
    assertSuccess(parser, "xaaabx", 1,
        Arrays.asList('a', 'a', 'a'), 4);
  }

  @Test
  public void greedyPlusBacktracksToLimit() {
    Parser parser = of('a').plusGreedy(of('b'));
    assertSuccess(parser, "ab", 0, Collections.singletonList('a'), 1);
    assertSuccess(parser, "aaab", 0,
        Arrays.asList('a', 'a', 'a'), 3);
  }

  @Test
  public void greedyLimitNeverMatchedReturnsLimitFailureAtStart() {
    Parser parser = of('a').starGreedy(of('b'));
    // Consumes all 'a', then backtracking evaluates the limit at every
    // position down to the start; the reported failure is the last one, at
    // the original position.
    assertFailure(parser, "aaa", 0, 0, B_EXPECTED);
  }

  @Test
  public void greedyMinNotSatisfied() {
    Parser parser = of('a').repeatGreedy(of('b'), 2,
        org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    assertFailure(parser, "ab", 0, 1, A_EXPECTED);
  }

  @Test
  public void greedyBoundedStopsAtMax() {
    Parser parser = any().repeatGreedy(of('b'), 0, 2);
    // Max only caps consumption; backtracking still probes positions below
    // max and the final failure is reported at the original position.
    assertFailure(parser, "aaab", 0, 0, B_EXPECTED);
    assertSuccess(parser, "aab", 0, Arrays.asList('a', 'a'), 2);
  }

  @Test
  public void greedyBacktracksInReversePositionOrder() {
    ObservableParser child = new ObservableParser(any());
    ObservableParser limit = new ObservableParser(of('b'));
    Parser parser = new GreedyRepeatingParser(child, limit, 0,
        org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    // any() consumes the trailing 'b' too; the child is tried at 0..4 and
    // fails at the end. Backtracking evaluates the limit at 4 (fail) then 3
    // (success), in reverse position order.
    List<String> childModes = Arrays.asList("parseOn", "parseOn",
        "parseOn", "parseOn", "parseOn");
    List<Integer> childPositions = Arrays.asList(0, 1, 2, 3, 4);
    List<String> limitModes =
        Arrays.asList("parseOn", "parseOn");
    List<Integer> limitPositions = Arrays.asList(4, 3);
    List<String> fastChildModes = Arrays.asList("fastParseOn",
        "fastParseOn", "fastParseOn", "fastParseOn", "fastParseOn");
    List<String> fastLimitModes = Arrays.asList("fastParseOn",
        "fastParseOn");
    runBothPaths(parser, "aaab", 0,
        concat(childModes, limitModes),
        concat(childPositions, limitPositions),
        concat(fastChildModes, fastLimitModes),
        concat(childPositions, limitPositions),
        child, limit);
  }

  // ------------------------------------------------------------------ lazy

  @Test
  public void lazyStarStopsAtEarliestLimit() {
    Parser parser = any().starLazy(of('b'));
    assertSuccess(parser, "b", 0, Collections.emptyList(), 0);
    assertSuccess(parser, "aaab", 0,
        Arrays.asList('a', 'a', 'a'), 3);
    assertSuccess(parser, "xaaabx", 1,
        Arrays.asList('a', 'a', 'a'), 4);
  }

  @Test
  public void lazyPlusRequiresMinOne() {
    Parser parser = of('a').plusLazy(of('b'));
    // Failure while satisfying the minimum comes from the delegate.
    assertFailure(parser, "b", 0, 0, A_EXPECTED);
    assertSuccess(parser, "aab", 0, Arrays.asList('a', 'a'), 2);
  }

  @Test
  public void lazyDelegateFailureReturnsLimitFailure() {
    // Characterization of the existing behavior: when the limit fails and
    // the child fails too, the reported failure is the limit failure at the
    // current position (not the child failure further down).
    Parser parser = of('a').starLazy(of('b'));
    assertFailure(parser, "aac", 0, 2, B_EXPECTED);
    assertContract(parser, "", 0);
  }

  @Test
  public void lazyMinFailureReturnsChildFailure() {
    Parser parser = of('a').repeatLazy(of('b'), 2,
        org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    assertFailure(parser, "ab", 0, 1, A_EXPECTED);
  }

  @Test
  public void lazyBoundedAtMaxReturnsLimitFailure() {
    Parser parser = any().repeatLazy(of('b'), 0, 2);
    assertFailure(parser, "aaab", 0, 2, B_EXPECTED);
  }

  @Test
  public void lazyAlternatesLimitThenChild() {
    ObservableParser child = new ObservableParser(any());
    ObservableParser limit = new ObservableParser(of('b'));
    Parser parser = new LazyRepeatingParser(child, limit, 0,
        org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    // limit@0, child@0, limit@1, child@1, limit@2, child@2, limit@3.
    // runBothPaths reports per-observable traces, so grouped by observable.
    runBothPaths(parser, "aaab", 0,
        Arrays.asList("parseOn", "parseOn", "parseOn", "parseOn",
            "parseOn", "parseOn", "parseOn"),
        Arrays.asList(0, 1, 2, 0, 1, 2, 3),
        Arrays.asList("fastParseOn", "fastParseOn", "fastParseOn",
            "fastParseOn", "fastParseOn", "fastParseOn", "fastParseOn"),
        Arrays.asList(0, 1, 2, 0, 1, 2, 3),
        child, limit);
  }

  @Test
  public void nestedLookaheadWithRepeat() {
    // Each item must be followed by 'a' (and-predicate), repeated; the
    // lookahead consumes nothing on either path.
    Parser item = of('a').and().seq(any());
    Parser parser = item.star();
    assertSuccess(parser, "aa", 0,
        Arrays.asList(Arrays.asList('a', 'a'),
            Arrays.asList('a', 'a')),
        2);
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    java.util.List<T> result = new java.util.ArrayList<>(first);
    result.addAll(second);
    return result;
  }

}
