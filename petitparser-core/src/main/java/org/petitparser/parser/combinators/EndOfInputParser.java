package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

import java.util.Objects;

/**
 * A parser that succeeds only at the end of the input stream.
 */
public class EndOfInputParser extends Parser {

  protected final String message;

  public EndOfInputParser(String message) {
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

  /** Shared transition: succeed only at the end of the buffer. */
  private void transition(ParseMode mode) {
    if (mode.atEnd()) {
      mode.succeedValue(null);
    } else {
      mode.fail(message);
    }
  }

  @Override
  protected boolean hasEqualProperties(Parser other) {
    return super.hasEqualProperties(other) &&
        Objects.equals(message, ((EndOfInputParser) other).message);
  }

  @Override
  public EndOfInputParser copy() {
    return new EndOfInputParser(message);
  }

  @Override
  public String toString() {
    return super.toString() + "[" + message + "]";
  }
}
