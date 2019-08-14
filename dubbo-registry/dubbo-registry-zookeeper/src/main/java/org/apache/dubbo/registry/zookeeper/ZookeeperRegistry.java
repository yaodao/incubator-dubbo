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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ZookeeperRegistry
 *
 */
// ZookeeperRegistry的工作就是通过Zookeeper API实现doRegister,doUnregister,doSubscribe,doUnsubscribe的具体逻辑
public class ZookeeperRegistry extends FailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    /**
     * 默认端口
     */
    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;

    /**
     * 默认 Zookeeper 根节点
     */
    private final static String DEFAULT_ROOT = "dubbo";

    /**
     * Zookeeper 根节点 ，默认值是 "/dubbo"
     * 如果url对象的group属性有值，则root="/{group}"， 若没有值，则默认root="/dubbo"
     *
     */
    private final String root;

    /**
     * Service 接口全名集合
     */
    private final Set<String> anyServices = new ConcurrentHashSet<>();

    /**
     * 监听器集合
     * key是消费者url， value暂时认为是 该url对应的 状态监听器 和 子节点监听器（监听zk上的目录变化）
     */
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    /**
     * Zookeeper 客户端
     */
    private final ZookeeperClient zkClient;

    // 创建定时器retryTimer
    // 给成员变量root赋值
    // 给成员变量zkClient赋值
    // 为zkClient对象的stateListeners变量添加一个元素
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            // url中没有提供host值
            throw new IllegalStateException("registry address == null");
        }

        // 获取url中的group属性值，若没有， 则group的默认值为"dubbo"
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        // group前面加 "/" , 例如 "/dubbo"
        if (!group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        // 如果url中group属性有值，则root="/{group}"， 若没有值，则默认root="/dubbo"
        this.root = group;
        zkClient = zookeeperTransporter.connect(url);
        // 添加一个StateListener对象到zkClient对象的属性stateListeners中
        // （暂时估计这个stateListeners中的对象，在zkClient连接上zk时（或者是重新连接上zk时）会被调用，从而调用recover()方法）
        //  添加 StateListener 对象。该监听器对象，在重连时，调用恢复方法。
        zkClient.addStateListener(state -> {
            if (state == StateListener.RECONNECTED) {
                try {
                    // 将之前已注册/已订阅的URL，添加到那几个失败集合中，然后取失败集合中的元素， 进行重新注册和订阅。
                    recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    /**
     * 在zk上建立节点（就是创建目录）
     * toUrlPath(url)后具体格式为 /{group}/{interfaceName}/{category}/{url.toFullString}
     *
     * 需要注意下节点的路径生成格式，也就是toUrlPath(url)方法，生成的串格式为 /{group}/{interfaceName}/{category}/{url.toFullString}，
     * group一般不配置的话为"/dubbo"，
     * interfaceName对应具体接口全名，
     * category开始就讲过，分为consumers,configuators,routers,providers
     * url.toFullString就是我们的url配置
     * 对于registry来讲category=providers
     *
     * @param url 类似 "zookeeper://zookeeper/org.apache.dubbo.test.injvmServie?notify=false&methods=test1,test2"
     */
    public void doRegister(URL url) {
        try {
            // 在zk上建立节点， 参数path最后一个"/"之前路径的才会建立对应节点
            zkClient.create(toUrlPath(url), url.getParameter(Constants.DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    // 删除url对应的zk上的节点
    public void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    /**
     * 订阅的行为对于消费者来讲，用于获取providers和routers，用于得到路由后的服务提供者
     * 对于提供者来讲，订阅configuators，通过新的配置重新暴露服务
     *
     * doSubscribe方法支持订阅全局和订阅特定接口
     * 如果interface=*，即订阅全局，对于新增和已存在的所有接口的改动都会触发回调
     * 如果interface=特定接口，那么只有这个接口的子节点改变时，才触发回调
     *
     *
     * 为url新增一个监听器listener（就是url又订阅了一个新的服务）
     * 感觉每次新增一个监听器，都要推送一遍已有的服务给这个监听器
     * 感觉针对"interface"属性值为 "*" 的订阅是递归执行， 最终都会归到对单一接口的订阅
     */
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            // 若url的"interface"属性值为 "*", 即订阅全局，对于新增和已存在的所有接口的改动都会触发回调
            if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                //  root="/dubbo"
                String root = toRootPath();
                // 从zkListeners中取入参url对应的监听器，没有则新增
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                if (listeners == null) {
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                    listeners = zkListeners.get(url);
                }
                // 从listeners中取状态监听器对应的子节点监听器，没有则新增
                ChildListener zkListener = listeners.get(listener);
                if (zkListener == null) {
                    // listeners中新增一个entry， key是NotifyListener的实例, value是ChildListener的实例
                    listeners.putIfAbsent(listener, (parentPath, currentChilds) -> {
                        // 这部分代码在这里的时候还没有触发， 这里只是整体作为ChildListener的实例 放到listeners中
                        for (String child : currentChilds) {
                            child = URL.decode(child);
                            if (!anyServices.contains(child)) {
                                anyServices.add(child);
                                // 暂时理解当anyServices中没有该child时，才会调用subscribe订阅它。
                                // 就是有新增的服务时，会订阅这个新增的服务
                                // 这里是一个listener对应多个url， 也就是一个listener（监听器）可以监听多个url？？
                                subscribe(url.setPath(child).addParameters(Constants.INTERFACE_KEY, child,
                                        Constants.CHECK_KEY, String.valueOf(false)), listener);
                            }
                        }
                    });
                    // 取出刚才加入的ChildListener实例
                    zkListener = listeners.get(listener);
                }
                zkClient.create(root, false);
                // 返回参数root下的所有的子节点
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (CollectionUtils.isNotEmpty(services)) {
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        // 订阅全量url
                        // 这里是一个listener对应多个url， 也就是一个listener（监听器）可以监听多个url？？
                        subscribe(url.setPath(service).addParameters(Constants.INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {
                // 针对单个interface的订阅逻辑
                List<URL> urls = new ArrayList<>();

                // 根据参数url得到zk上的category路径，过滤所有这些category路径下的子节点， 只取与参数url匹配的服务url，将这些服务url放到集合urls中
                // 将服务提供者urls信息,推送给订阅了该服务的监听器listener
                for (String path : toCategoriesPath(url)) {
                    //  从zkListeners中取url对应的状态监听器对应的节点监听器，没有则新增
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                        listeners = zkListeners.get(url);
                    }
                    //  从listeners中取状态监听器对应的节点监听器，没有则新增
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        // 感觉zk注册中心的树结构，一共有4层， 第一层是"/dubbo" ，第二层是 接口全名 ，第三层是"{category}" ，第四层是 具体的服务url
                        // 其中，第三层的"{category}"是 "providers","configurators","routers","consumers"
                        // parentPath就到了第三层， currentChilds就到了具体的服务url
                        listeners.putIfAbsent(listener, (parentPath, currentChilds) -> ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds)));
                        zkListener = listeners.get(listener);
                    }
                    // 创建path节点，path="/dubbo" + 接口全名 + "/" + "{category}"
                    zkClient.create(path, false);
                    // 取监听器zkListener监听的参数path下的所有的子节点
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                        // 从children中取出与消费者url匹配的url，将匹配的url做点变换，添加到urls中
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                //如果有子节点，直接进行触发一次，对应AbstractRegsitry的lookup方法
                // 将最新的服务提供者urls信息,推送给订阅了该服务的监听器listener
                notify(url, listener, urls);
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    // 移除url所拥有的单个监听器listener （感觉就是移除一个指定的监听器）
    public void doUnsubscribe(URL url, NotifyListener listener) {
        // 得到该url对应的监听器map
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            // 得到 状态监听器 对应的 子节点监听器
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                // 若url中没有指定接口名，则删除root下的一个监听器
                if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    // 移除root路径对应的监听器zkListener
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    // 若url中指定了接口名，则删除该接口下的一个监听器
                    for (String path : toCategoriesPath(url)) {
                        // 移除该接口下的一个监听器zkListener
                        // （单个接口名的所有类别下的所有的url都对应同一个监听器？？）
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }
        }
    }

    @Override
    // 返回与消费者url匹配的 服务提供者url集合
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<>();
            // 根据入参url 确定服务提供者的类别节点路径（就是确定 "/dubbo" + 接口全名 + "/" + "{category}"的具体值）
            for (String path : toCategoriesPath(url)) {
                // 返回path下的所有子节点的url串
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 返回providers集合中 与参数consumer匹配的那些url
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    // 返回"/" 或者 "/dubbo/"
    private String toRootDir() {
        if (root.equals(Constants.PATH_SEPARATOR)) {
            return root;
        }
        return root + Constants.PATH_SEPARATOR;
    }

    private String toRootPath() {
        // root是在本类的构造函数中赋值，
        // 若构造函数的参数url对象中未指定 "group" 属性值，root值为"/dubbo"
        // 若构造函数的参数url对象中指定了"group" 属性值， root值为 "/{group}"
        return root;
    }

    // 若参数url中interface属性值是"*"， 则返回 "/dubbo"
    // 若不是，则返回 "/dubbo" + 参数url中interface值，其实就是 "/dubbo" + 接口全名
    private String toServicePath(URL url) {
        // 从url中取key="interface" 对应的值, 值是一个接口的全名
        String name = url.getServiceInterface();
        if (Constants.ANY_VALUE.equals(name)) {
            // 若url中key="interface"的值是 "*"， 则返回成员变量root值
            return toRootPath();
        }
        // 返回 root + interfaceName
        return toRootDir() + URL.encode(name);
    }

    // 根据url得到它在zk上category路径名
    // 举例：  返回 "/dubbo" + 接口全名 + "/" + "{category}"
    private String[] toCategoriesPath(URL url) {
        String[] categories;
        // 若url中的"category"属性值为星号， 则categories=["providers", "consumers", "routers", "configurators"]
        if (Constants.ANY_VALUE.equals(url.getParameter(Constants.CATEGORY_KEY))) {
            categories = new String[]{Constants.PROVIDERS_CATEGORY, Constants.CONSUMERS_CATEGORY,
                    Constants.ROUTERS_CATEGORY, Constants.CONFIGURATORS_CATEGORY};
        } else {
            // categories是单个值的数组， categories=["{category}"], 或者默认值 ["providers"]
            categories = url.getParameter(Constants.CATEGORY_KEY, new String[]{Constants.DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            // paths中元素举例：  "/dubbo" + 接口全名 + "/" + "{category}"
            paths[i] = toServicePath(url) + Constants.PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    // 若参数url的category属性有值， 则返回"/dubbo" + 接口全名 + url的category属性值
    // 否则，默认返回"/dubbo" + 接口全名 + "/providers"
    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    /**
     * 返回字符串 形如， "/dubbo" + 接口全名 + url的category属性值 + "/" + "protocol://username:password@host:port?key1=value1&key2=value2"
     *
     * @param url 类似 "zookeeper://zookeeper/org.apache.dubbo.test.injvmServie?notify=false&methods=test1,test2"
     * @return
     */
    private String toUrlPath(URL url) {
        // 返回串 "/dubbo" + 接口全名 + url的category属性值 + "/" + "protocol://username:password@host:port?key1=value1&key2=value2"
        return toCategoryPath(url) + Constants.PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    // 返回providers集合中 与参数consumer匹配的那些url
    // providers中的url类似  "protocol://username:password@host:port?key1=value1&key2=value2"
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(providers)) {
            for (String provider : providers) {
                provider = URL.decode(provider);
                if (provider.contains(Constants.PROTOCOL_SEPARATOR)) {
                    // 生成一个url对象
                    URL url = URL.valueOf(provider);
                    // 若两个url匹配，则加到结果集合中
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    // 从参数providers中取出与consumer匹配的url， 将这些匹配的url的protocal属性设置为"empty"，parameters中新增一个entry（"category"，{category}）
    // 返回处理后的匹配的url集合
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        // 返回providers集合中 与参数consumer匹配的那些url
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf(Constants.PATH_SEPARATOR);
            // 取path中的category值
            String category = i < 0 ? path : path.substring(i + 1);
            // 构造一个url， protocal属性设置为"empty"， parameters中新增entry（"category"，{category}）
            URL empty = URLBuilder.from(consumer)
                    .setProtocol(Constants.EMPTY_PROTOCOL)
                    .addParameter(Constants.CATEGORY_KEY, category)
                    .build();
            urls.add(empty);
        }
        return urls;
    }

}
