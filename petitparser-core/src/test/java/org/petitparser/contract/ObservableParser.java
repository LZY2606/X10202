package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A parser wrapper that records every invocation of both entry points.
 *
 * <p>It delegates to the wrapped parser without changing its result, so it can
 * be used to observe the sequence of positions at which a parent combinator
 * invokes its children on either path, and which path (full {@code parseOn}
 * or allocation free {@code fastParseOn}) is taken.
 */
public final class ObservableParser extends Parser {

  /** An entry of the invocation trace of an {@link ObservableParser}. */
  public static final class Trace {

    private final String mode;
    private final int position;

    Trace(String mode, int position) {
      this.mode = mode;
      this.position = position;
    }

    public String getMode() {
      return mode;
    }

    public int getPosition() {
      return position;
    }

    @Override
    public String toString() {
      return mode + "@" + position;
    }
  }

  private final Parser delegate;
  private final List<Trace> traces = new ArrayList<>();

  public ObservableParser(Parser delegate) {
    this.delegate = delegate;
  }

  @Override
  public Result parseOn(Context context) {
    traces.add(new Trace("parseOn", context.getPosition()));
    return delegate.parseOn(context);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    traces.add(new Trace("fastParseOn", position));
    return delegate.fastParseOn(buffer, position);
  }

  /** Resets the recorded invocation trace. */
  public void reset() {
    traces.clear();
  }

  /** Returns the positions the delegate was invoked at, in invocation order. */
  public List<Integer> positions() {
    List<Integer> result = new ArrayList<>();
    for (Trace trace : traces) {
      result.add(trace.position);
    }
    return result;
  }

  /** Returns the modes (parseOn/fastParseOn) of the invocations, in order. */
  public List<String> modes() {
    List<String> result = new ArrayList<>();
    for (Trace trace : traces) {
      result.add(trace.mode);
    }
    return result;
  }

  /** Returns the raw trace. */
  public List<Trace> traces() {
    return new ArrayList<>(traces);
  }

  @Override
  public Parser copy() {
    return new ObservableParser(delegate);
  }
}
