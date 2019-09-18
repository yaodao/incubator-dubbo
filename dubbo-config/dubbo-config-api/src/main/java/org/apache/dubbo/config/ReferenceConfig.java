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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.RegistryAwareCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;


/**
 * ReferenceConfig
 *
 * @export
 */

/**
 * 服务消费者引用服务配置，示例如下：
 *
 * // 生成远程服务代理，可以像使用本地bean一样使用demoService
 * <dubbo:reference id="demoService" interface="com.test.dubbo.DemoService" check="false"/>
 *
 */

public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * The {@link Cluster}'s implementation with adaptive functionality, and actually it will get a {@link Cluster}'s
     * specific implementation who is wrapped with <b>MockClusterInvoker</b>
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * The url of the reference service
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The interface name of the reference service
     */
    private String interfaceName;

    /**
     * The interface class of the reference service
     */
    private Class<?> interfaceClass;

    /**
     * client type
     */
    private String client;

    /**
     * The url for peer-to-peer invocation
     */
    private String url;

    /**
     * The method configs
     */
    private List<MethodConfig> methods;

    /**
     * The consumer config (default)
     */
    private ConsumerConfig consumer;

    /**
     * Only the service provider of the specified protocol is invoked, and other protocols are ignored.
     */
    private String protocol;

    /**
     * The interface proxy reference
     */
    // 接口的代理类对象
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     * 用于标识ReferenceConfig是否已经初始化
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
        setMethods(MethodConfig.constructMethodConfig(reference.methods()));
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     *
     * 在当前类的对象被创建之后、在当前类的成员变量被其他类使用之前。应该立即调用本方法，
     * 本方法会检验当前类中每个config对象是否正确，并按需要覆盖config对象的属性
     */
    public void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        completeCompoundConfigs();
        startConfigCenter();
        // get consumer's global configuration
        checkDefault();
        this.refresh();
        // 若当前对象的generic属性为空，则给它赋值
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        // 若当前对象的generic属性为"true"/"nativejava"/"bean" ，则给interfaceClass赋值
        if (ProtocolUtils.isGeneric(getGeneric())) {
            interfaceClass = GenericService.class;
        } else {
            try {
                // 加载interfaceName指定的clazz
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 判断methods中的每一个元素，都是interfaceClass中的方法
            checkInterfaceAndMethods(interfaceClass, methods);
        }
        resolveFile();
        checkApplication();
        checkMetadataReport();
    }

    public synchronized T get() {
        checkAndUpdateSubConfigs();

        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        if (ref == null) {
            // init 方法主要用于处理配置，以及调用 createProxy 生成代理类对象ref
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected error occured when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    private void init() {
        // 避免重复初始化
        if (initialized) {
            return;
        }
        initialized = true;
        checkStubAndLocal(interfaceClass);
        checkMock(interfaceClass);
        Map<String, String> map = new HashMap<String, String>();

        // map中添加("side"， "consumer")
        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);

        // 在map中添加一些项目运行时的参数，例如：协议版本信息、时间戳和进程号等信息
        appendRuntimeParameters(map);
        // 非泛化服务
        if (!isGeneric()) {
            // 获取interfaceClass所在jar包版本号
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                // map中添加("revision"， {revision})
                map.put(Constants.REVISION_KEY, revision);
            }

            // 获取接口方法列表，并添加到 map 中
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                // 若接口interfaceClass中没有方法，则map中添加（"methods"，"*"）
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                // map中添加（"methods"，"public void fun1(),public int fun2(),public String fun3()"）
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), Constants.COMMA_SEPARATOR));
            }
        }
        // map中添加（"interface"， "{interfaceName}"）
        map.put(Constants.INTERFACE_KEY, interfaceName);
        // 通过反射将 ApplicationConfig、ConsumerConfig、ReferenceConfig 等对象的字段信息添加到 map 中
        appendParameters(map, metrics);
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, this);
        Map<String, Object> attributes = null;

        // 这段代码用于检测 <dubbo:method> 标签中的配置信息，并将相关配置添加到 map 中。
        // MethodConfig对象中存储了 <dubbo:method> 标签的配置信息，methods是MethodConfig对象的集合
        if (CollectionUtils.isNotEmpty(methods)) {
            attributes = new HashMap<String, Object>();
            for (MethodConfig methodConfig : methods) {
                // 把方法的配置加入map
                // 比如 <dubbo:method name="sayHello" retries="2"> 对应的 MethodConfig，
                // 存储到map中，map = {"sayHello.retries": 2}，可以看出 key = 方法名.属性名
                appendParameters(map, methodConfig, methodConfig.getName());

                // 检测 MethodConfig对象的 retry属性值 是否为 false，若是，则设置重试次数为0， 将（"sayHello.retries", "0"）放到map中
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    // 移除 retryKey = "sayHello.retry" ，注意： 这里是"retry" 不是上面的 "retries"
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                // 一个方法名 对应 一个AsyncMethodInfo对象
                attributes.put(methodConfig.getName(), convertMethodConfig2AyncInfo(methodConfig));
            }
        }

        // 取key="DUBBO_IP_TO_REGISTRY" 对应的配置值，先从系统环境中取，再从java环境中取。
        // 获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        }
        // map中添加（"register.ip","{hostToRegistry}"）
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

        // 创建代理类
        ref = createProxy(map);

        // 根据服务名，ReferenceConfig，代理类构建 ConsumerModel，
        // 并将 ConsumerModel 存入到 ApplicationModel 中
        String serviceKey = URL.buildKey(interfaceName, group, version);
        ApplicationModel.initConsumerModel(serviceKey, buildConsumerModel(serviceKey, attributes));
    }

    private ConsumerModel buildConsumerModel(String serviceKey, Map<String, Object> attributes) {
        Method[] methods = interfaceClass.getMethods();
        Class serviceInterface = interfaceClass;
        if (interfaceClass == GenericService.class) {
            try {
                serviceInterface = Class.forName(interfaceName);
                methods = serviceInterface.getMethods();
            } catch (ClassNotFoundException e) {
                methods = interfaceClass.getMethods();
            }
        }
        // 根据服务名，ReferenceConfig对象的属性，代理类构建 ConsumerModel
        return new ConsumerModel(serviceKey, serviceInterface, ref, methods, attributes);
    }
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    // 入参map是消费者url对象的parameters属性
    private T createProxy(Map<String, String> map) {
        // 检查是否为本地调用
        if (shouldJvmRefer(map)) {
            // 生成本地引用 URL，协议为 injvm
            URL url = new URL(Constants.LOCAL_PROTOCOL, Constants.LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            // 构建 InjvmInvoker 实例（这里使用InjvmProtocol对象的refer方法）
            invoker = refprotocol.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            // url 不为空，表明用户可能想进行点对点调用
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 将url串用分号隔成数组
                String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            // 将url的path设置为服务的接口全名
                            // registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample
                            url = url.setPath(interfaceName);
                        }
                        // 检测 url 协议是否为 registry，若是，表明用户想使用指定的注册中心
                        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                            // 将 map 转换为查询字符串，并作为 key="refer"的参数值添加到 url 中
                            urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            // 合并 url，移除服务提供者的一些配置（这些配置来源于用户配置的 url 属性），
                            // 比如线程池相关配置。并保留服务提供者的部分配置，比如版本，group，时间戳等
                            // 最后将合并后的配置设置为 url 的parameters属性。
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } // url 为空，需要自己找注册中心
            else { // assemble URL from register center's configuration
                // 检查注册配置是否存在，然后将其转换为RegistryConfig
                checkRegistry();
                // 加载注册中心 url
                List<URL> us = loadRegistries(false);
                // 将注册中心url进行修改后，添加到urls
                if (CollectionUtils.isNotEmpty(us)) {
                    for (URL u : us) {
                        URL monitorUrl = loadMonitor(u);
                        if (monitorUrl != null) {
                            // map中添加（"monitor"，monitorUrl对应的字符串）
                            map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                        }
                        // 添加 key="refer" 参数到 url 中，并将 url 添加到 urls 中
                        urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    }
                }
                // 未配置注册中心，抛出异常
                if (urls.isEmpty()) {
                    throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                }
            }

            // 单个注册中心或服务提供者(服务直连，下同)
            if (urls.size() == 1) {
                // 构建 Invoker 实例（ 调用 RegistryProtocol 的 refer 函数）
                invoker = refprotocol.refer(interfaceClass, urls.get(0));
            }
            // 若 urls 元素数量大于1，即存在多个注册中心或服务直连 url
            // 此时先根据 url 构建 Invoker。然后再通过 Cluster 合并多个 Invoker
            else {
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                // 获取所有的 Invoker
                for (URL url : urls) {
                    // 通过 refprotocol 调用 refer 构建 Invoker，refprotocol 会在运行时
                    // 根据 url 协议头加载指定的 Protocol 实例，并调用该实例的 refer 方法
                    invokers.add(refprotocol.refer(interfaceClass, url));
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        registryURL = url; // use last registry url
                    }
                }
                if (registryURL != null) { // registry url is available
                    // use RegistryAwareCluster only when register's cluster is available
                    // 如果注册中心链接不为空，则将使用 AvailableCluster
                    URL u = registryURL.addParameter(Constants.CLUSTER_KEY, RegistryAwareCluster.NAME);
                    // The invoker wrap relation would be: RegistryAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, will execute route) -> Invoker
                    // 创建 StaticDirectory 实例，并由 Cluster 对多个 Invoker 进行合并
                    invoker = cluster.join(new StaticDirectory(u, invokers));
                } else { // not a registry url, must be direct invoke.
                    invoker = cluster.join(new StaticDirectory(invokers));
                }
            }
        }

        // invoker 可用性检查
        if (shouldCheck() && !invoker.isAvailable()) {
            // make it possible for consumer to retry later if provider is temporarily unavailable
            initialized = false;
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        /**
         * @since 2.7.0
         * ServiceData Store
         */
        MetadataReportService metadataReportService = null;
        if ((metadataReportService = getMetadataReportService()) != null) {
            URL consumerURL = new URL(Constants.CONSUMER_PROTOCOL, map.remove(Constants.REGISTER_IP_KEY), 0, map.get(Constants.INTERFACE_KEY), map);
            metadataReportService.publishConsumer(consumerURL);
        }

        // Invoker 创建完毕后，接下来要做的事情是为服务接口生成代理对象。有了代理对象，即可进行远程调用。
        // 代理对象生成的入口方法为 ProxyFactory 的 getProxy

        // create service proxy
        // 调用proxyFactory创建服务代理
        return (T) proxyFactory.getProxy(invoker);
    }

    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */
    /**
     * 1.如果指定了当前对象的injvm属性值，则返回该属性值
     * 2.如果当前对象的url属性不空，则是远程调用
     * 3.如果指定了scope属性值，则根据scope属性来判断（入参map中有scope属性）
     * 4.如果没有指定scope属性值，但当前JVM中有目标服务，则优先进行本地调用，这是默认行为
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        // 没有指定injvm属性值
        if (isInjvm() == null) {
            // if a url is specified, don't do local reference
            // 若url不空，则不是本地调用
            if (url != null && url.length() > 0) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                // 判断tmpUrl是否为本地服务（实际是根据tmpUrl的parameters集合中的值进行判断）
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    protected boolean shouldCheck() {
        Boolean shouldCheck = isCheck();
        if (shouldCheck == null && getConsumer()!= null) {
            shouldCheck = getConsumer().isCheck();
        }
        if (shouldCheck == null) {
            // default true
            shouldCheck = true;
        }
        return shouldCheck;
    }

    protected boolean shouldInit() {
        Boolean shouldInit = isInit();
        if (shouldInit == null && getConsumer() != null) {
            shouldInit = getConsumer().isInit();
        }
        if (shouldInit == null) {
            // default is false
            return false;
        }
        return shouldInit;
    }

    private void checkDefault() {
        createConsumerIfAbsent();
    }

    // 若当前对象的consumer属性为空，则新建一个赋值给它
    private void createConsumerIfAbsent() {
        // consumer不为空，则直接返回
        if (consumer != null) {
            return;
        }
        setConsumer(
                        // 先从ConfigManager对象中取默认的ConsumerConfig对象，若没有，则生成一个新的对象返回。
                        ConfigManager.getInstance()
                            .getDefaultConsumer()
                            .orElseGet(() -> {
                                ConsumerConfig consumerConfig = new ConsumerConfig();
                                consumerConfig.refresh();
                                return consumerConfig;
                            })
                );
    }

    // 给当前对象的属性赋值，
    // 数据来源的优先级是consumer>module>application
    private void completeCompoundConfigs() {
        // 若当前对象的consumer属性不空，当以下属性为空时，则取consumer中的值赋给这些属性
        if (consumer != null) {
            if (application == null) {
                setApplication(consumer.getApplication());
            }
            if (module == null) {
                setModule(consumer.getModule());
            }
            if (registries == null) {
                setRegistries(consumer.getRegistries());
            }
            if (monitor == null) {
                setMonitor(consumer.getMonitor());
            }
        }
        // 若当前对象的module属性不空，当以下属性为空时，则取module中的值赋给这些属性
        if (module != null) {
            if (registries == null) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        // 若当前对象的application属性不空，当以下属性为空时，则取application中的值赋给这些属性
        if (application != null) {
            if (registries == null) {
                setRegistries(application.getRegistries());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, ClassHelper.getClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName(Constants.CLIENT_KEY, client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        // 为ConfigManager对象的consumers集合添加一个consumer
        ConfigManager.getInstance().addConsumer(consumer);
        // 给当前对象的consumer属性赋值
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return Constants.DUBBO + ".reference." + interfaceName;
    }

    // 给当前对象的url属性赋值（先从系统变量中取值，再从配置文件中取值）
    private void resolveFile() {
        // 从系统变量中取interfaceName对应的值
        String resolve = System.getProperty(interfaceName);
        String resolveFile = null;
        // 若没取到
        if (StringUtils.isEmpty(resolve)) {
            // 获取文件名和路径名
            resolveFile = System.getProperty("dubbo.resolve.file");
            if (StringUtils.isEmpty(resolveFile)) {
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    resolveFile = userResolveFile.getAbsolutePath();
                }
            }
            // 读取文件内容
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                try (FileInputStream fis = new FileInputStream(new File(resolveFile))) {
                    properties.load(fis);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to load " + resolveFile + ", cause: " + e.getMessage(), e);
                }
                // 从文件中取interfaceName对应的值
                resolve = properties.getProperty(interfaceName);
            }
        }
        // 将上面得到的resolve值赋给当前对象的url
        if (resolve != null && resolve.length() > 0) {
            url = resolve;
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }
    }
}
