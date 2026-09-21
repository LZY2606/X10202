package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

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
    TransitionHandler.Collecting handler = new TransitionHandler.Collecting();
    transition(context.getBuffer(), context.getPosition(), handler);
    return handler.last.isSuccess() ? handler.last :
        context.success(otherwise);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transition(buffer, position, TransitionHandler.FAST);
  }

  private int transition(String buffer, int position,
      TransitionHandler handler) {
    int result = handler.move(delegate, buffer, position);
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
