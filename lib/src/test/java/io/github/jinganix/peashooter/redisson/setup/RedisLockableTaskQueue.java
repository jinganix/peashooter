package io.github.jinganix.peashooter.redisson.setup;

import io.github.jinganix.peashooter.LockableTaskQueue;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;

public class RedisLockableTaskQueue extends LockableTaskQueue {

  private final RLock lock;

  public RedisLockableTaskQueue(String lockName) {
    this.lock = RedisClient.client.getFairLock(lockName);
  }

  @Override
  protected boolean tryLock(int executedCount) {
    try {
      return lock.tryLock(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      return false;
    }
  }

  @Override
  protected boolean shouldYield(int executedCount) {
    return executedCount > 0 && executedCount % 5 == 0;
  }

  @Override
  protected void unlock() {
    lock.forceUnlock();
  }
}
