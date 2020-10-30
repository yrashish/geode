/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.logging;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Before;
import org.junit.Test;

import org.apache.geode.distributed.internal.FunctionExecutionPooledExecutor;
import org.apache.geode.distributed.internal.OverflowQueueWithDMStats;
import org.apache.geode.distributed.internal.PoolStatHelper;
import org.apache.geode.distributed.internal.QueueStatHelper;
import org.apache.geode.internal.monitoring.ThreadsMonitoring;
import org.apache.geode.logging.internal.executors.LoggingThread;
import org.apache.geode.logging.internal.executors.LoggingThreadFactory;
import org.apache.geode.logging.internal.executors.LoggingThreadFactory.CommandWrapper;
import org.apache.geode.logging.internal.executors.LoggingThreadFactory.ThreadInitializer;

public class CoreLoggingExecutorsTest {

  private CommandWrapper commandWrapper;
  private PoolStatHelper poolStatHelper;
  private QueueStatHelper queueStatHelper;
  private Runnable runnable;
  private ThreadInitializer threadInitializer;
  private ThreadsMonitoring threadsMonitoring;

  @Before
  public void setUp() {
    commandWrapper = mock(CommandWrapper.class);
    poolStatHelper = mock(PoolStatHelper.class);
    queueStatHelper = mock(QueueStatHelper.class);
    runnable = mock(Runnable.class);
    threadInitializer = mock(ThreadInitializer.class);
    threadsMonitoring = mock(ThreadsMonitoring.class);
  }

  @Test
  public void newFixedThreadPoolWithTimeout() {
    int poolSize = 5;
    int keepAliveTime = 2;
    String threadName = "thread";

    ExecutorService executorService = CoreLoggingExecutors
        .newFixedThreadPoolWithTimeout(poolSize, keepAliveTime, MINUTES, queueStatHelper,
            threadName);

    assertThat(executorService).isInstanceOf(ThreadPoolExecutor.class);

    ThreadPoolExecutor executor = (ThreadPoolExecutor) executorService;

    assertThat(executor.getCorePoolSize()).isEqualTo(poolSize);
    assertThat(executor.getKeepAliveTime(MINUTES)).isEqualTo(keepAliveTime);
    assertThat(executor.getMaximumPoolSize()).isEqualTo(poolSize);
    assertThat(executor.getQueue()).isInstanceOf(OverflowQueueWithDMStats.class);
    assertThat(executor.getThreadFactory()).isInstanceOf(LoggingThreadFactory.class);

    OverflowQueueWithDMStats overflowQueueWithDMStats =
        (OverflowQueueWithDMStats) executor.getQueue();

    assertThat(overflowQueueWithDMStats.getQueueStatHelper()).isSameAs(queueStatHelper);

    ThreadFactory threadFactory = executor.getThreadFactory();
    Thread thread = threadFactory.newThread(runnable);

    assertThat(thread).isInstanceOf(LoggingThread.class);
    assertThat(thread.getName()).contains(threadName);
  }

  @Test
  public void newFunctionThreadPoolWithFeedStatistics() {
    int poolSize = 5;
    int workQueueSize = 2;
    String threadName = "thread";

    ExecutorService executorService = CoreLoggingExecutors
        .newFunctionThreadPoolWithFeedStatistics(poolSize, workQueueSize, queueStatHelper,
            threadName, threadInitializer, commandWrapper, poolStatHelper, threadsMonitoring);

    assertThat(executorService).isInstanceOf(FunctionExecutionPooledExecutor.class);

    FunctionExecutionPooledExecutor executor = (FunctionExecutionPooledExecutor) executorService;

    assertThat(executor.getCorePoolSize()).isEqualTo(1);
    assertThat(executor.getKeepAliveTime(MINUTES)).isEqualTo(30);
    assertThat(executor.getMaximumPoolSize()).isEqualTo(poolSize);
    assertThat(executor.getBufferQueue()).isInstanceOf(OverflowQueueWithDMStats.class);
    assertThat(executor.getQueue()).isInstanceOf(SynchronousQueue.class);
    assertThat(executor.getThreadFactory()).isInstanceOf(LoggingThreadFactory.class);

    OverflowQueueWithDMStats overflowQueueWithDMStats =
        (OverflowQueueWithDMStats) executor.getBufferQueue();

    assertThat(overflowQueueWithDMStats.getQueueStatHelper()).isSameAs(queueStatHelper);

    ThreadFactory threadFactory = executor.getThreadFactory();
    Thread thread = threadFactory.newThread(runnable);

    assertThat(thread).isInstanceOf(LoggingThread.class);
    assertThat(thread.getName()).contains(threadName);
  }
}
