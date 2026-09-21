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
    if (message == null) {
      // Preserve the delegate's own failure (position and message).
      Result result = delegate.parseOn(context);
      if (result.isSuccess()) {
        String flattened = context.getBuffer()
            .substring(context.getPosition(), result.getPosition());
        return result.success(flattened);
      }
      return result;
    }
    // With an explicit message success and the flattened range are derived
    // directly from the allocation-free fast transition; on failure the given
    // message replaces the delegate failure.
    int end = delegateEnd(context.getBuffer(), context.getPosition());
    if (end < 0) {
      return context.failure(message);
    }
    return context.success(
        context.getBuffer().substring(context.getPosition(), end), end);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Only the consumed range matters; the delegate runs on the allocation
    // free fast transition and no Result or substring is constructed.
    return delegateEnd(buffer, position);
  }

  /**
   * Shared boundary computation: the final position reached by the delegate,
   * or {@code -1} on failure.
   */
  private int delegateEnd(String buffer, int position) {
    return delegate.fastParseOn(buffer, position);
  }

  @Override
  public FlattenParser copy() {
    return new FlattenParser(delegate, message);
  }
}
