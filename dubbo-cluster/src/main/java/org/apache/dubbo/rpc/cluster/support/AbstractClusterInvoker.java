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
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    protected final Directory<T> directory;

    protected final boolean availablecheck;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile Invoker<T> stickyInvoker = null;

    // 给当前对象的成员变量directory和availablecheck赋值
    public AbstractClusterInvoker(Directory<T> directory) {

        // 可以看到，第二个参数url，取的是入参directory对象的url
        this(directory, directory.getUrl());
    }

    // 给当前对象的成员变量directory和availablecheck赋值
    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        // 便利贴： 当availablecheck=true时， 在使用invoker之前，需要调用invoker.isAvailable()检查其是否可用

        // 取url对象的 "cluster.availablecheck"属性值，默认值是true
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * 使用入参loadbalance提供的策略，选一个invoker对象。
     * a) 首先，使用loadbalance提供的策略选择一个invoker对象， 如果这个invoker在入参selected集合里，或者，
     * 如果这个invoker对象不可用，则执行reselect操作， 否则就返回刚选中的invoker对象
     * b) 重新选择， 被选出来的invoker对象有最小的概率来自于入参selected集合，并且保证这个被选出来的invoker对象可用。
     *
     *
     * @param loadbalance load balance policy 负载均衡策略
     * @param invocation  invocation
     * @param invokers    invoker candidates 可选择的invoker集合
     * @param selected    exclude selected invokers or not 已经选过的invoker集合
     * @return the invoker which will final to do invoke. 返回一个invoker对象，用于调用
     * @throws RpcException exception
     */

    // 先看当前对象的成员变量stickyInvoker是否可用，若可用就直接返回该对象。 （stickyInvoker就是一个Invoker对象，相当于缓存）
    // 否则，就从入参invokers集合中， 通过负载均衡组件选择一个Invoker对象返回。
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 取入参invocation对象所代表的方法的名字
        String methodName = invocation == null ? StringUtils.EMPTY : invocation.getMethodName();

        // 从url对象中获取 sticky 配置，sticky 表示粘滞连接。所谓粘滞连接是指让服务消费者尽可能的
        // 调用同一个服务提供者，除非该提供者挂了再进行切换
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);

        // 检测 invokers 列表是否包含 stickyInvoker，如果不包含，
        // 说明 stickyInvoker 代表的服务提供者挂了，此时需要将其置空
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }

        // 到这若stickyInvoker不为空，说明它代表的服务提供者还可用。
        // stickyInvoker 对应的服务提供者可能因网络原因未能成功提供服务。
        // 但是该提供者并没挂，因为此时 invokers 列表中仍存在该服务提供者对应的 Invoker。


        //ignore concurrency problem
        // 在 sticky 为 true，且 stickyInvoker != null 的情况下，若selected 不包含stickyInvoker，则进if
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {

            if (availablecheck && stickyInvoker.isAvailable()) {
                // availablecheck 表示是否开启了可用性检查。
                // 如果开启了，则调用 stickyInvoker 的isAvailable 方法进行检查，如果检查通过，则直接返回 stickyInvoker。
                return stickyInvoker;
            }
        }

        // 如果线程走到当前代码处，说明前面的 stickyInvoker 为空，或者不可用。
        // 此时继续调用 doSelect 选择 Invoker
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        // 如果 sticky 为 true，则将负载均衡组件选出的 Invoker 赋值给 stickyInvoker
        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /**
     * 从入参invokers集合中， 通过负载均衡对象loadbalance 选择一个Invoker对象
     *
     * 被选出的这个Invoker对象可能有两种情况
     * 1）选择出的这个Invoker对象，在入参selected中，
     * 2）选择出的这个Invoker对象不可用（未经过可用性检查）
     * 若选择出的这个Invoker对象属于以上两种情况之一， 则从invokers集合中重新进行选择。
     * 若不属于，则直接返回该Invoker对象。
     *
     * 看重新选择reselect()的代码你会发现，本函数最后返回的Invoker对象，肯定是可用的，但还是可能来自于入参selected集合
     * 就是说 在情况1时，虽然需要重选，但选出来并返回的Invoker对象仍然可以来自入参selected集合，
     * 因为重新选择reselect()只是优先从入参invokers集合中选而已，实在是找不到了，还是要从入参selected集合中找。
     *
     */
    // 从入参invokers集合中， 通过负载均衡组件选择一个Invoker对象，并返回该对象。
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        // 通过负载均衡组件选择 Invoker
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        // 如果 selected 包含负载均衡选择出的 Invoker，
        // 或者该 Invoker 无法经过可用性检查，此时进行重选
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                // 进行重选
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                // 如果 rinvoker 不为空，则将其赋值给 invoker
                if (rInvoker != null) {
                    invoker = rInvoker;
                } else {
                    // 若 reselect 选出来的 Invoker 为空，
                    // 此时定位 invoker 在 invokers 列表中的位置 index，然后获取 index + 1 处的 invoker，
                    // 这也可以看做是重选逻辑的一部分。

                    // rinvoker 为空，定位 invoker 在 invokers 中的位置
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        // 获取 index + 1 位置处的 Invoker，以下代码等价于：
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     *
     * @param loadbalance    load balance policy
     * @param invocation     invocation
     * @param invokers       invoker candidates
     * @param selected       exclude selected invokers or not
     * @param availablecheck check invoker available if true
     * @return the reselect result to do invoke
     * @throws RpcException exception
     */

    /**
     * reselect 方法总结下来其实只做了两件事情，
     * 第一是查找可用的 Invoker，并将其添加到 reselectInvokers 集合中。 （这步会先从入参invokers 再从 入参selected中挑选可用的Invoker）
     * 第二，如果 reselectInvokers 不为空，则通过负载均衡组件再次进行选择。
     *
     * 其中，第一件事情又可进行细分，
     *      先从 入参invokers 列表中查找有效可用的 Invoker，若未能找到，此时再到 selected 列表中继续查找。
     */
    // 先从入参invokers集合 再从入参selected集合中挑选一个可用的Invoker对象，并返回该对象。
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // First, try picking a invoker not in `selected`.
        // 先选一个不在selected集合中的Invoker对象。添加到reselectInvokers集合中
        for (Invoker<T> invoker : invokers) {
            // 检测可用性
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }

            // 如果invoker不在selected集合中，则将其添加到reselectInvokers中
            if (selected == null || !selected.contains(invoker)) {
                reselectInvokers.add(invoker);
            }
        }

        // reselectInvokers 不为空，此时通过负载均衡组件进行选择
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // Just pick an available invoker using loadbalance policy
        // 若线程走到此处，说明 reselectInvokers 集合为空。
        // 这里从 selected 列表中查找可用的 Invoker，并将其添加到 reselectInvokers 集合中
        if (selected != null) {
            for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
        }
        if (!reselectInvokers.isEmpty()) {
            // 再次进行选择，并返回选择结果
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();

        // binding attachments into invocation.
        // 绑定 attachments 到入参invocation对象中.
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }

        // 列举 Invoker
        List<Invoker<T>> invokers = list(invocation);
        // 加载LoadBalance接口的一个实现类对象
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        // 调用 doInvoke 进行后续操作
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {
        // 当前对象是否被销毁
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    // 检查入参invokers是否为空，为空则log
    // 入参invokers为空，表示没有服务提供者
    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    // 加载LoadBalance接口的一个实现类的对象
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            // 从url对象中取"loadbalance"属性的value值，默认值是"random"
            // （其中，url对象是invokers集合的第一个元素的url属性值，value值就是要加载的实现类的别名）
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        } else {
            // 取别名是"random"的实现类的对象
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }
    }
}
