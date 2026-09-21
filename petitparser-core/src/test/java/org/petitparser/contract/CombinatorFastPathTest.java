package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;

import static org.petitparser.contract.ScriptedParser.Reaction.fail;
import static org.petitparser.contract.ScriptedParser.Reaction.succeed;

/**
 * Characterization contract for {@code sequence}, {@code choice},
 * {@code optional} and the {@code and}/{@code not} lookahead combinators.
 *
 * <p>Each case drives {@link Parser#parseOn} and
 * {@link Parser#fastParseOn} with an identical scripted transition definition
 * and pins down success, final position, child invocation order, slow-path
 * value as well as failure position and message.
 */
public class CombinatorFastPathTest {

  private static ScriptedParser scripted(String name,
      ScriptedParser.Reaction... reactions) {
    return new ScriptedParser(name, reactions);
  }

  // ---------------------------------------------------------------- sequence

  @Test
  public void sequenceAllConsumeFromMiddle() {
    ScriptedParser a = scripted("a",
        succeed(1, 2, 'A'), succeed(1, 2, 'A'));
    ScriptedParser b = scripted("b",
        succeed(2, 4, "BB"), succeed(2, 4, "BB"));
    Parser parser = a.seq(b);
    FastPathContract.assertAgree(parser, scripted("probe"), "abcde", 1);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        succeed(1, 2, 'A'), succeed(2, 4, "BB")), "abcde", 1,
        java.util.Arrays.asList('A', "BB"), 4);
  }

  @Test
  public void sequenceStartsAtEmptyInputZeroWidth() {
    ScriptedParser a = scripted("a", succeed(0, 'A'), succeed(0, 'A'));
    ScriptedParser b = scripted("b", succeed(0, 'B'), succeed(0, 'B'));
    Parser parser = a.seq(b);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "", 0, 0);
  }

  @Test
  public void sequencePartialFailureRollsBack() {
    // First child consumes 0 -> 2, second child fails at 2; both paths must
    // invoke the children at the same positions and report the same failure.
    ScriptedParser a = scripted("a",
        succeed(0, 2, 'A'), succeed(0, 2, 'A'));
    ScriptedParser b = scripted("b",
        fail(2, "boom"), fail(2, "boom"));
    Parser parser = a.seq(b);
    FastPathContract.assertAgree(parser, scripted("probe"), "abcde", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        succeed(0, 2, 'A'), fail(2, "boom")), "abcde", 0, 2, "boom");
  }

  @Test
  public void sequenceFirstChildFails() {
    ScriptedParser a = scripted("a", fail(0, "no-a"), fail(0, "no-a"));
    ScriptedParser b = scripted("b",
        succeed(0, 'B'), succeed(0, 'B'));
    Parser parser = a.seq(b);
    FastPathContract.assertAgree(parser, scripted("probe"), "abc", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(0, "no-a"), fail(0, "no-a")), "abc", 0, 0, "no-a");
  }

  // ------------------------------------------------------------------ choice

  @Test
  public void choiceFirstAlternativeWins() {
    ScriptedParser a = scripted("a",
        succeed(1, 3, 'A'), succeed(1, 3, 'A'));
    ScriptedParser b = scripted("b",
        succeed(1, 2, 'B'), succeed(1, 2, 'B'));
    Parser parser = a.or(b);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abcde", 1, 3);
  }

  @Test
  public void choiceRollsBackToSecondAlternative() {
    ScriptedParser a = scripted("a",
        fail(2, "a-fail"), fail(2, "a-fail"));
    ScriptedParser b = scripted("b",
        succeed(2, 5, "BBB"), succeed(2, 5, "BBB"));
    Parser parser = a.or(b);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abcdef", 2, 5);
  }

  @Test
  public void choiceAllAlternativesFailAtEndOfInput() {
    ScriptedParser a = scripted("a", fail(3, "a"), fail(3, "a"));
    ScriptedParser b = scripted("b", fail(3, "b"), fail(3, "b"));
    Parser parser = a.or(b);
    FastPathContract.assertAgree(parser, scripted("probe"), "abc", 3);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(3, "a"), fail(3, "b")), "abc", 3, 3, "b");
  }

  // ---------------------------------------------------------------- optional

  @Test
  public void optionalConsumesOnSuccess() {
    ScriptedParser a = scripted("a",
        succeed(1, 3, 'A'), succeed(1, 3, 'A'));
    Parser parser = a.optional("none");
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abcde", 1, 3);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        succeed(1, 3, 'A')), "abcde", 1, 'A', 3);
  }

  @Test
  public void optionalZeroWidthFailureKeepsPosition() {
    ScriptedParser a = scripted("a", fail(2, "x"), fail(2, "x"));
    Parser parser = a.optional("none");
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abcde", 2, 2);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        fail(2, "x")), "abcde", 2, "none", 2);
  }

  // ------------------------------------------------------------- lookaheads

  @Test
  public void andLookaheadSucceedsZeroWidth() {
    ScriptedParser a = scripted("a",
        succeed(2, 5, "BBB"), succeed(2, 5, "BBB"));
    Parser parser = a.and();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abcdef", 2, 2);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        succeed(2, 5, "BBB")), "abcdef", 2, "BBB", 2);
  }

  @Test
  public void andLookaheadPropagatesFailure() {
    ScriptedParser a = scripted("a",
        fail(1, "and-boom"), fail(1, "and-boom"));
    Parser parser = a.and();
    FastPathContract.assertAgree(parser, scripted("probe"), "abc", 1);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(1, "and-boom")), "abc", 1, 1, "and-boom");
  }

  @Test
  public void notLookaheadSucceedsZeroWidthOnChildFailure() {
    ScriptedParser a = scripted("a", fail(0, "x"), fail(0, "x"));
    Parser parser = a.not("unexpected");
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "", 0, 0);
  }

  @Test
  public void notLookaheadFailsWhenChildSucceeds() {
    ScriptedParser a = scripted("a",
        succeed(0, 2, 'A'), succeed(0, 2, 'A'));
    Parser parser = a.not("unexpected");
    FastPathContract.assertAgree(parser, scripted("probe"), "ab", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        succeed(0, 2, 'A')), "ab", 0, 0, "unexpected");
  }
}
