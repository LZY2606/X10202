package org.petitparser.parser;

import org.petitparser.context.Context;
import org.petitparser.context.Result;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal strategy that defines how a combinator advances over its children
 * during a parse transition.
 *
 * <p>Combinators that support both {@link Parser#parseOn(Context)} and {@link
 * Parser#fastParseOn(String, int)} express their control flow (iteration
 * order, position threading, success and failure decisions, backtracking) a
 * single time against this interface. The two public entry points then only
 * differ in the handler they supply:
 *
 * <ul>
 * <li>{@link #FAST} is a stateless singleton that only advances positions and
 * never allocates. It backs the allocation free hot path.</li>
 * <li>{@link Collecting} performs full child parses and records the resulting
 * values and failures, so that the caller can assemble the same {@link Result}
 * objects the combinator has always produced.</li>
 * </ul>
 *
 * <p>The contract both handlers satisfy, and thus the contract between the two
 * parse paths, is: a transition succeeds at exactly the positions where the
 * full parse succeeds, and fails exactly where the full parse fails.
 */
public abstract class TransitionHandler {

  /**
   * Advances over {@code parser} at {@code position} in {@code buffer}.
   *
   * <p>Returns the position right after the child on success, or {@code -1}
   * if the child failed. This is the only place where child parsers are
   * invoked, which fixes the invocation order for both parse paths.
   */
  public abstract int move(Parser parser, String buffer, int position);

  /**
   * Commits the result of the last successful {@link #move} as a produced
   * value of the transition. Called by combinators that build list results.
   */
  public void push() {
    // The fast path does not produce values.
  }

  /**
   * Reverts the most recent {@link #push}, used when backtracking.
   */
  public void pop() {
    // The fast path does not produce values.
  }

  /**
   * The stateless and allocation free handler of the fast parse path.
   */
  public static final TransitionHandler FAST = new TransitionHandler() {
    @Override
    public int move(Parser parser, String buffer, int position) {
      return parser.fastParseOn(buffer, position);
    }
  };

  /**
   * The handler of the full parse path. Runs {@link Parser#parseOn(Context)}
   * on the children and records the last result as well as all committed
   * values, so that combinators can construct their results and failures
   * exactly as before.
   */
  public static class Collecting extends TransitionHandler {

    /**
     * The result of the most recent {@link #move}, success or failure.
     */
    public Result last;

    /**
     * The values committed through {@link #push()}, in order.
     */
    public final List<Object> values = new ArrayList<>();

    @Override
    public int move(Parser parser, String buffer, int position) {
      last = parser.parseOn(new Context(buffer, position));
      return last.isSuccess() ? last.getPosition() : -1;
    }

    @Override
    public void push() {
      values.add(last.get());
    }

    @Override
    public void pop() {
      values.remove(values.size() - 1);
    }
  }
}
