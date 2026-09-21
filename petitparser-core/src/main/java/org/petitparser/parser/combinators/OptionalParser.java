package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.Objects;

/**
 * A parser that optionally parsers its delegate, or answers nil.
 */
public class OptionalParser extends DelegateParser {

  protected final Object otherwise;

  public OptionalParser(Parser delegate, /*@Nullable*/ Object otherwise) {
    super(delegate);
    this.otherwise = otherwise;
  }

  @Override
  public Result parseOn(Context context) {
    Result[] sink = new Result[1];
    int position =
        transition(delegate, context.getBuffer(), context.getPosition(), sink);
    return position < 0 ? context.success(otherwise) : sink[0];
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    int result = transition(delegate, buffer, position, null);
    return result < 0 ? position : result;
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
