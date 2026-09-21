package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.petitparser.contract.Contract.assertContract;
import static org.petitparser.contract.Contract.assertFailure;
import static org.petitparser.contract.Contract.assertSuccess;
import static org.petitparser.contract.Contract.runBothPaths;
import static org.petitparser.parser.primitive.CharacterParser.any;
import static org.petitparser.parser.primitive.CharacterParser.of;

import org.petitparser.parser.primitive.EpsilonParser;

/**
 * Executable contract between {@link Parser#parseOn} and
 * {@link Parser#fastParseOn} for the combinators that implement both paths.
 *
 * <p>Every case is executed from both entry points and asserts:
 * <ul>
 *   <li>identical success/failure decision and identical final position;</li>
 *   <li>exact value, failure position and message on the full path;</li>
 *   <li>identical child invocation positions (rollback order) and the exact
 *       entry point used for each child (observable parsers).</li>
 * </ul>
 *
 * <p>Changing the position calculation, the rollback order or the side-effect
 * policy in only one path fails this test.
 */
public class FastSlowContractTest {

  private static final String A_EXPECTED = "'a' expected";
  private static final String B_EXPECTED = "'b' expected";
  private static final String ANY_EXPECTED = "any character expected";

  // ---------------------------------------------------------------- sequence

  @Test
  public void sequenceEmptyInputSuccessExact() {
    Parser parser = of('a').seq(of('b'));
    assertSuccess(parser, "ab", 0, Arrays.asList('a', 'b'), 2);
  }

  @Test
  public void sequenceStartsInTheMiddle() {
    Parser parser = of('a').seq(of('b'));
    assertSuccess(parser, "xab", 1, Arrays.asList('a', 'b'), 3);
  }

  @Test
  public void sequenceChildConsumesThenFails() {
    Parser parser = of('a').seq(of('b'));
    // First child consumes 'a' at position 0, second child fails at 1.
    assertFailure(parser, "ac", 0, 1, B_EXPECTED);
    assertFailure(parser, "a", 0, 1, B_EXPECTED);
  }

  @Test
  public void sequenceFirstChildFailsBeforeConsuming() {
    Parser parser = of('a').seq(of('b'));
    assertFailure(parser, "ba", 0, 0, A_EXPECTED);
  }

  @Test
  public void sequenceWithZeroWidthSuccess() {
    Parser parser = new EpsilonParser().seq(of('a')).seq(new EpsilonParser());
    assertSuccess(parser, "a", 0, Arrays.asList(null, 'a', null), 1);
    assertContract(parser, "a", 1);
  }

  @Test
  public void sequenceOfZeroParsersIsZeroWidthSuccess() {
    Parser parser = new org.petitparser.parser.combinators.SequenceParser();
    assertSuccess(parser, "abc", 2, Collections.emptyList(), 2);
    assertFailure(of('x').seq(), "abc", 0, 0, "'x' expected");
  }

  @Test
  public void sequenceChildInvocationPositions() {
    ObservableParser first = new ObservableParser(of('a'));
    ObservableParser second = new ObservableParser(of('b'));
    Parser parser = new org.petitparser.parser.combinators.SequenceParser(
        first, second);
    runBothPaths(parser, "ab", 0,
        Arrays.asList("parseOn", "parseOn"), Arrays.asList(0, 1),
        Arrays.asList("fastParseOn", "fastParseOn"), Arrays.asList(0, 1),
        first, second);
  }

  @Test
  public void sequencePartialFailureNeverInvokesRemainingChildren() {
    ObservableParser first = new ObservableParser(of('a'));
    ObservableParser second = new ObservableParser(of('b'));
    ObservableParser third = new ObservableParser(of('c'));
    Parser parser = new org.petitparser.parser.combinators.SequenceParser(
        first, second, third);
    runBothPaths(parser, "ax", 0,
        Arrays.asList("parseOn", "parseOn"), Arrays.asList(0, 1),
        Arrays.asList("fastParseOn", "fastParseOn"), Arrays.asList(0, 1),
        first, second, third);
  }

  // ------------------------------------------------------------------ choice

  @Test
  public void choiceFirstAlternativeSucceeds() {
    Parser parser = of('a').or(of('b'));
    assertSuccess(parser, "a", 0, 'a', 1);
    assertSuccess(parser, "b", 0, 'b', 1);
  }

  @Test
  public void choiceAllAlternativesFailReportsLastFailure() {
    Parser parser = of('a').or(of('b'));
    assertFailure(parser, "c", 0, 0, B_EXPECTED);
  }

  @Test
  public void choiceRollsBackToStartForSecondAlternative() {
    // First alternative consumes two characters before failing; the choice
    // must retry the second alternative at the original position.
    Parser parser = of('a').seq(of('a')).or(of('a').seq(of('b')));
    assertSuccess(parser, "ab", 0, Arrays.asList('a', 'b'), 2);
    assertFailure(parser, "ac", 0, 1, B_EXPECTED);
  }

  @Test
  public void choiceStartsInTheMiddle() {
    Parser parser = of('a').or(of('b'));
    assertSuccess(parser, "xb", 1, 'b', 2);
    assertFailure(parser, "xc", 1, 1, B_EXPECTED);
  }

  @Test
  public void choiceWithZeroWidthAlternative() {
    Parser parser = of('a').or(new EpsilonParser());
    assertSuccess(parser, "b", 0, null, 0);
    Parser failingFirst =
        org.petitparser.parser.primitive.FailureParser.withMessage(
            "boom").or(of('a'));
    assertSuccess(failingFirst, "a", 0, 'a', 1);
  }

  @Test
  public void choiceSelectFarthestJoiner() {
    Parser parser = new org.petitparser.parser.combinators.ChoiceParser(
        new org.petitparser.utils.FailureJoiner.SelectFarthest(),
        of('a').seq(of('b')), of('c'));
    assertFailure(parser, "ax", 0, 1, B_EXPECTED);
  }

  @Test
  public void choiceRetriesAllChildrenAtTheSamePosition() {
    ObservableParser first = new ObservableParser(of('a'));
    ObservableParser second = new ObservableParser(of('b'));
    Parser parser = new org.petitparser.parser.combinators.ChoiceParser(
        first, second);
    // Every alternative is tried at position 0; the second one succeeds.
    runBothPaths(parser, "b", 0,
        Arrays.asList("parseOn", "parseOn"), Arrays.asList(0, 0),
        Arrays.asList("fastParseOn", "fastParseOn"), Arrays.asList(0, 0),
        first, second);
  }

  // ---------------------------------------------------------------- optional

  @Test
  public void optionalPresent() {
    Parser parser = of('a').optional();
    assertSuccess(parser, "ab", 0, 'a', 1);
  }

  @Test
  public void optionalAbsentIsZeroWidthSuccessWithOtherwise() {
    Parser parser = of('a').optional("none");
    assertSuccess(parser, "b", 0, "none", 0);
    assertSuccess(parser, "abc", 2, "none", 2);
  }

  @Test
  public void optionalChildConsumesNothing() {
    Parser parser = new EpsilonParser().optional();
    assertSuccess(parser, "x", 0, null, 0);
  }

  // --------------------------------------------------------------------- and

  @Test
  public void andLookaheadSucceedsZeroWidth() {
    Parser parser = of('a').and().seq(of('a'));
    assertSuccess(parser, "a", 0, Arrays.asList('a', 'a'), 1);
  }

  @Test
  public void andLookaheadPropagatesFailure() {
    Parser parser = of('a').and();
    assertFailure(parser, "b", 0, 0, A_EXPECTED);
    assertSuccess(parser, "xa", 1, 'a', 1);
  }

  @Test
  public void andLookaheadDoesNotConsumeAfterChildSuccess() {
    ObservableParser child = new ObservableParser(of('a'));
    Parser parser = new org.petitparser.parser.combinators.AndParser(child);
    runBothPaths(parser, "ab", 0,
        Arrays.asList("parseOn"), Arrays.asList(0),
        Arrays.asList("fastParseOn"), Arrays.asList(0),
        child);
    assertSuccess(parser, "ab", 0, 'a', 0);
  }

  // --------------------------------------------------------------------- not

  @Test
  public void notLookaheadSucceedsZeroWidthWhenDelegateFails() {
    Parser parser = of('a').not().seq(of('b'));
    assertSuccess(parser, "b", 0, Arrays.asList(null, 'b'), 1);
    assertSuccess(parser, "ab", 1, Arrays.asList(null, 'b'), 2);
  }

  @Test
  public void notLookaheadFailsAtOriginalPositionWithMessage() {
    Parser parser = of('a').not("nope");
    assertFailure(parser, "a", 0, 0, "nope");
  }

  @Test
  public void notLookaheadDoesNotConsume() {
    ObservableParser child = new ObservableParser(of('a'));
    Parser parser = new org.petitparser.parser.combinators.NotParser(child,
        "unexpected");
    runBothPaths(parser, "b", 0,
        Arrays.asList("parseOn"), Arrays.asList(0),
        Arrays.asList("fastParseOn"), Arrays.asList(0),
        child);
    assertSuccess(parser, "b", 0, null, 0);
  }
}
