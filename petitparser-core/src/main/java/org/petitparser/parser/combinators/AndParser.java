package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

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
    TransitionHandler.Collecting handler = new TransitionHandler.Collecting();
    int position = transition(context.getBuffer(), context.getPosition(),
        handler);
    return position < 0 ? handler.last : context.success(handler.last.get());
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transition(buffer, position, TransitionHandler.FAST);
  }

  private int transition(String buffer, int position,
      TransitionHandler handler) {
    int result = handler.move(delegate, buffer, position);
    return result < 0 ? -1 : position;
  }

  @Override
  public AndParser copy() {
    return new AndParser(delegate);
  }
}
