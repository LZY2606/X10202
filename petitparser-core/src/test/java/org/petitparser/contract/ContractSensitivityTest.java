package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.SequenceParser;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.petitparser.contract.ObservableParser.Answer;
import static org.petitparser.contract.ObservableParser.fail;
import static org.petitparser.contract.ObservableParser.scripted;
import static org.petitparser.contract.ObservableParser.succeed;

/**
 * Negative tests proving the executable contract cannot be satisfied by an
 * implementation that changes the position calculation or the child
 * activation order on only one of the two paths.
 *
 * <p>Each case injects a deliberately diverging primitive parser: the
 * characterization probe must flag the disagreement rather than silently pass.
 */
public class ContractSensitivityTest {

  private final ObservableParser.EventLog log = new ObservableParser.EventLog();
  private final String input = "abcdefgh";

  private Answer[] table() {
    return new Answer[input.length() + 1];
  }

  /** A parser whose fast path lies about the resulting position. */
  private static final class FastPositionLiar extends Parser {
    private final Parser delegate;

    private FastPositionLiar(Parser delegate) {
      this.delegate = delegate;
    }

    @Override
    public Result parseOn(Context context) {
      return delegate.parseOn(context);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      int result = delegate.fastParseOn(buffer, position);
      return result < 0 ? -1 : result + 1;
    }

    @Override
    public Parser copy() {
      return new FastPositionLiar(delegate);
    }
  }

  /** A parser whose fast path disagrees on success vs. failure. */
  private static final class FastOutcomeLiar extends Parser {
    private final Parser delegate;

    private FastOutcomeLiar(Parser delegate) {
      this.delegate = delegate;
    }

    @Override
    public Result parseOn(Context context) {
      return delegate.parseOn(context);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      return -1;
    }

    @Override
    public Parser copy() {
      return new FastOutcomeLiar(delegate);
    }
  }

  @Test
  public void detectsPositionDriftOnFastPath() {
    Answer[] a = table();
    a[0] = succeed(2, 'x');
    Parser honest = scripted("a", input.length(), log.events(), a);
    Parser parser = new FastPositionLiar(honest);
    try {
      ContractProbe.run(parser, log, input, 0);
      fail("expected the contract to detect fast-path position drift");
    } catch (AssertionError expected) {
      assertTrue(expected.getMessage().contains("final position"));
    }
  }

  @Test
  public void detectsOutcomeDriftOnFastPath() {
    Answer[] a = table();
    a[0] = succeed(2, 'x');
    Parser parser = new FastOutcomeLiar(
        scripted("a", input.length(), log.events(), a));
    try {
      ContractProbe.run(parser, log, input, 0);
      fail("expected the contract to detect fast-path outcome drift");
    } catch (AssertionError expected) {
      assertTrue(expected.getMessage().contains("success"));
    }
  }

  /**
   * A sequence child whose fast path activates a different child order cannot
   * happen at the primitive level, but a combinator whose fast loop skips a
   * child (while the slow loop visits it) is caught by the transition order
   * comparison: build a delegate parser that on the fast path does not consult
   * its delegate.
   */
  private static final class FastSkipsDelegate extends Parser {
    private final Parser delegate;

    private FastSkipsDelegate(Parser delegate) {
      this.delegate = delegate;
    }

    @Override
    public Result parseOn(Context context) {
      return delegate.parseOn(context);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      return position;
    }

    @Override
    public Parser copy() {
      return new FastSkipsDelegate(delegate);
    }
  }

  @Test
  public void detectsSkippedChildActivationOnFastPath() {
    Answer[] a = table();
    a[0] = fail(0, "a-fails");
    Parser observable = scripted("a", input.length(), log.events(), a);
    // Slow path visits the failing observable, fast path short-circuits it.
    Parser parser = new SequenceParser(new FastSkipsDelegate(observable));
    try {
      ContractProbe.run(parser, log, input, 0);
      fail("expected the contract to detect a skipped child on fast path");
    } catch (AssertionError expected) {
      assertTrue(expected.getMessage(),
          expected.getMessage().contains("success")
              || expected.getMessage().contains("activation order"));
    }
  }

  @Test
  public void contractPassesWhenPathsAgree() {
    Answer[] a = table();
    a[0] = succeed(2, 'x');
    Parser parser = scripted("a", input.length(), log.events(), a);
    ContractProbe.run(parser, log, input, 0).assertSuccess(2);
  }
}
