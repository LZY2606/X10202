package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

/**
 * The and-predicate, a parser that succeeds whenever its delegate does, but
 * does not consume the input stream [Parr 1994, 1995].
 */
public class AndParser extends DelegateParser {

  public AndParser(Parser delegate) {
    super(delegate);
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
   * Shared transition: succeed exactly when the delegate succeeds, but never
   * consume input and report the delegate value; propagate its failure.
   */
  private void transition(ParseMode mode) {
    int mark = mode.position();
    if (mode.accept(delegate)) {
      mode.reset(mark);
    }
  }

  @Override
  public AndParser copy() {
    return new AndParser(delegate);
  }
}
