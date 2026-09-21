package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

import java.util.Objects;

/**
 * The not-predicate, a parser that succeeds whenever its delegate does not, but
 * consumes no input [Parr 1994, 1995].
 */
public class NotParser extends DelegateParser {

  protected final String message;

  public NotParser(Parser delegate, String message) {
    super(delegate);
    this.message = Objects.requireNonNull(message, "Undefined message");
  }

  @Override
  public Result parseOn(Context context) {
    TransitionHandler.Collecting handler = new TransitionHandler.Collecting();
    int position = transition(context.getBuffer(), context.getPosition(),
        handler);
    return position < 0 ? context.failure(message) : context.success(null);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transition(buffer, position, TransitionHandler.FAST);
  }

  private int transition(String buffer, int position,
      TransitionHandler handler) {
    return handler.move(delegate, buffer, position) < 0 ? position : -1;
  }

  @Override
  protected boolean hasEqualProperties(Parser other) {
    return super.hasEqualProperties(other) &&
        Objects.equals(message, ((NotParser) other).message);
  }

  @Override
  public NotParser copy() {
    return new NotParser(delegate, message);
  }

  @Override
  public String toString() {
    return super.toString() + "[" + message + "]";
  }
}
