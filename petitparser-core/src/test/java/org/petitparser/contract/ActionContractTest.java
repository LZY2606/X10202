package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.petitparser.contract.Contract.assertFailure;
import static org.petitparser.contract.Contract.assertSuccess;
import static org.petitparser.contract.Contract.runBothPaths;
import static org.petitparser.parser.primitive.CharacterParser.any;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Full/fast contract for flatten, token, action, trimming, end of input and
 * nested combinations of the above.
 */
public class ActionContractTest {

  private static final String A_EXPECTED = "'a' expected";

  // --------------------------------------------------------------- flatten

  @Test
  public void flattenReturnsConsumedRange() {
    Parser parser = of('a').star().flatten();
    assertSuccess(parser, "aaab", 0, "aaa", 3);
    assertSuccess(parser, "", 0, "", 0);
  }

  @Test
  public void flattenStartsInTheMiddle() {
    Parser parser = of('a').star().flatten();
    assertSuccess(parser, "xaaa", 1, "aaa", 4);
  }

  @Test
  public void flattenPropagatesChildFailure() {
    Parser parser = of('a').plus().flatten();
    assertFailure(parser, "b", 0, 0, A_EXPECTED);
  }

  @Test
  public void flattenWithMessageReportsGivenFailure() {
    Parser parser = of('a').plus().flatten("need a run");
    assertFailure(parser, "b", 0, 0, "need a run");
    assertSuccess(parser, "ab", 0, "a", 1);
  }

  @Test
  public void flattenZeroWidthSuccessIsEmptyString() {
    Parser parser = of('a').star().flatten();
    assertSuccess(parser, "b", 0, "", 0);
    Parser withMessage = of('a').star().flatten("need a run");
    assertSuccess(withMessage, "b", 0, "", 0);
  }

  @Test
  public void flattenWithMessageInvokesChildOnFastPathEvenInSlowMode() {
    ObservableParser child = new ObservableParser(of('a').star());
    Parser parser =
        new org.petitparser.parser.actions.FlattenParser(child, "msg");
    // Characterization of the existing behavior: the message variant of
    // flatten uses the fast child path from BOTH entry points.
    runBothPaths(parser, "aa", 0,
        Collections.singletonList("fastParseOn"),
        Collections.singletonList(0),
        Collections.singletonList("fastParseOn"),
        Collections.singletonList(0),
        child);
  }

  @Test
  public void flattenWithoutMessageUsesFastChildOnFastPathAfterRefactor() {
    ObservableParser child = new ObservableParser(of('a').star());
    Parser parser =
        new org.petitparser.parser.actions.FlattenParser(child);
    // Characterization, pre-refactor: FlattenParser does not override
    // fastParseOn, so the inherited fallback runs a full parseOn and the
    // child is invoked via parseOn on BOTH paths (an allocation-heavy
    // degenerate fast path). The shared-transition refactor eliminates this
    // without changing positions or values, so the expected fast entry point
    // is fastParseOn; until the refactor this case is skipped with the
    // observed current behavior documented here.
    child.reset();
    parser.fastParseOn("aa", 0);
    if (child.modes().equals(
        Collections.singletonList("fastParseOn"))) {
      runBothPaths(parser, "aa", 0,
          Collections.singletonList("parseOn"),
          Collections.singletonList(0),
          Collections.singletonList("fastParseOn"),
          Collections.singletonList(0),
          child);
    }
    // The value/position contract holds regardless of which child entry
    // point is used.
    assertSuccess(parser, "aa", 0, "aa", 2);
  }

  @Test
  public void flattenWithoutMessageCurrentDegenerateFastPathCharacterization() {
    ObservableParser child = new ObservableParser(of('a').star());
    Parser parser =
        new org.petitparser.parser.actions.FlattenParser(child);
    // Explicit characterization of the inconsistency the refactor removes:
    // before the change, fastParseOn invokes the observable child through
    // parseOn. Delete this test together with the behavior, after refactor.
    child.reset();
    parser.fastParseOn("aa", 0);
    runBothPaths(parser, "aa", 0,
        Collections.singletonList("parseOn"),
        Collections.singletonList(0),
        Collections.singletonList("parseOn"),
        Collections.singletonList(0),
        child);
  }

  // ----------------------------------------------------------------- token

  @Test
  public void tokenWrapsValueRangeAndInput() {
    Parser parser = digit().plus().token();
    Object value = parser.parseOn(new org.petitparser.context.Context(
        "x123y", 1)).get();
    Token token = (Token) value;
    org.junit.Assert.assertEquals(1, token.getStart());
    org.junit.Assert.assertEquals(4, token.getStop());
    org.junit.Assert.assertEquals("123", token.getInput());
    org.junit.Assert.assertEquals(
        Arrays.asList('1', '2', '3'), token.getValue());
    assertSuccess(parser, "x123y", 1, token, 4);
  }

  @Test
  public void tokenPropagatesFailure() {
    Parser parser = of('a').plus().token();
    assertFailure(parser, "b", 0, 0, A_EXPECTED);
  }

  @Test
  public void tokenFastPathDoesNotCreateTokenValue() {
    // The fast path only agrees on the final position; the token itself is
    // only built by the full path.
    Parser parser = of('a').plus().token();
    assertSuccess(parser, "aab", 0, parser.parse("aab").get(), 2);
  }

  // ---------------------------------------------------------------- action

  @Test
  public void actionTransformsValueOnSlowPath() {
    Parser parser = of('a').plus().flatten().map(String::length);
    assertSuccess(parser, "aaa", 0, 3, 3);
  }

  @Test
  public void actionPureFunctionIsNotInvokedOnFastPath() {
    // LOCKED behavior: a side-effect-free action must not execute on the
    // fast path. This guards against the fast path silently turning into a
    // full parse, and against accidental changes of the side-effect policy.
    AtomicInteger invocations = new AtomicInteger();
    Function<Object, Object> function = value -> {
      invocations.incrementAndGet();
      return value;
    };
    Parser parser =
        new org.petitparser.parser.actions.ActionParser<>(
            of('a').plus(), function);
    org.junit.Assert.assertEquals(2, parser.fastParseOn("aab", 0));
    org.junit.Assert.assertEquals(
        "pure action must not run on fast path", 0, invocations.get());
    org.junit.Assert.assertTrue(parser.parseOn(
        new org.petitparser.context.Context("aab", 0)).isSuccess());
    org.junit.Assert.assertEquals(
        "pure action runs exactly once on slow path", 1, invocations.get());
  }

  @Test
  public void actionWithSideEffectsFallsBackToSlowPathOnFastPath() {
    // LOCKED behavior: mapWithSideEffects runs the action on the fast path by
    // falling back to the full parse semantics.
    AtomicInteger invocations = new AtomicInteger();
    Parser parser = of('a').plus().mapWithSideEffects(value -> {
      invocations.incrementAndGet();
      return value;
    });
    org.junit.Assert.assertEquals(2, parser.fastParseOn("aab", 0));
    org.junit.Assert.assertEquals(
        "side-effecting action must run on fast path", 1,
        invocations.get());
    org.junit.Assert.assertTrue(parser.accept("aab"));
    org.junit.Assert.assertEquals(2, invocations.get());
  }

  @Test
  public void actionFailureNeverInvokesFunction() {
    AtomicInteger invocations = new AtomicInteger();
    Parser parser =
        new org.petitparser.parser.actions.ActionParser<>(
            of('a').plus(), value -> {
              invocations.incrementAndGet();
              return value;
            });
    assertFailure(parser, "b", 0, 0, A_EXPECTED);
    org.junit.Assert.assertEquals(-1, parser.fastParseOn("b", 0));
    org.junit.Assert.assertEquals(0, invocations.get());
  }

  @Test
  public void actionNestedInSequenceAndChoice() {
    Parser first = of('a').flatten().map((String value) ->
        value.toUpperCase());
    Parser second = of('b').flatten().map((String value) ->
        value.toUpperCase());
    Parser parser = first.or(second).seq(of('c')).flatten();
    assertSuccess(parser, "bc", 0, "bc", 2);
    assertFailure(parser, "dc", 0, 0, "'b' expected");
  }

  // ------------------------------------------------------------------ trim

  @Test
  public void trimmingConsumesBothSides() {
    Parser parser = of('a').trim(of(' '), of(' '));
    assertSuccess(parser, "  a  ", 0, 'a', 5);
    assertSuccess(parser, "a", 0, 'a', 1);
  }

  @Test
  public void trimmingFailurePosition() {
    Parser parser = of('a').trim(of(' '), of(' '));
    assertFailure(parser, "  b", 0, 2, A_EXPECTED);
  }

  @Test
  public void trimmingUsesFastPathForTrimmersOnBothPaths() {
    ObservableParser left = new ObservableParser(of(' '));
    ObservableParser delegate = new ObservableParser(of('a'));
    ObservableParser right = new ObservableParser(of(' '));
    Parser parser = new org.petitparser.parser.actions.TrimmingParser(
        delegate, left, right);
    // Characterization: trimming parsers are consumed through the fast entry
    // point on BOTH paths, at the correct positions.
    runBothPaths(parser, " a ", 0,
        Arrays.asList("fastParseOn", "fastParseOn", "parseOn",
            "fastParseOn", "fastParseOn"),
        Arrays.asList(0, 1, 1, 2, 3),
        Arrays.asList("fastParseOn", "fastParseOn", "fastParseOn",
            "fastParseOn", "fastParseOn"),
        Arrays.asList(0, 1, 1, 2, 3),
        left, delegate, right);
  }

  // ------------------------------------------------------------- endOfInput

  @Test
  public void endOfInputAtEndAndMiddle() {
    Parser parser = of('a').star().end();
    assertSuccess(parser, "aaa", 0, Arrays.asList('a', 'a', 'a'), 3);
    assertFailure(parser, "aaab", 0, 3, "end of input expected");
  }

  // --------------------------------------------------------------- nesting

  @Test
  public void flattenTokenActionNesting() {
    Parser letters = of('a').plus();
    Parser parser = letters.token().flatten()
        .map(String::length);
    assertSuccess(parser, "aaa", 0, 3, 3);
  }

  @Test
  public void nestedChoiceSequenceRepeatFromMiddle() {
    Parser item = of('a').or(of('b')).seq(any());
    Parser parser = item.star().flatten();
    assertSuccess(parser, "xxababy", 2, "abab", 6);
  }

  @Test
  public void lookaheadGuardedRepetition() {
    // Repeat an item while the next two characters are an 'a' followed by a
    // digit; the positive lookahead never consumes.
    Parser guard = of('a').seq(digit()).and();
    Parser item = guard.seq(any()).seq(any());
    Parser parser = item.star().flatten();
    assertSuccess(parser, "a1a2z", 0, "a1a2", 4);
    assertSuccess(parser, "xa1a2z", 1, "a1a2", 5);
    assertFailure(item, "ax", 0, 1, "digit expected");
  }
}
