package io.github.jinganix.peashooter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ExecutorForTests {

  public static Map<String, Executor> executors() {
    Map<String, Executor> executors = new LinkedHashMap<>();
    <#if java19>executors.put("VirtualThreadPerTaskExecutor", Executors.newVirtualThreadPerTaskExecutor());</#if>
    executors.put("DirectExecutor", DirectExecutor.INSTANCE);
    executors.put("SingleThreadExecutor", Executors.newSingleThreadExecutor());
    executors.put("CachedThreadPool", Executors.newCachedThreadPool());
    executors.put("FixedThreadPool(2)", Executors.newFixedThreadPool(2));
    executors.put("FixedThreadPool(10)", Executors.newFixedThreadPool(10));
    return executors;
  }
}
