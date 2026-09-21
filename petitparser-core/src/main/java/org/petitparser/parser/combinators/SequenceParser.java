package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

import java.util.Arrays;

/**
 * A parser that parses a sequence of parsers.
 */
public class SequenceParser extends ListParser {

  public SequenceParser(Parser... parsers) {
    super(parsers);
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
   * Shared transition of {@link #parseOn} and {@link #fastParseOn}: every
   * child must succeed at the position reached by its predecessor; the first
   * failure ends the transition and the collected child values form the
   * result.
   */
  private void transition(ParseMode mode) {
    for (Parser parser : parsers) {
      if (!mode.accept(parser)) {
        return;
      }
      mode.push();
    }
    mode.succeedList();
  }

  @Override
  public SequenceParser seq(Parser... others) {
    Parser[] array = Arrays.copyOf(parsers, parsers.length + others.length);
    System.arraycopy(others, 0, array, parsers.length, others.length);
    return new SequenceParser(array);
  }

  @Override
  public SequenceParser copy() {
    return new SequenceParser(Arrays.copyOf(parsers, parsers.length));
  }
}
