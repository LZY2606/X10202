package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

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
    Result[] sink = new Result[1];
    int position =
        transition(delegate, context.getBuffer(), context.getPosition(), sink);
    return position < 0 ? sink[0] : context.success(sink[0].get());
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    int result = transition(delegate, buffer, position, null);
    return result < 0 ? -1 : position;
  }

  @Override
  public AndParser copy() {
    return new AndParser(delegate);
  }
}
