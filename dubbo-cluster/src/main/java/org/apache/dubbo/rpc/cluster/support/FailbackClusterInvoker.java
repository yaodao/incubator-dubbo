/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * When fails, record failure requests and schedule for retry on a regular interval.
 * Especially useful for services of notification.
 *
 * <a href="http://en.wikipedia.org/wiki/Failback">Failback</a>
 */
public class FailbackClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailbackClusterInvoker.class);

    private static final long RETRY_FAILED_PERIOD = 5;

    private final int retries;

    private final int failbackTasks;

    private volatile Timer failTimer;

    // 给当前对象的成员变量赋值，包括：
    // directory、availablecheck、retries、failbackTasks
    public FailbackClusterInvoker(Directory<T> directory) {
        // 给当前对象的成员变量directory和availablecheck赋值
        super(directory);
        // 取url的"retries"属性值，默认是3
        int retriesConfig = getUrl().getParameter(Constants.RETRIES_KEY, Constants.DEFAULT_FAILBACK_TIMES);
        if (retriesConfig <= 0) {
            retriesConfig = Constants.DEFAULT_FAILBACK_TIMES;
        }

        // 取url的"failbacktasks"属性值，默认是100
        int failbackTasksConfig = getUrl().getParameter(Constants.FAIL_BACK_TASKS_KEY, Constants.DEFAULT_FAILBACK_TASKS);
        if (failbackTasksConfig <= 0) {
            failbackTasksConfig = Constants.DEFAULT_FAILBACK_TASKS;
        }
        retries = retriesConfig;
        failbackTasks = failbackTasksConfig;
    }

    // 若当前对象的成员变量failTimer为空，则生成一个Timer对象，赋给它
    // 之后，根据入参，生成一个RetryTimerTask对象，添加到Timer对象中执行
    private void addFailed(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, Invoker<T> lastInvoker) {
        if (failTimer == null) {
            synchronized (this) {
                if (failTimer == null) {
                    failTimer = new HashedWheelTimer(
                            new NamedThreadFactory("failback-cluster-timer", true),
                            1,
                            TimeUnit.SECONDS, 32, failbackTasks);
                }
            }
        }
        RetryTimerTask retryTimerTask = new RetryTimerTask(loadbalance, invocation, invokers, lastInvoker, retries, RETRY_FAILED_PERIOD);
        try {
            failTimer.newTimeout(retryTimerTask, RETRY_FAILED_PERIOD, TimeUnit.SECONDS);
        } catch (Throwable e) {
            logger.error("Failback background works error,invocation->" + invocation + ", exception: " + e.getMessage());
        }
    }

    @Override
    // 选择一个Invoker对象，执行远程调用。
    // 若远程调用失败，则通过 addFailed 方法给timer添加任务，定时重试。
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        Invoker<T> invoker = null;
        try {
            // 检查入参invokers是否为空，为空则log
            checkInvokers(invokers, invocation);
            // 从入参invokers集合中， 通过负载均衡组件选择一个Invoker对象。
            invoker = select(loadbalance, invocation, invokers, null);
            // 进行调用
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            // 如果调用过程中发生异常，此时仅打印错误日志，不抛出异常
            logger.error("Failback to invoke method " + invocation.getMethodName() + ", wait for retry in background. Ignored exception: "
                    + e.getMessage() + ", ", e);
            // 向成员变量failTimer添加一个RetryTimerTask 用于执行
            addFailed(loadbalance, invocation, invokers, invoker);
            // 返回一个空结果给服务消费者
            return new RpcResult(); // ignore
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (failTimer != null) {
            failTimer.stop();
        }
    }

    /**
     * RetryTimerTask
     */
    private class RetryTimerTask implements TimerTask {
        private final Invocation invocation;
        private final LoadBalance loadbalance;
        private final List<Invoker<T>> invokers;
        private final int retries;
        private final long tick;
        private Invoker<T> lastInvoker;
        private int retryTimes = 0;

        RetryTimerTask(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, Invoker<T> lastInvoker, int retries, long tick) {
            this.loadbalance = loadbalance;
            this.invocation = invocation;
            this.invokers = invokers;
            this.retries = retries;
            this.tick = tick;
            this.lastInvoker=lastInvoker;
        }

        @Override
        // 入参timeout是与当前RetryTimerTask对象关联的timeout对象
        public void run(Timeout timeout) {
            try {
                // 从入参invokers中选一个invoker对象
                Invoker<T> retryInvoker = select(loadbalance, invocation, invokers, Collections.singletonList(lastInvoker));
                lastInvoker = retryInvoker;
                // 执行方法
                retryInvoker.invoke(invocation);
            } catch (Throwable e) {
                logger.error("Failed retry to invoke method " + invocation.getMethodName() + ", waiting again.", e);

                // 这里是失败后，重试执行，重试次数retries在这里用到了。
                if ((++retryTimes) >= retries) {
                    logger.error("Failed retry times exceed threshold (" + retries + "), We have to abandon, invocation->" + invocation);
                } else {
                    // 再执行一次当前的Task
                    rePut(timeout);
                }
            }
        }

        // 将入参timeout关联的task对象，重新放到timer中（timer到时间就会执行这个task对象）
        // （其中，timeout关联的task对象，由timeout.task()取到）
        private void rePut(Timeout timeout) {
            if (timeout == null) {
                return;
            }

            Timer timer = timeout.timer();
            if (timer.isStop() || timeout.isCancelled()) {
                return;
            }

            timer.newTimeout(timeout.task(), tick, TimeUnit.SECONDS);
        }
    }
}
