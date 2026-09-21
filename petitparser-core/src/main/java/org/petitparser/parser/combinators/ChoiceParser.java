package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;
import org.petitparser.utils.FailureJoiner;

import java.util.Arrays;

/**
 * A parser that uses the first parser that succeeds.
 */
public class ChoiceParser extends ListParser {

  protected final FailureJoiner failureJoiner;

  public ChoiceParser(Parser... parsers) {
    this(new FailureJoiner.SelectLast(), parsers);
  }

  public ChoiceParser(FailureJoiner failureJoiner, Parser... parsers) {
    super(parsers);
    this.failureJoiner = failureJoiner;
    if (parsers.length == 0) {
      throw new IllegalArgumentException("Choice parser cannot be empty.");
    }
  }

  @Override
  public Result parseOn(Context context) {
    ResultMode mode = new ResultMode(context);
    transition(mode);
    return mode.toResult();
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    PositionMode mode = new PositionMode(buffer, position);
    transition(mode);
    return mode.result();
  }

  /**
   * Shared transition: alternatives are tried in order at the very same
   * position; the first success ends the transition, otherwise all failures
   * are joined.
   */
  private void transition(ParseMode mode) {
    for (Parser parser : parsers) {
      if (mode.accept(parser)) {
        return;
      }
      mode.joinFailure(failureJoiner);
    }
    mode.failJoined();
  }

  @Override
  public ChoiceParser or(FailureJoiner failureJoiner, Parser... others) {
    Parser[] array = Arrays.copyOf(parsers, parsers.length + others.length);
    System.arraycopy(others, 0, array, parsers.length, others.length);
    return new ChoiceParser(failureJoiner, array);
  }

  @Override
  public ChoiceParser copy() {
    return new ChoiceParser(failureJoiner, Arrays.copyOf(parsers,
        parsers.length));
  }
}
