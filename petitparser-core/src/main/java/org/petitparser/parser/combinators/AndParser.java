package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

/**
 * The and-predicate, a parser that succeeds whenever its delegate does, but
 * does not consume the input stream [Parr 1994, 1995].
 */
public class AndParser extends PredicateParser {

  public AndParser(Parser delegate) {
    super(delegate);
  }

  @Override
  boolean succeedsWhenDelegate(boolean delegateSucceeds) {
    return delegateSucceeds;
  }

  @Override
  Result successResult(Context context, Result result) {
    return context.success(result.get());
  }

  @Override
  public AndParser copy() {
    return new AndParser(delegate);
  }
}
