package io.github.jinganix.peashooter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LockableTaskQueueProvider implements TaskQueueProvider {

  private final Map<String, TaskQueue> queues = new ConcurrentHashMap<>();

  @Override
  public void remove(String key) {
    this.queues.remove(key);
  }

  @Override
  public TaskQueue get(String key) {
    return queues.computeIfAbsent(
        key,
        x ->
            new LockableTaskQueue() {
              @Override
              protected boolean tryLock(int index) {
                return true;
              }

              @Override
              protected boolean shouldYield(int index) {
                return false;
              }

              @Override
              protected void unlock() {}
            });
  }
}
