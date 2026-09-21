package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

/**
 * Internal, package-private template for delegate parsers that decide success
 * or failure from a single delegate transition.
 *
 * <p>This class is the single semantic definition shared by
 * {@link OptionalParser}, {@link AndParser} and {@link NotParser}. Both
 * evaluation paths ask the same predicate
 * ({@link #succeedsWhenDelegate(boolean)}) about the delegate's outcome:
 * the allocation-free hot path evaluates it directly on the integer
 * transition code returned by {@link Parser#fastParseOn(String, int)} (a
 * non-negative next position or {@code -1} on failure) and computes the next
 * position with {@link #nextPosition(int, int, boolean)}; the slow path asks
 * the identical predicate and only then constructs a {@link Result}. No tagged
 * wrapper object is ever allocated to carry the decision.
 */
abstract class PredicateParser extends DelegateParser {

  PredicateParser(Parser delegate) {
    super(delegate);
  }

  @Override
  public final int fastParseOn(String buffer, int position) {
    int outcome = delegate.fastParseOn(buffer, position);
    boolean delegateSucceeds = outcome >= 0;
    if (succeedsWhenDelegate(delegateSucceeds)) {
      return nextPosition(position, outcome, delegateSucceeds);
    }
    return -1;
  }

  @Override
  public final Result parseOn(Context context) {
    Result result = delegate.parseOn(context);
    if (succeedsWhenDelegate(result.isSuccess())) {
      return successResult(context, result);
    }
    return result.isFailure() ? result : context.failure(failureMessage());
  }

  /**
   * The one shared transition rule: whether this parser succeeds given the
   * delegate's success flag.
   */
  abstract boolean succeedsWhenDelegate(boolean delegateSucceeds);

  /**
   * Position reported on fast-path success. {@code outcome} is the delegate's
   * next position ({@code -1} when it failed); {@code delegateSucceeds} is its
   * success flag.
   */
  int nextPosition(int position, int outcome, boolean delegateSucceeds) {
    return position;
  }

  /**
   * Result constructed on slow-path success. Implementations either adopt the
   * delegate result or re-wrap its value at the entry position.
   */
  abstract Result successResult(Context context, Result result);

  /**
   * Message of a failure produced by this parser itself when its delegate
   * unexpectedly succeeds (used by the not-predicate).
   */
  String failureMessage() {
    return null;
  }
}
