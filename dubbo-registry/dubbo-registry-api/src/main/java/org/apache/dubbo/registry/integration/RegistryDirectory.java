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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.Constants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.CATEGORY_KEY;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.Constants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.Constants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.Constants.ROUTE_PROTOCOL;


/**
 * RegistryDirectory
 *
 * Directory代表多个Invoker，可以把它看成List<Invoker>，但与List不同的是，它的值可能是动态变化的，比如注册中心推送变更。
 * Cluster将Directory中的多个Invoker伪装成一个Invoker，对上层透明，伪装过程包含了容错逻辑，调用失败后，重试另一个。
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class)
            .getAdaptiveExtension();

    private final String serviceKey; // Initialization at construction time, assertion not null
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    private final boolean multiGroup;
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    // 服务提供者关闭或禁用了服务
    private volatile boolean forbidden = false;

    // provider url
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    private volatile URL registeredConsumerUrl;

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    // key是服务提供者url的串，value是该url串对应的invoker对象
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    private volatile List<Invoker<T>> invokers;

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    private static final ConsumerConfigurationListener consumerConfigurationListener = new ConsumerConfigurationListener();
    private ReferenceConfigurationListener serviceConfigurationListener;


    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = turnRegistryUrlToConsumerUrl(url);
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && (Constants.ANY_VALUE.equals(group) || group.contains(","));
    }

    private URL turnRegistryUrlToConsumerUrl(URL url) {
        // save any parameter in registry that will be useful to the new url.
        String isDefault = url.getParameter(Constants.DEFAULT_KEY);
        if (StringUtils.isNotEmpty(isDefault)) {
            // queryMap中添加（"registry.default"，"{isDefault}"）
            queryMap.put(Constants.REGISTRY_KEY + "." + Constants.DEFAULT_KEY, isDefault);
        }
        return URLBuilder.from(url)
                .setPath(url.getServiceInterface())
                .clearParameters()
                .addParameters(queryMap)
                .removeParameter(Constants.MONITOR_KEY)
                .build();
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        setConsumerUrl(url);
        consumerConfigurationListener.addNotifyListener(this);
        serviceConfigurationListener = new ReferenceConfigurationListener(this, url);
        registry.subscribe(url, this);
    }


    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // unregister.
        try {
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unregister(getRegisteredConsumerUrl());
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unregister service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
            DynamicConfiguration.getDynamicConfiguration()
                    .removeListener(ApplicationModel.getApplication(), consumerConfigurationListener);
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    // RegistryDirectory 是一个动态服务目录，会随注册中心配置的变化进行动态调整。
    // 因此 RegistryDirectory 实现了 NotifyListener 接口，通过这个接口获取注册中心变更通知
    // 本函数作用是让本地服务目录和注册中心保持一致，应该是注册中心有变化的时候，会调用本函数。
    @Override
    public synchronized void notify(List<URL> urls) {
        // 把url分类成map，
        // key分别是"configurators", "routers", "providers", ""
        // value是相应的URL对象的集合
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                // 根据url的类别，对url进行分类。
                .collect(Collectors.groupingBy(url -> {
                    if (UrlUtils.isConfigurator(url)) {
                        return CONFIGURATORS_CATEGORY;
                    } else if (UrlUtils.isRoute(url)) {
                        return ROUTERS_CATEGORY;
                    } else if (UrlUtils.isProvider(url)) {
                        return PROVIDERS_CATEGORY;
                    }
                    return "";
                }));

        // 从categoryUrls中取key="configurators"对应的value值，没有则返回空集合
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        // 将 url 转成 Configurator
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);

        // 从categoryUrls中取key="routers"对应的value值，没有则返回空集合
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        // 将 url 转成 Router
        toRouters(routerURLs).ifPresent(this::addRouters);

        // 从categoryUrls中取key="providers"对应的value值，没有则返回空集合
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        refreshOverrideAndInvoker(providerURLs);
    }


    // 入参是服务提供者url集合， 即 入参urls中的每个url对象，都满足
    // url对象的"protocol"属性值 not in("override"，"route")  且  "category"属性值是"providers"
    private void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        // 配置 overrideDirectoryUrl，即
        // 为成员变量overrideDirectoryUrl的parameters属性新增或者修改entry
        overrideDirectoryUrl();
        // 刷新 Invoker 列表
        refreshInvoker(urls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * <ol>
     * <li> If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache,
     * and notice that any parameter changes in the URL will be re-referenced.</li>
     * <li>If the incoming invoker list is not empty, it means that it is the latest invoker list.</li>
     * <li>If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route
     * rule, which needs to be re-contrasted to decide whether to re-reference.</li>
     * </ol>
     *
     * 将入参invokerURL列表转化为invoker的map，转化规则如下：
     * 1、如果url已经被转化为invoker，则直接从缓存中获取，不再重新引用，注意：若url中任何一个参数有变更都会重新引用。
     * 2、如果传入的invokerUrls列表不为空，则表示最新的invoker列表
     * 3、如果传入的invokerUrl列表是空，则表示只是下发的override规则或route规则，需要重新交叉对比，决定是否需要重新引用。
     *
     * @param invokerUrls this parameter can't be null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    // 入参是服务提供者url
    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null");

        // 入参invokerUrls 仅有一个元素，且 url 协议头为 empty，此时表示禁用所有服务
        if (invokerUrls.size() == 1
                && invokerUrls.get(0) != null
                && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            this.forbidden = true; // Forbid to access
            this.invokers = Collections.emptyList();
            routerChain.setInvokers(this.invokers);
            // 关闭当前对象中保存的所有 Invoker
            destroyAllInvokers(); // Close all invokers
        }
        // 当入参invokerUrls为空 或者 有多个元素时
        else {
            this.forbidden = false; // Allow to access
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }

            // 若invokerUrls为空 但是 cachedInvokerUrls不空， 则将cachedInvokerUrls赋给它
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 添加缓存中的 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                // 设置缓存cachedInvokerUrls
                this.cachedInvokerUrls = new HashSet<>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }

            // 若invokerUrls为空，函数返回
            // （能到这， 说明缓存cachedInvokerUrls 和 invokerUrls都为空）
            if (invokerUrls.isEmpty()) {
                return;
            }

            // 将 url 转成 Invoker
            // key是服务提供者url的串，value是该服务提供者对应的Invoker对象
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

            /**
             * If the calculation is wrong, it is not processed.
             *
             * 1. The protocol configured by the client is inconsistent with the protocol of the server.
             *    eg: consumer protocol = dubbo, provider only has other protocol services(rest).
             * 2. The registration center is not robust and pushes illegal specification data.
             *
             */
            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }

            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            routerChain.setInvokers(newInvokers);
            // 合并每个组内的Invoker（最终，每个组对应一个Invoker对象）
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // 销毁无用 Invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }


    // 将入参invokers集合中的元素 分组合并，返回合并后的Invoker对象集合（每组对应一个Invoker对象）
    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        // 结果map
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        // key是组名， value是该组内的Invoker对象
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();

        // 填充groupMap
        // 将invokers中的元素，按group值分组，添加到groupMap
        for (Invoker<T> invoker : invokers) {
            // 从invoker的url中取"group"属性值，
            String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            groupMap.get(group).add(invoker);
        }

        // 如果 groupMap 中只包含一组键值对，取出该entry的value值添加到结果list （entry的value值是个集合）
        if (groupMap.size() == 1) {
            mergedInvokers.addAll(groupMap.values().iterator().next());
        }

        /**
         * groupMap.size() > 1 成立，表示 groupMap 中包含多组键值对，比如：
         * {
         *     "dubbo": [invoker1, invoker2, invoker3, ...],
         *     "hello": [invoker4, invoker5, invoker6, ...]
         *  }
         */
        // 若关系表groupMap中的映射关系数量大于1，表示有多组服务。此时通过集群类合并每组 Invoker
        else if (groupMap.size() > 1) {

            // 将一个组对应的Invoker集合 合并成一个Invoker对象，添加到mergedInvokers中，依次遍历每个组。
            for (List<Invoker<T>> groupList : groupMap.values()) {
                // 生成一个StaticDirectory对象
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                // 设置当前对象的routerChain属性
                staticDirectory.buildRouterChain();
                // 通过集群类合并每个分组对应的 Invoker 列表
                mergedInvokers.add(cluster.join(staticDirectory));
            }
        } // groupMap大小为0
        else {
            mergedInvokers = invokers;
        }
        return mergedInvokers;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    // 使用入参 urls 构造Router对象，并返回Router对象集合
    // 其中，入参urls中的每个url对象都满足 "protocol"属性值为"route"  或者  "category"属性值为"routers"
    private Optional<List<Router>> toRouters(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }
        // 返回结果，Router对象集合
        List<Router> routers = new ArrayList<>();

        // 遍历urls，使用url的属性值构造Router对象，并将Router对象添加到routers集合中
        for (URL url : urls) {
            // 跳过protocol值为"empty"的url
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }

            // 取url对象的"router"属性值，若不为空，则重新生成一个url
            String routerType = url.getParameter(Constants.ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                // 重新生成一个url
                url = url.setProtocol(routerType);
            }

            // 创建一个ConditionRouter对象
            try {
                Router router = routerFactory.getRouter(url);
                if (!routers.contains(router)) {
                    // 添加到routers中
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * 将url集合 转成对应的Invoker集合， 若该url已经调用过refer()方法生成了Invoker对象，则不会再次调用。
     *
     * @param urls 服务提供者url集合
     * @return invokers key是服务提供者url的串，value是该服务提供者对应的Invoker对象
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        // 结果map
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<>();
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<>();
        // 获取消费端配置的协议
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);

        // 遍历urls
        // 将url转成字符串，并获取该url的invoker对象
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 检测服务提供者协议是否能支持消费端 （就是筛选出一个支持消费端协议的providerUrl对象）
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                // 消费端配置的协议
                String[] acceptProtocols = queryProtocols.split(",");
                // 先检测服务提供者url的协议属性，是否等于消费端配置的协议
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        // 支持则跳出for
                        accept = true;
                        break;
                    }
                }
                // 若服务提供者协议不支持消费端，则跳过该提供者url，换下一个提供者url
                if (!accept) {
                    continue;
                }
            }
            // 若providerUrl的protocol="empty"，则跳过
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }


            // 能到这，说明现在的这个providerUrl对象的协议支持消费端的协议。


            // 通过SPI机制，查看服务端url的协议是否有对应的clazz
            // 即，检测服务端协议是否被消费端支持，不支持则log （说明这段代码跑在消费端）
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }


            // 能到这，说明现在的这个providerUrl对象的协议支持消费端的协议。


            // 合并 url
            URL url = mergeUrl(providerUrl);
            // 将url转成串存入keys
            String key = url.toFullString(); // The parameter urls are sorted

            if (keys.contains(key)) { // Repeated url
                // 忽略重复 url
                continue;
            }
            keys.add(key);

            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            // 从缓存中取与 url串 对应的 Invoker对象
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            // 缓存未命中
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true;
                    // 给enabled赋值

                    // 若url中有"disabled"属性，则取反 disable的配置值，赋值给 enable 变量
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        // 获取 enable 配置，赋值给 enable 变量
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }

                    if (enabled) {
                        // 调用 refer 获取 Invoker对象
                        invoker = new InvokerDelegate<>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    // 将url串和 为该url新创建的invoker对象添加到map
                    newUrlInvokerMap.put(key, invoker);
                }
            } // 缓存命中
            else {
                // 将url串和 该url对应的已有的invoker对象添加到map
                newUrlInvokerMap.put(key, invoker);
            }
        }
        keys.clear();
        // 返回的map中， key是服务提供者url的串，value是该服务提供者对应的Invoker对象
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * 为入参providerUrl的parameters属性添加或修改entry
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        // 将providerUrl的parameters属性和queryMap合并，并生成一个新的providerUrl对象
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        // 为providerUrl的parameters属性新增或者修改entry，
        // 添加顺序是override > -D >Consumer > Provider
        providerUrl = overrideWithConfigurator(providerUrl);

        // providerUrl的parameters属性中添加（"check"，false）
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        // 将providerUrl的parameters和overrideDirectoryUrl的parameters合一起，并重新生成一个url返回
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        // 若providerUrl的path属性为空  且 protocol属性为"dubbo"，进if
        if ((providerUrl.getPath() == null || providerUrl.getPath()
                .length() == 0) && Constants.DUBBO_PROTOCOL.equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            // 从directoryUrl的"interface"属性值中取path
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                // 设置providerUrl的path属性
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    // 为入参providerUrl的parameters属性新增或者修改entry（使用从各个对象中取到的Configurator对象集合来搞）
    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);

        // override url with configurator from configurator from "app-name.configurators"
        providerUrl = overrideWithConfigurators(consumerConfigurationListener.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (serviceConfigurationListener != null) {
            providerUrl = overrideWithConfigurators(serviceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    // 使用入参configurators集合中的每个元素，为url的parameters属性新增或者修改entry
    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    /**
     * Close all invokers
     */
    // 将当前对象的成员变量urlInvokerMap清空，关闭其中的Invoker对象。
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    // 关闭invoker
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        invokers = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    // 销毁无用 Invoker
    // 所谓无用的Invoker，就是在oldUrlInvokerMap中存在，但在newUrlInvokerMap中没有的Invoker对象。
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            // 获取新生成的 Invoker 列表
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();

            // deleted = oldUrlInvokerMap -（newInvokers ∩ oldUrlInvokerMap）
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 检测 newInvokers 中是否包含老的Invoker
                if (!newInvokers.contains(entry.getValue())) {
                    // 若不包含，则需要删除。
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    // 若不包含，则将老的 Invoker 对应的 url 存入 deleted 列表中
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            // 遍历 deleted 集合，在老的 <url, Invoker> 映射关系表查出 Invoker，销毁该Invoker
            for (String url : deleted) {
                if (url != null) {
                    // 从 oldUrlInvokerMap 中移除 url串 对应的 Invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁 Invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    // 获取服务提供者列表
    public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 服务提供者关闭或禁用了服务，此时抛出 No provider 异常
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // Get invokers from cache, only runtime routers will be executed.
            // 为消费者url选出服务提供者集合
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }


        // FIXME Is there any need of failing back to Constants.ANY_VALUE or the first available method invokers when invokers is null?
        /*Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            String methodName = RpcUtils.getMethodName(invocation);
            invokers = localMethodInvokerMap.get(methodName);
            if (invokers == null) {
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }*/
        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    public void setRegisteredConsumerUrl(URL registeredConsumerUrl) {
        this.registeredConsumerUrl = registeredConsumerUrl;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void buildRouterChain(URL url) {
        this.setRouterChain(RouterChain.buildChain(url));
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    public List<Invoker<T>> getInvokers() {
        return invokers;
    }

    // 判断入参url的"category"属性值是否合法，是则返回true
    private boolean isValidCategory(URL url) {
        // 获取url的"category"属性值
        String category = url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);

        // category只有取以下值时，才合法，返回true

        // （若url的category属性值为"routers" 或者 protocol属性值为"route"）
        // 或者 category="providers" 或者  category="configurators" 或者  category="dynamicconfigurators"
        // 或者 category="appdynamicconfigurators" ，
        // 则返回true
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||
                PROVIDERS_CATEGORY.equals(category) ||
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    // 判断url的属性"compatible_config"是否为空， 为空返回true
    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(Constants.COMPATIBLE_CONFIG_KEY));
    }

    // 为成员变量overrideDirectoryUrl的parameters属性新增或者修改entry（使用从各个对象中取到的Configurator对象集合来搞）
    private void overrideDirectoryUrl() {
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        // 取当前对象的Configurator对象集合
        List<Configurator> localConfigurators = this.configurators; // local reference
        doOverrideUrl(localConfigurators);

        // 取consumerConfigurationListener对象的Configurator对象集合
        List<Configurator> localAppDynamicConfigurators = consumerConfigurationListener.getConfigurators(); // local reference
        doOverrideUrl(localAppDynamicConfigurators);

        // 取serviceConfigurationListener对象的Configurator对象集合
        if (serviceConfigurationListener != null) {
            List<Configurator> localDynamicConfigurators = serviceConfigurationListener.getConfigurators(); // local reference
            doOverrideUrl(localDynamicConfigurators);
        }
    }

    // 使用入参configurators集合中的每个元素，为overrideDirectoryUrl的parameters属性新增或者修改entry
    private void doOverrideUrl(List<Configurator> configurators) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        ReferenceConfigurationListener(RegistryDirectory directory, URL url) {
            this.directory = directory;
            this.url = url;
            this.initWith(url.getEncodedServiceKey() + Constants.CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + Constants.CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshInvoker(Collections.emptyList()));
        }
    }

}
