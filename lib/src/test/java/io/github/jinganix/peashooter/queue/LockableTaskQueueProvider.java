package io.github.jinganix.peashooter.queue;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.TaskQueueProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LockableTaskQueueProvider implements TaskQueueProvider {

  private final Map<String, TaskQueue> queues = new ConcurrentHashMap<>();

  @Override
  public TaskQueue get(String key) {
    return queues.computeIfAbsent(
        key,
        x ->
            new LockableTaskQueue() {
              @Override
              protected boolean tryLock(ExecutionStats stats) {
                return true;
              }

              @Override
              protected boolean shouldYield(ExecutionStats stats) {
                return false;
              }

              @Override
              protected void unlock() {}
            });
  }
}
