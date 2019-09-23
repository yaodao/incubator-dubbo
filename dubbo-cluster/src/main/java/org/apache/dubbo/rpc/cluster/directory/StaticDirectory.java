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
package org.apache.dubbo.rpc.cluster.directory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.RouterChain;

import java.util.Collections;
import java.util.List;

/**
 * StaticDirectory
 */
public class StaticDirectory<T> extends AbstractDirectory<T> {
    private static final Logger logger = LoggerFactory.getLogger(StaticDirectory.class);

    private final List<Invoker<T>> invokers;

    // 生成一个StaticDirectory对象
    public StaticDirectory(List<Invoker<T>> invokers) {
        this(null, invokers, null);
    }

    public StaticDirectory(List<Invoker<T>> invokers, RouterChain<T> routerChain) {
        this(null, invokers, routerChain);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers) {
        this(url, invokers, null);
    }

    // 生成一个StaticDirectory对象，并给它自己和它父类的成员变量赋值
    public StaticDirectory(URL url, List<Invoker<T>> invokers, RouterChain<T> routerChain) {
        // 若入参url为空 且 入参invokers不空，则调用 super(invokers.get(0).getUrl(), routerChain)
        // 其他情况调用super(url, routerChain)
        super(url == null && CollectionUtils.isNotEmpty(invokers) ? invokers.get(0).getUrl() : url, routerChain);

        // 入参invokers为空，则抛出异常
        if (CollectionUtils.isEmpty(invokers)) {
            throw new IllegalArgumentException("invokers == null");
        }
        this.invokers = invokers;
    }

    @Override
    public Class<T> getInterface() {
        return invokers.get(0).getInterface();
    }

    @Override
    // 检测当前服务目录是否可用
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        for (Invoker<T> invoker : invokers) {
            // 只要有一个 Invoker 是可用的，就认为当前目录是可用的
            if (invoker.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    // 销毁当前服务目录
    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        super.destroy();
        // 遍历 Invoker 列表，并执行相应的销毁逻辑
        for (Invoker<T> invoker : invokers) {
            invoker.destroy();
        }
        // 清空当前服务目录的invokers集合
        invokers.clear();
    }

    // 构造一个RouterChain对象， 设置到当前对象的routerChain属性中
    public void buildRouterChain() {
        // 构造一个RouterChain对象
        RouterChain<T> routerChain = RouterChain.buildChain(getUrl());
        // 给routerChain的invokers赋值
        routerChain.setInvokers(invokers);

        // 将上面构造出的RouterChain对象， 赋给当前对象的routerChain属性
        this.setRouterChain(routerChain);
    }

    @Override
    protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {
        List<Invoker<T>> finalInvokers = invokers;
        if (routerChain != null) {
            try {
                finalInvokers = routerChain.route(getConsumerUrl(), invocation);
            } catch (Throwable t) {
                logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
            }
        }
        return finalInvokers == null ? Collections.emptyList() : finalInvokers;
    }

}
