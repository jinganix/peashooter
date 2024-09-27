/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package io.github.jinganix.peashooter.redisson.setup;

public class TestItem {

  private final String task;

  private final int index;

  private long millis;

  public TestItem(int task, int index) {
    this.task = "task_" + task;
    this.index = index;
  }

  public String getTask() {
    return task;
  }

  public int getIndex() {
    return index;
  }

  public long getMillis() {
    return millis;
  }

  public TestItem setMillis(long millis) {
    this.millis = millis;
    return this;
  }

  @Override
  public String toString() {
    return "{ task: " + task + ", index: " + index + ", millis: " + millis + " }";
  }
}
