package io.github.jinganix.peashooter.redisson.setup;

import io.github.jinganix.peashooter.ExecutionStats;
import io.github.jinganix.peashooter.queue.ExecutionCountStats;
import io.github.jinganix.peashooter.queue.LockableTaskQueue;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;

public class RedisLockableTaskQueue extends LockableTaskQueue {

  private final RLock lock;

  public RedisLockableTaskQueue(String lockName) {
    super(new ExecutionCountStats());
    this.lock = RedisClient.client.getFairLock(lockName);
  }

  @Override
  protected boolean tryLock(ExecutionStats stats) {
    try {
      return lock.tryLock(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      return false;
    }
  }

  @Override
  protected boolean shouldYield(ExecutionStats stats) {
    int executionCount = ((ExecutionCountStats) stats).getExecutionCount();
    return executionCount > 0 && executionCount % 5 == 0;
  }

  @Override
  protected void unlock() {
    lock.forceUnlock();
  }
}
