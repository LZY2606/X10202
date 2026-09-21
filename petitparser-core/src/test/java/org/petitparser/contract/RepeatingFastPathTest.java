package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;

import java.util.Arrays;

import static org.petitparser.contract.ScriptedParser.Reaction.fail;
import static org.petitparser.contract.ScriptedParser.Reaction.succeed;

/**
 * Characterization contract for possessive, greedy and lazy repeating
 * parsers, covering empty input, mid-input starts, exact boundary success, a
 * child that first consumes and then fails, and zero-width repetitions.
 */
public class RepeatingFastPathTest {

  private static ScriptedParser scripted(String name,
      ScriptedParser.Reaction... reactions) {
    return new ScriptedParser(name, reactions);
  }

  // ------------------------------------------------------------ possessive

  @Test
  public void possessiveStarEmptyInputZeroRepetitions() {
    ScriptedParser child = scripted("c", fail(0, "stop"), fail(0, "stop"));
    Parser parser = child.star();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "", 0, 0);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        fail(0, "stop")), "", 0, Arrays.asList(), 0);
  }

  @Test
  public void possessivePlusGreedyConsumptionFromMiddle() {
    ScriptedParser child = scripted("c",
        succeed(1, 2, 'a'), succeed(2, 3, 'b'), fail(3, "end"),
        succeed(1, 2, 'a'), succeed(2, 3, 'b'), fail(3, "end"));
    Parser parser = child.plus();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "xabx", 1, 3);
  }

  @Test
  public void possessiveExactBoundarySuccess() {
    ScriptedParser child = scripted("c",
        succeed(0, 1, 'a'), succeed(1, 2, 'b'),
        succeed(0, 1, 'a'), succeed(1, 2, 'b'));
    Parser parser = child.times(2);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "ab", 0, 2);
  }

  @Test
  public void possessiveMinNotReachedFails() {
    // Child consumes once, then fails before the minimum of 2 is reached.
    ScriptedParser child = scripted("c",
        succeed(0, 1, 'a'), fail(1, "need-more"),
        succeed(0, 1, 'a'), fail(1, "need-more"));
    Parser parser = child.times(2);
    FastPathContract.assertAgree(parser, scripted("probe"), "ax", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        succeed(0, 1, 'a'), fail(1, "need-more")), "ax", 0, 1, "need-more");
  }

  @Test
  public void possessiveZeroWidthSuccessStillAdvancesLoop() {
    // A zero-width child inside star would loop forever in real parsers; the
    // scripted parser lets us pin the single-iteration transition semantics.
    ScriptedParser child = scripted("c",
        succeed(0, 'z'), fail(0, "stop"),
        succeed(0, 'z'), fail(0, "stop"));
    Parser parser = child.star();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "", 0, 0);
  }

  // ---------------------------------------------------------------- greedy

  @Test
  public void greedyConsumesThenBacktracksToLimit() {
    // delegate consumes 0->1->2, then fails; limit fails at 2, succeeds at 1,
    // so the parser must backtrack one consumed repetition.
    ScriptedParser del = scripted("d",
        succeed(0, 1, 'a'), succeed(1, 2, 'b'), fail(2, "no-more"),
        succeed(0, 1, 'a'), succeed(1, 2, 'b'), fail(2, "no-more"));
    ScriptedParser lim = scripted("l",
        fail(2, "lim"), succeed(1, "lim-ok"),
        fail(2, "lim"), succeed(1, "lim-ok"));
    Parser parser = del.starGreedy(lim);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abX", 0, 1);
  }

  @Test
  public void greedyExactBoundaryLimitSucceedsAtStart() {
    ScriptedParser del = scripted("d",
        fail(0, "stop"), fail(0, "stop"));
    ScriptedParser lim = scripted("l",
        succeed(0, "ok"), succeed(0, "ok"));
    Parser parser = del.starGreedy(lim);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "", 0, 0);
  }

  @Test
  public void greedyMinNotReachedFails() {
    ScriptedParser del = scripted("d",
        fail(1, "d-fail"), fail(1, "d-fail"));
    ScriptedParser lim = scripted("l",
        succeed(1, "ok"), succeed(1, "ok"));
    Parser parser = del.plusGreedy(lim);
    FastPathContract.assertAgree(parser, scripted("probe"), "ab", 1);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(1, "d-fail"), succeed(1, "ok")), "ab", 1, 1, "d-fail");
  }

  @Test
  public void greedyLimitNeverMatchesReportsLimitFailure() {
    // All consumed positions are popped; the final (empty) limiter failure is
    // surfaced by the slow path and -1 by the fast path.
    ScriptedParser del = scripted("d",
        succeed(0, 1, 'a'), fail(1, "no-more"),
        succeed(0, 1, 'a'), fail(1, "no-more"));
    ScriptedParser lim = scripted("l",
        fail(1, "lim"), fail(0, "lim"),
        fail(1, "lim"), fail(0, "lim"));
    Parser parser = del.starGreedy(lim);
    FastPathContract.assertAgree(parser, scripted("probe"), "ax", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        succeed(0, 1, 'a'), fail(1, "no-more"),
        fail(1, "lim"), fail(0, "lim")), "ax", 0, 0, "lim");
  }

  // ------------------------------------------------------------------ lazy

  @Test
  public void lazyStopsAtEarliestLimit() {
    ScriptedParser del = scripted("d",
        succeed(0, 1, 'a'),
        succeed(0, 1, 'a'));
    ScriptedParser lim = scripted("l",
        fail(0, "lim"), succeed(1, "lim-ok"),
        fail(0, "lim"), succeed(1, "lim-ok"));
    Parser parser = del.starLazy(lim);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "aX", 0, 1);
  }

  @Test
  public void lazyLimitSucceedsImmediatelyZeroWidth() {
    ScriptedParser del = scripted("d",
        fail(0, "never"), fail(0, "never"));
    ScriptedParser lim = scripted("l",
        succeed(0, "ok"), succeed(0, "ok"));
    Parser parser = del.starLazy(lim);
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "", 0, 0);
  }

  @Test
  public void lazyMinNotReachedFails() {
    ScriptedParser del = scripted("d",
        fail(0, "d-fail"), fail(0, "d-fail"));
    ScriptedParser lim = scripted("l",
        fail(0, "lim"), fail(0, "lim"));
    Parser parser = del.plusLazy(lim);
    FastPathContract.assertAgree(parser, scripted("probe"), "", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(0, "d-fail"), fail(0, "lim")), "", 0, 0, "d-fail");
  }

  @Test
  public void lazyMaxReachedReportsLimitFailure() {
    // With max=1: one delegate succeeds, then at count == max the failing
    // limit terminates the parse without invoking the delegate again.
    ScriptedParser del = scripted("d",
        succeed(0, 1, 'a'), succeed(0, 1, 'a'));
    ScriptedParser lim = scripted("l",
        fail(0, "lim"), fail(1, "lim-end"),
        fail(0, "lim"), fail(1, "lim-end"));
    Parser parser = del.repeatLazy(lim, 0, 1);
    FastPathContract.assertAgree(parser, scripted("probe"), "ab", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(0, "lim"), succeed(0, 1, 'a'), fail(1, "lim-end")),
        "ab", 0, 1, "lim-end");
  }
}
