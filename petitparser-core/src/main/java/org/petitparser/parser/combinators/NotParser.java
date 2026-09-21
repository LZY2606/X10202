package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

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
   * Shared transition: succeed (without consuming) when the delegate fails;
   * fail at the original position with {@code message} when it succeeds.
   */
  private void transition(ParseMode mode) {
    int mark = mode.position();
    if (mode.accept(delegate)) {
      mode.reset(mark);
      mode.fail(message);
    } else {
      mode.succeedValue(null);
    }
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
