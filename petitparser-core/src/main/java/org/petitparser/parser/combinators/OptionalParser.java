package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.Objects;

/**
 * A parser that optionally parsers its delegate, or answers nil.
 */
public class OptionalParser extends PredicateParser {

  protected final Object otherwise;

  public OptionalParser(Parser delegate, /*@Nullable*/ Object otherwise) {
    super(delegate);
    this.otherwise = otherwise;
  }

  @Override
  boolean succeedsWhenDelegate(boolean delegateSucceeds) {
    return true;
  }

  @Override
  int nextPosition(int position, int outcome, boolean delegateSucceeds) {
    return delegateSucceeds ? outcome : position;
  }

  @Override
  Result successResult(Context context, Result result) {
    return result.isSuccess() ? result : context.success(otherwise);
  }

  @Override
  protected boolean hasEqualProperties(Parser other) {
    return super.hasEqualProperties(other) &&
        Objects.equals(otherwise, ((OptionalParser) other).otherwise);
  }

  @Override
  public OptionalParser copy() {
    return new OptionalParser(delegate, otherwise);
  }
}
