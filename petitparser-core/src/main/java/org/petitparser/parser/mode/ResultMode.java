package org.petitparser.parser.mode;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.context.Success;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;
import org.petitparser.utils.FailureJoiner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Full result producing mode: builds {@link Success} or {@link Failure}
 * objects exactly like the previous {@code parseOn} implementations, keeping
 * their allocation profile (successful child results are reused as the next
 * context).
 */
public final class ResultMode extends ParseMode {

  private boolean failed;
  private Object lastValue;
  private Object resultValue;
  private Failure failure;
  private Failure joinedFailure;
  private List<Object> values;

  private Context context;
  private int contextPosition = Integer.MIN_VALUE;

  public ResultMode(Context context) {
    super(context.getBuffer(), context.getPosition());
    this.context = context;
    this.contextPosition = context.getPosition();
  }

  private Context currentContext() {
    if (context == null || contextPosition != position) {
      context = new Context(buffer, position);
      contextPosition = position;
    }
    return context;
  }

  @Override
  public boolean accept(Parser parser) {
    Result result = parser.parseOn(currentContext());
    if (result.isSuccess()) {
      position = result.getPosition();
      lastValue = result.get();
      resultValue = lastValue;
      // Reuse the returned success as the next context, avoiding an
      // additional allocation as in the original implementations.
      context = result;
      contextPosition = position;
      failed = false;
      return true;
    }
    failure = (Failure) result;
    failed = true;
    return false;
  }

  @Override
  public boolean acceptFast(Parser parser) {
    int result = parser.fastParseOn(buffer, position);
    if (result < 0) {
      return false;
    }
    position = result;
    context = null;
    contextPosition = Integer.MIN_VALUE;
    return true;
  }

  @Override
  public void push() {
    if (values == null) {
      values = new ArrayList<>();
    }
    values.add(lastValue);
  }

  @Override
  public void pop() {
    values.remove(values.size() - 1);
  }

  @Override
  public void succeedValue(Object value) {
    resultValue = value;
    failed = false;
  }

  @Override
  public void succeedList() {
    resultValue = values == null ? new ArrayList<>() : values;
    failed = false;
  }

  @Override
  public void succeedToken(int start) {
    resultValue = new Token(buffer, start, position, lastValue);
    failed = false;
  }

  @Override
  public void succeedFlatten(int start) {
    resultValue = buffer.substring(start, position);
    failed = false;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T, R> R apply(Function<T, R> function) {
    return function.apply((T) lastValue);
  }

  @Override
  public Object failure() {
    return failure;
  }

  @Override
  public void fail(String message) {
    failure = new Failure(buffer, position, message);
    failed = true;
  }

  @Override
  public void failWith(Object failureObject) {
    failure = (Failure) failureObject;
    failed = true;
  }

  @Override
  public void joinFailure(FailureJoiner joiner) {
    joinedFailure = joinedFailure == null
        ? failure
        : joiner.apply(joinedFailure, failure);
  }

  @Override
  public void failJoined() {
    failure = joinedFailure;
    failed = true;
  }

  /** Returns the terminal result of the transition. */
  public Result toResult() {
    return failed
        ? failure
        : new Success(buffer, position, resultValue);
  }
}
