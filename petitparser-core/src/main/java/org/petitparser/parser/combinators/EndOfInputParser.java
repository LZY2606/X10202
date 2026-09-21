package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

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
    return isEndOfInput(context.getBuffer(), context.getPosition()) ?
        context.success(null) : context.failure(message);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return isEndOfInput(buffer, position) ? position : -1;
  }

  /**
   * Shared boundary condition of both parse modes: the end of input is
   * reached when the {@code position} is at or past the end of the {@code
   * buffer}.
   */
  private boolean isEndOfInput(String buffer, int position) {
    return position >= buffer.length();
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
