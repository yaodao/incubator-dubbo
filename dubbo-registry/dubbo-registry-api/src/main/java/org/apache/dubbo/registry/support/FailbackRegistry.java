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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.retry.FailedNotifiedTask;
import org.apache.dubbo.registry.retry.FailedRegisteredTask;
import org.apache.dubbo.registry.retry.FailedSubscribedTask;
import org.apache.dubbo.registry.retry.FailedUnregisteredTask;
import org.apache.dubbo.registry.retry.FailedUnsubscribedTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * FailbackRegistry. (SPI, Prototype, ThreadSafe)
 */
public abstract class FailbackRegistry extends AbstractRegistry {

    /*  retry task map */

    // 注册失败的URL集合
    private final ConcurrentMap<URL, FailedRegisteredTask> failedRegistered = new ConcurrentHashMap<URL, FailedRegisteredTask>();

    // 取消注册 失败的URL集合
    private final ConcurrentMap<URL, FailedUnregisteredTask> failedUnregistered = new ConcurrentHashMap<URL, FailedUnregisteredTask>();

    // 订阅失败的监听器集合
    private final ConcurrentMap<Holder, FailedSubscribedTask> failedSubscribed = new ConcurrentHashMap<Holder, FailedSubscribedTask>();

    // 取消订阅失败的监听器集合
    private final ConcurrentMap<Holder, FailedUnsubscribedTask> failedUnsubscribed = new ConcurrentHashMap<Holder, FailedUnsubscribedTask>();

    // 通知失败的URL集合
    private final ConcurrentMap<Holder, FailedNotifiedTask> failedNotified = new ConcurrentHashMap<Holder, FailedNotifiedTask>();

    /**
     * The time in milliseconds the retryExecutor will wait
     */
    // 重试周期
    private final int retryPeriod;

    // Timer for failure retry, regular check if there is a request for failure, and if there is, an unlimited retry
    // 定时器
    private final HashedWheelTimer retryTimer;

    // 构造函数主要是创建定时器用于失败重试，重试频率从URL取，如果没有设置，则默认为5000ms。
    public FailbackRegistry(URL url) {
        super(url);
        // 取url中"retry.period"的值，若没有，则取默认值为5000ms
        this.retryPeriod = url.getParameter(Constants.REGISTRY_RETRY_PERIOD_KEY, Constants.DEFAULT_REGISTRY_RETRY_PERIOD);

        // since the retry task will not be very much. 128 ticks is enough.
        // 生成一个定时器，定时器有128个格
        retryTimer = new HashedWheelTimer(new NamedThreadFactory("DubboRegistryRetryTimer", true), retryPeriod, TimeUnit.MILLISECONDS, 128);
    }

    // 将url从注册失败的URL集合中移除
    public void removeFailedRegisteredTask(URL url) {
        failedRegistered.remove(url);
    }

    // 将url从 取消注册 失败的URL集合中移除
    public void removeFailedUnregisteredTask(URL url) {
        failedUnregistered.remove(url);
    }

    public void removeFailedSubscribedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedSubscribed.remove(h);
    }

    public void removeFailedUnsubscribedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedUnsubscribed.remove(h);
    }

    public void removeFailedNotifiedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedNotified.remove(h);
    }

    // 将url和该url对应的任务 添加到注册失败的URL集合，并将url对应的任务放到定时器
    // 该url对应的任务主要功能是：根据url的信息，重新在zk上创建一系列节点
    private void addFailedRegistered(URL url) {
        FailedRegisteredTask oldOne = failedRegistered.get(url);
        // 有该url对应的task对象，则不用再添加，直接返回
        if (oldOne != null) {
            return;
        }
        // 为参数url新建一个task对象
        FailedRegisteredTask newTask = new FailedRegisteredTask(url, this);
        // 将（url，task）添加到注册失败的URL集合
        oldOne = failedRegistered.putIfAbsent(url, newTask);
        if (oldOne == null) {// 返回值==nul表示failedRegistered中原来没有该url
            // 添加到failedRegistered成功， 则将该任务添加到定时器中
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    // 从注册失败的URL集合中移除入参url，并设置该url对应的task的状态为已取消
    private void removeFailedRegistered(URL url) {
        // 从failedRegistered中移除url， 并返回该url对应的task对象
        FailedRegisteredTask f = failedRegistered.remove(url);
        if (f != null) {
            // 设置task的状态为已取消
            f.cancel();
        }
    }
    // 将（url， url对应的task）加入到注册取消失败的缓存中（成员变量failedUnregistered），并将task放到定时器中
    private void addFailedUnregistered(URL url) {
        FailedUnregisteredTask oldOne = failedUnregistered.get(url);
        if (oldOne != null) {
            return;
        }
        FailedUnregisteredTask newTask = new FailedUnregisteredTask(url, this);
        oldOne = failedUnregistered.putIfAbsent(url, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            // 将该url对应的task放到定时器中
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    // 从取消注册 失败的URL集合中移除入参url，并设置该url对应的task的状态为已取消
    private void removeFailedUnregistered(URL url) {
        FailedUnregisteredTask f = failedUnregistered.remove(url);
        if (f != null) {
            // 设置task的状态为已取消
            f.cancel();
        }
    }

    /**
     * 将（url，listener） 和对应的task添加到订阅失败的监听器集合（成员变量failedSubscribed中）
     * 并将对应的task放到定时器中
     * @param url 消费者url
     * @param listener 该消费者拥有的监听器
     */
    private void addFailedSubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedSubscribedTask oldOne = failedSubscribed.get(h);
        // 已有该holder，直接返回
        if (oldOne != null) {
            return;
        }
        // 生成一个订阅失败任务对象
        FailedSubscribedTask newTask = new FailedSubscribedTask(url, this, listener);
        // failedSubscribed中还没有该holder， 则添加进去
        oldOne = failedSubscribed.putIfAbsent(h, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            // 将该holder对应的task放到定时器中
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    // 从failedSubscribed 、failedUnsubscribed 、failedNotified删除该监听器
    private void removeFailedSubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        // 将该url从订阅失败的监听器集合中移除
        FailedSubscribedTask f = failedSubscribed.remove(h);
        if (f != null) {
            // 设置任务状态为取消
            f.cancel();
        }
        // 将该url从 取消订阅 失败的监听器集合中移除
        removeFailedUnsubscribed(url, listener);
        // 将该url从 通知失败的URL集合中移除
        removeFailedNotified(url, listener);
    }

    // 将（url，listener） 和对应的task添加到取消订阅失败的监听器集合（成员变量failedUnsubscribed中）
    private void addFailedUnsubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        // 从 取消订阅失败的监听器集合中取h对应的task
        FailedUnsubscribedTask oldOne = failedUnsubscribed.get(h);
        if (oldOne != null) {
            return;
        }
        // 新建一个task
        FailedUnsubscribedTask newTask = new FailedUnsubscribedTask(url, this, listener);
        oldOne = failedUnsubscribed.putIfAbsent(h, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            // 将该holder对应的task放到定时器中
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    // 将该url从 取消订阅 失败的监听器集合中移除，并设置该url对应的任务状态为取消
    private void removeFailedUnsubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedUnsubscribedTask f = failedUnsubscribed.remove(h);
        if (f != null) {
            // 设置任务状态为取消
            f.cancel();
        }
    }

    // 将（参数url，task）添加到通知失败的URL集合（成员变量failedNotified），并将参数url对应的任务放到定时器中
    private void addFailedNotified(URL url, NotifyListener listener, List<URL> urls) {
        Holder h = new Holder(url, listener);
        FailedNotifiedTask newTask = new FailedNotifiedTask(url, listener);
        FailedNotifiedTask f = failedNotified.putIfAbsent(h, newTask);
        if (f == null) {
            // never has a retry task. then start a new task for retry.
            // 将服务提供者的urls添加到task对象的成员变量urls中
            newTask.addUrlToRetry(urls);
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        } else {
            // just add urls which needs retry.
            newTask.addUrlToRetry(urls);
        }
    }

    // 将该url从 通知失败的URL集合中移除，并设置该url对应的任务状态为取消
    private void removeFailedNotified(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedNotifiedTask f = failedNotified.remove(h);
        if (f != null) {
            // 设置任务状态为取消
            f.cancel();
        }
    }

    public ConcurrentMap<URL, FailedRegisteredTask> getFailedRegistered() {
        return failedRegistered;
    }

    public ConcurrentMap<URL, FailedUnregisteredTask> getFailedUnregistered() {
        return failedUnregistered;
    }

    public ConcurrentMap<Holder, FailedSubscribedTask> getFailedSubscribed() {
        return failedSubscribed;
    }

    public ConcurrentMap<Holder, FailedUnsubscribedTask> getFailedUnsubscribed() {
        return failedUnsubscribed;
    }

    public ConcurrentMap<Holder, FailedNotifiedTask> getFailedNotified() {
        return failedNotified;
    }

    @Override
    // 向注册中心注册
    public void register(URL url) {
        super.register(url);
        // 从失败的缓存中删除该url
        removeFailedRegistered(url);
        removeFailedUnregistered(url);
        try {
            // Sending a registration request to the server side
            // 向注册中心发送一个注册请求
            // 在zk上建立节点
            doRegister(url);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            // 如果开启了启动时检测，则直接抛出异常
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !Constants.CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to register " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to register " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            // 添加url到failedRegistered，并定时重试url对应的任务
            addFailedRegistered(url);
        }
    }

    @Override
    // 取消注册
    public void unregister(URL url) {
        super.unregister(url);
        // 从失败的缓存中删除该url
        removeFailedRegistered(url);
        removeFailedUnregistered(url);
        try {
            // Sending a cancellation request to the server side
            // 向注册中心发送取消注册的请求
            doUnregister(url);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !Constants.CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unregister " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unregister " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            // 加入到注册取消失败的缓存中
            addFailedUnregistered(url);
        }
    }

    @Override
    /**
     * 为url新增一个监听器listener（就是url又订阅了一个新的服务）
     *
     *
     * @param url 消费者url
     * @param listener 监听器
     */
    public void subscribe(URL url, NotifyListener listener) {
        // 为参数url 新增加一个监听器listener 到subscribed中
        super.subscribe(url, listener);
        // 从failedSubscribed 、failedUnsubscribed 、failedNotified删除该监听器
        removeFailedSubscribed(url, listener);
        try {
            // Sending a subscription request to the server side
            // 向注册中心发送一个订阅的请求
            doSubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;
            // 订阅失败的时候，调用notify函数， 将最新的服务提供者urls信息,推送给订阅了该服务的监听器listener

            // 获取消费者url对应的 服务提供者url的集合（从缓存properties中取）
            List<URL> urls = getCacheUrls(url);
            if (CollectionUtils.isNotEmpty(urls)) {
                notify(url, listener, urls);
                logger.error("Failed to subscribe " + url + ", Using cached list: " + urls + " from cache file: " + getUrl().getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/dubbo-registry-" + url.getHost() + ".cache") + ", cause: " + t.getMessage(), t);
            } else {
                // If the startup detection is opened, the Exception is thrown directly.
                boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                        && url.getParameter(Constants.CHECK_KEY, true);
                boolean skipFailback = t instanceof SkipFailbackWrapperException;
                if (check || skipFailback) {
                    if (skipFailback) {
                        t = t.getCause();
                    }
                    throw new IllegalStateException("Failed to subscribe " + url + ", cause: " + t.getMessage(), t);
                } else {
                    logger.error("Failed to subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
                }
            }

            // Record a failed registration request to a failed list, retry regularly
            // 将（url，listener） 和对应的task添加到成员变量failedSubscribed中（订阅失败的监听器集合）,并将对应的task放到定时器中
            addFailedSubscribed(url, listener);
        }
    }

    @Override
    // 取消订阅url所拥有的某个监听器listener
    public void unsubscribe(URL url, NotifyListener listener) {
        // 移除消费者url所拥有的某个监听器listener
        super.unsubscribe(url, listener);
        removeFailedSubscribed(url, listener);
        try {
            // Sending a canceling subscription request to the server side
            // 移除url所拥有的单个监听器listener
            doUnsubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true);
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unsubscribe " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unsubscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            // 将（url，listener） 和对应的task添加到取消订阅失败的监听器集合（成员变量failedUnsubscribed中）
            addFailedUnsubscribed(url, listener);
        }
    }

    @Override
    // 将最新的服务提供者urls信息,推送给订阅了该服务的监听器listener
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        try {
            // 将最新的服务提供者urls信息，推送给订阅了该服务的监听器listener
            doNotify(url, listener, urls);
        } catch (Exception t) {
            // Record a failed registration request to a failed list, retry regularly
            // 将（参数url，task）添加到通知失败的URL集合， 并将task添加到定时器
            addFailedNotified(url, listener, urls);
            logger.error("Failed to notify for subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
        }
    }

    // 将最新的服务提供者urls信息，推送给订阅了该服务的监听器listener
    protected void doNotify(URL url, NotifyListener listener, List<URL> urls) {
        super.notify(url, listener, urls);
    }

    @Override
    // 将成员变量registered和subscribed中的元素添加到 成员变量failedRegistered和failedSubscribed中
    // 并将每一个元素对应的任务添加到定时器中
    protected void recover() throws Exception {
        // register
        // 已注册的服务的URL集合
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                // 将url和该url对应的任务 添加到注册失败的URL集合（成员变量failedRegistered中）
                // 并将url对应的任务放到定时器
                addFailedRegistered(url);
            }
        }
        // subscribe
        // 已有的订阅信息， key是消费者url, value是该url所拥有的监听器集合（其实就是该url订阅了其他的服务者url）
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            // 遍历每一条订阅信息，将其添加到订阅失败的监听器集合（成员变量failedSubscribed中）
            // 并将url对应的task放到定时器中
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        retryTimer.stop();
    }

    // ==== Template method ====

    public abstract void doRegister(URL url);

    public abstract void doUnregister(URL url);

    public abstract void doSubscribe(URL url, NotifyListener listener);

    public abstract void doUnsubscribe(URL url, NotifyListener listener);

    // 相当于pair， 存放（url， 监听器），url应该是消费者url
    static class Holder {

        private final URL url;

        private final NotifyListener notifyListener;

        Holder(URL url, NotifyListener notifyListener) {
            if (url == null || notifyListener == null) {
                throw new IllegalArgumentException();
            }
            this.url = url;
            this.notifyListener = notifyListener;
        }

        @Override
        public int hashCode() {
            return url.hashCode() + notifyListener.hashCode();
        }

        @Override
        // url和notifyListener都相等，两个Holder对象才相等
        public boolean equals(Object obj) {
            if (obj instanceof Holder) {
                Holder h = (Holder) obj;
                return this.url.equals(h.url) && this.notifyListener.equals(h.notifyListener);
            } else {
                return false;
            }
        }
    }
}
