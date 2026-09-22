package org.petitparser.parser.actions;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

/**
 * A parser that answers a flat copy of the range my delegate parses.
 *
 * <p>Both entry points share the same transfer: on success the consumed range
 * is from the starting position to the delegate's resulting position. The
 * slow path additionally materializes that range as a {@link String}; with a
 * custom {@code message} a delegate failure is reported at the starting
 * position instead of the delegate's failure position. The fast path only
 * forwards the delegate's position transition and therefore allocates
 * nothing.
 */
public class FlattenParser extends DelegateParser {

  protected final String message;

  public FlattenParser(Parser delegate) {
    this(delegate, null);
  }

  public FlattenParser(Parser delegate, String message) {
    super(delegate);
    this.message = message;
  }

  @Override
  public Result parseOn(Context context) {
    if (message == null) {
      Result result = delegate.parseOn(context);
      if (result.isSuccess()) {
        String flattened = context.getBuffer()
            .substring(context.getPosition(), result.getPosition());
        return result.success(flattened);
      } else {
        return result;
      }
    } else {
      // If we have a message we can switch to fast mode.
      int position =
          delegate.fastParseOn(context.getBuffer(), context.getPosition());
      if (position < 0) {
        return context.failure(message);
      }
      String output =
          context.getBuffer().substring(context.getPosition(), position);
      return context.success(output, position);
    }
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    int result = delegate.fastParseOn(buffer, position);
    return result < 0 ? FAST_PARSE_FAILURE : result;
  }

  @Override
  public FlattenParser copy() {
    return new FlattenParser(delegate, message);
  }
}
