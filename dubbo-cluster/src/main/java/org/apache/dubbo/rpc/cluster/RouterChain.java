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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Router chain
 */
// 暂时理解为 包含多个路由器对象的类
public class RouterChain<T> {

    // full list of addresses from registry, classified by method name.
    private List<Invoker<T>> invokers = Collections.emptyList();

    // containing all routers, reconstruct every time 'route://' urls change.
    private volatile List<Router> routers = Collections.emptyList();

    // Fixed router instances: ConfigConditionRouter, TagRouter, e.g., the rule for each instance may change but the
    // instance will never delete or recreate.
    private List<Router> builtinRouters = Collections.emptyList();

    // 构造一个RouterChain对象，并给它的两个成员变量builtinRouters，routers赋值（两个成员变量都是存放Router对象的集合）
    public static <T> RouterChain<T> buildChain(URL url) {
        return new RouterChain<>(url);
    }

    // 构造一个RouterChain对象，并给它的两个成员变量builtinRouters，routers赋值
    // （两个成员变量都是存放Router对象的集合）
    private RouterChain(URL url) {
        // 在RouterFactory接口的实现类中，查找带有@Activate注解 且 与url的属性名匹配的实现类对象，
        // 返回符合条件的类的对象。
        List<RouterFactory> extensionFactories = ExtensionLoader.getExtensionLoader(RouterFactory.class)
                .getActivateExtension(url, (String[]) null);

        List<Router> routers = extensionFactories.stream()
                // 使用factory对象，获取Router对象
                .map(factory -> factory.getRouter(url))
                // 将Router对象收集到集合中
                .collect(Collectors.toList());

        // 给当前对象的成员变量builtinRouters和routers赋值
        initWithRouters(routers);
    }

    /**
     * the resident routers must being initialized before address notification.
     * FIXME: this method should not be public
     */
    public void initWithRouters(List<Router> builtinRouters) {
        this.builtinRouters = builtinRouters;
        this.routers = new CopyOnWriteArrayList<>(builtinRouters);
        this.sort();
    }

    /**
     * If we use route:// protocol in version before 2.7.0, each URL will generate a Router instance, so we should
     * keep the routers up to date, that is, each time router URLs changes, we should update the routers list, only
     * keep the builtinRouters which are available all the time and the latest notified routers which are generated
     * from URLs.
     *
     * @param routers routers from 'router://' rules in 2.6.x or before.
     */
    // 给当前对象的成员变量routers赋值。
    // 将入参routers集合 和 成员变量builtinRouters集合，合并到一起，赋值给当前对象的成员变量routers
    public void addRouters(List<Router> routers) {
        List<Router> newRouters = new CopyOnWriteArrayList<>();
        newRouters.addAll(builtinRouters);
        newRouters.addAll(routers);
        CollectionUtils.sort(routers);
        this.routers = newRouters;
    }

    private void sort() {
        Collections.sort(routers);
    }

    /**
     *
     * @param url
     * @param invocation
     * @return
     */
    // 为入参url选出服务提供者集合，并返回
    // 这个函数有些奇怪：
    // 遍历成员变量routers集合，前面router对象取到的Invoker集合会被后面的覆盖掉。
    // 暂时认为原因可能是成员变量routers集合只有一个元素。
    public List<Invoker<T>> route(URL url, Invocation invocation) {
        // 当没有路由对象的时候，默认返回invokers
        List<Invoker<T>> finalInvokers = invokers;
        // 遍历路由对象
        for (Router router : routers) {
            finalInvokers = router.route(finalInvokers, url, invocation);
        }
        return finalInvokers;
    }

    /**
     * Notify router chain of the initial addresses from registry at the first time.
     * Notify whenever addresses in registry change.
     */
    // 设置当前对象的invokers属性
    // 调用当前对象的路由集合中，每个路由对象的notify方法
    public void setInvokers(List<Invoker<T>> invokers) {
        this.invokers = (invokers == null ? Collections.emptyList() : invokers);
        routers.forEach(router -> router.notify(this.invokers));
    }
}
