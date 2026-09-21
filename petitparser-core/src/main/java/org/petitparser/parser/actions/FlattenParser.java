package org.petitparser.parser.actions;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

/**
 * A parser that answers a flat copy of the range my delegate parses.
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
    // Recognition is delegated to the shared fast path whenever a custom
    // message is reported; otherwise the delegate's own success/failure
    // (including its precise failure position and message) is preserved and
    // only the value on success is replaced by the consumed substring.
    if (message == null) {
      Result result = delegate.parseOn(context);
      if (result.isSuccess()) {
        String flattened = context.getBuffer()
            .substring(context.getPosition(), result.getPosition());
        return result.success(flattened);
      }
      return result;
    }
    int position = fastParseOn(context.getBuffer(), context.getPosition());
    if (position < 0) {
      return context.failure(message);
    }
    String output =
        context.getBuffer().substring(context.getPosition(), position);
    return context.success(output, position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Flattening only rewrites the value, so recognition is the delegate's
    // recognition on both entry points. This stays on the allocation-free
    // recognition path instead of building a Result just to discard it.
    return delegate.fastParseOn(buffer, position);
  }

  @Override
  public FlattenParser copy() {
    return new FlattenParser(delegate, message);
  }
}
