package io.github.jinganix.peashooter;

@FunctionalInterface
public interface ThrowingRunnable {

  void run() throws Exception;
}
