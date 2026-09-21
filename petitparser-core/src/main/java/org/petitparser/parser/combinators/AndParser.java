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
    return Transitions.and(context, delegate.parseOn(context));
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return Transitions.and(position,
        delegate.fastParseOn(buffer, position));
  }

  @Override
  public AndParser copy() {
    return new AndParser(delegate);
  }
}
