package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

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
   * Shared transition: report the delegate success, or a zero-width success
   * carrying the {@code otherwise} value.
   */
  private void transition(ParseMode mode) {
    if (!mode.accept(delegate)) {
      mode.succeedValue(otherwise);
    }
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
