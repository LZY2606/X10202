package org.petitparser.parser.actions;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A parser that silently consumes a before and after the delegate parser.
 */
public class TrimmingParser extends DelegateParser {

  private Parser left;
  private Parser right;

  public TrimmingParser(Parser delegate, Parser left, Parser right) {
    super(delegate);
    this.left = Objects.requireNonNull(left, "Undefined left trimming parser");
    this.right =
        Objects.requireNonNull(right, "Undefined right trimming parser");
  }

  @Override
  public Result parseOn(Context context) {
    String buffer = context.getBuffer();

    // Trim the left part:
    int before = consume(left, buffer, context.getPosition());
    if (before != context.getPosition()) {
      context = new Context(buffer, before);
    }

    // Consume the delegate:
    Result result = delegate.parseOn(context);
    if (result.isFailure()) {
      return result;
    }

    // Trim the right part:
    int after = consume(right, buffer, result.getPosition());
    return after == result.getPosition() ? result :
        result.success(result.get(), after);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Same three-stage transition as parseOn, expressed entirely on the
    // integer position and allocating nothing.
    position = consume(left, buffer, position);
    int result = delegate.fastParseOn(buffer, position);
    return result < 0 ? result : consume(right, buffer, result);
  }

  /**
   * Shared boundary computation: repeatedly apply {@code parser} while it
   * keeps advancing, and return the fixed point position. Used by both the
   * slow and the fast path to trim the left and right surroundings.
   */
  private int consume(Parser parser, String buffer, int position) {
    for (; ; ) {
      int result = parser.fastParseOn(buffer, position);
      if (result < 0) {
        return position;
      }
      position = result;
    }
  }

  @Override
  public void replace(Parser source, Parser target) {
    super.replace(source, target);
    if (left == source) {
      left = target;
    }
    if (right == source) {
      right = target;
    }
  }

  @Override
  public List<Parser> getChildren() {
    return Arrays.asList(delegate, left, right);
  }

  @Override
  public TrimmingParser copy() {
    return new TrimmingParser(delegate, left, right);
  }
}
