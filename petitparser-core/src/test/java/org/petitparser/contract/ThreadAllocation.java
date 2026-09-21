package org.petitparser.contract;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflective access to the JVM thread-allocation counter.
 *
 * <p>The bean lives in the {@code jdk.management}/{@code com.sun.management}
 * module, which the shipped library module does not read. Going through
 * reflection keeps the test sources compilable on the module path; Surefire is
 * configured with {@code --add-reads} so the lookup succeeds at run time.
 */
final class ThreadAllocation {

  private final Object bean;
  private final Method getThreadAllocatedBytes;

  private ThreadAllocation() {
    try {
      Class<?> managementFactory =
          Class.forName("java.lang.management.ManagementFactory");
      Method getThreadMXBean =
          managementFactory.getMethod("getThreadMXBean");
      bean = getThreadMXBean.invoke(null);
      getThreadAllocatedBytes = bean.getClass()
          .getMethod("getThreadAllocatedBytes", long.class);
      getThreadAllocatedBytes.setAccessible(true);
    } catch (ClassNotFoundException | NoSuchMethodException
        | IllegalAccessException | InvocationTargetException cause) {
      throw new IllegalStateException(cause);
    }
  }

  static ThreadAllocation create() {
    return new ThreadAllocation();
  }

  long allocatedBytes() {
    try {
      long threadId = Thread.currentThread().getId();
      return (Long) getThreadAllocatedBytes.invoke(bean, threadId);
    } catch (IllegalAccessException | InvocationTargetException cause) {
      throw new IllegalStateException(cause);
    }
  }
}
