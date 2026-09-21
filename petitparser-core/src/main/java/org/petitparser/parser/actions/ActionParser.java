package org.petitparser.parser.actions;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.Objects;
import java.util.function.Function;

/**
 * A parser that performs a transformation with a given function on the
 * successful parse result of the delegate.
 *
 * @param <T> The type of the function argument.
 * @param <R> The type of the function result.
 */
public class ActionParser<T, R> extends DelegateParser {

  protected final Function<T, R> function;
  protected final boolean hasSideEffects;

  public ActionParser(
      Parser delegate, Function<T, R> function) {
    this(delegate, function, false);
  }

  public ActionParser(
      Parser delegate, Function<T, R> function, boolean hasSideEffects) {
    super(delegate);
    this.function = Objects.requireNonNull(function, "Undefined function");
    this.hasSideEffects = hasSideEffects;
  }

  @Override
  public Result parseOn(Context context) {
    Result result = delegate.parseOn(context);
    if (result.isSuccess()) {
      // The slow path always applies the action, both for its value and for
      // its (possible) side effects.
      return result.success(applyAction(result.get()));
    } else {
      return result;
    }
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Whether the action may run on the fast path is defined once by
    // runsOnFastPath() below. A side-effecting action must keep executing even
    // when only success/position is queried, so the fast path falls back to
    // the slow (allocating) mode; a pure action is skipped and only the
    // delegate's transition is evaluated, allocating nothing.
    return runsOnFastPath()
        ? delegate.fastParseOn(buffer, position)
        : super.fastParseOn(buffer, position);
  }

  /**
   * Single decision for whether the action itself is evaluated on the
   * allocation-free fast path. Pure actions are elided; actions declared with
   * side effects force the slow path so that the effect is not lost.
   */
  boolean runsOnFastPath() {
    return !hasSideEffects;
  }

  @SuppressWarnings("unchecked")
  private R applyAction(Object value) {
    return function.apply((T) value);
  }

  @Override
  protected boolean hasEqualProperties(Parser other) {
    return super.hasEqualProperties(other) &&
        Objects.equals(function, ((ActionParser<T, R>) other).function) &&
        hasSideEffects == ((ActionParser<T, R>) other).hasSideEffects;
  }

  @Override
  public ActionParser<T, R> copy() {
    return new ActionParser<>(delegate, function, hasSideEffects);
  }
}
