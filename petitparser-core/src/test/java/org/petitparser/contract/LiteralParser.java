package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

/**
 * Minimal leaf parser used by {@link FastParseDifferentialTest}: matches one
 * fixed character and implements both entry points directly.
 */
final class LiteralParser extends Parser {

  private final char wanted;

  LiteralParser(char wanted) {
    this.wanted = wanted;
  }

  @Override
  public Result parseOn(Context context) {
    String buffer = context.getBuffer();
    int position = context.getPosition();
    if (position < buffer.length() && buffer.charAt(position) == wanted) {
      return context.success(wanted, position + 1);
    }
    return context.failure(wanted + " expected");
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return position < buffer.length() && buffer.charAt(position) == wanted
        ? position + 1 : -1;
  }

  @Override
  public Parser copy() {
    return new LiteralParser(wanted);
  }
}
