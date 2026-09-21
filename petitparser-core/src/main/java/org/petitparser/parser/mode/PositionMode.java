package org.petitparser.parser.mode;

import org.petitparser.parser.Parser;
import org.petitparser.utils.FailureJoiner;

import java.util.function.Function;

/**
 * Fast, allocation free mode: tracks only the current position and whether
 * the transition has failed. All value, token, substring, action and failure
 * joining operations are no-ops, so a successful transition creates no
 * objects beyond what the child parsers allocate themselves.
 */
public final class PositionMode extends ParseMode {

  private boolean failed;

  public PositionMode(String buffer, int position) {
    super(buffer, position);
  }

  @Override
  public boolean accept(Parser parser) {
    int result = parser.fastParseOn(buffer, position);
    if (result < 0) {
      failed = true;
      return false;
    }
    position = result;
    failed = false;
    return true;
  }

  @Override
  public boolean acceptFast(Parser parser) {
    int result = parser.fastParseOn(buffer, position);
    if (result < 0) {
      return false;
    }
    position = result;
    return true;
  }

  @Override
  public void push() {
  }

  @Override
  public void pop() {
  }

  @Override
  public void succeedValue(Object value) {
    failed = false;
  }

  @Override
  public void succeedList() {
    failed = false;
  }

  @Override
  public void succeedToken(int start) {
    failed = false;
  }

  @Override
  public void succeedFlatten(int start) {
    failed = false;
  }

  @Override
  public <T, R> R apply(Function<T, R> function) {
    return null;
  }

  @Override
  public Object failure() {
    return null;
  }

  @Override
  public void fail(String message) {
    failed = true;
  }

  @Override
  public void failWith(Object failure) {
    failed = true;
  }

  @Override
  public void joinFailure(FailureJoiner joiner) {
  }

  @Override
  public void failJoined() {
    failed = true;
  }

  /** Returns the terminal position of the transition, or {@code -1}. */
  public int result() {
    return failed ? -1 : position;
  }
}
