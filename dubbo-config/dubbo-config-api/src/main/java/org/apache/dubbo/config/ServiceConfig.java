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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.Constants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 * 暂时认为本类对象代表一个对外暴露的服务
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

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
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    // 获取一个动态生成的ProxyFactory$Adaptive类（因为ProxyFactory接口没有使用@Adaptive标注的实现类，所以会动态生成一个$Adaptive类）
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    /**
     * The urls of the services exported
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    /**
     * The interface name of the exported service
     */
    // 要导出服务的接口名
    private String interfaceName;

    /**
     * The interface class of the exported service
     * 对外暴露服务的接口
     */
    private Class<?> interfaceClass;

    /**
     * The reference of the interface implementation
     * 对外暴露服务的接口的实现类 的对象
     */
    private T ref;

    /**
     * The service name
     * 对外暴露的服务名
     */
    private String path;

    /**
     * The method configuration
     */
    private List<MethodConfig> methods;

    /**
     * The provider configuration
     */
    private ProviderConfig provider;

    /**
     * The providerIds
     */
    private String providerIds;

    /**
     * Whether the provider has been exported
     */
    // 是否已经导出
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    // 是不是已被取消导出
    private transient volatile boolean unexported;

    /**
     * whether it is a GenericService
     */
    private volatile String generic;

    public ServiceConfig() {
    }

    // 设置当前对象的成员变量值
    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
        // 设置this.methods值
        // 取service注解对象中的methods()值, 将其转成List<MethodConfig> 再赋给this.methods
        setMethods(MethodConfig.constructMethodConfig(service.methods()));
    }

    @Deprecated
    // ProviderConfig对象列表转为ProtocolConfig对象列表
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (CollectionUtils.isEmpty(providers)) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            // 单个ProviderConfig对象转为单个ProtocolConfig对象
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    // ProtocolConfig对象列表转为ProviderConfig对象列表
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (CollectionUtils.isEmpty(protocols)) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    // ProviderConfig对象转为ProtocolConfig对象
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    // ProtocolConfig对象转为ProviderConfig对象
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    // 从成员变量RANDOM_PORT_MAP中取参数protocol对应的值
    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    // 将(protocol, port) 存入 成员变量RANDOM_PORT_MAP
    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    // 取urls列表中的一个元素
    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly on global configs
        completeCompoundConfigs();
        // Config Center should always being started first.
        startConfigCenter();
        checkDefault();
        checkApplication();
        checkRegistry();
        checkProtocol();
        this.refresh();
        checkMetadataReport();

        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 若 ref 是泛化服务类型
        if (ref instanceof GenericService) {
            // 设置 interfaceClass 为 GenericService.class
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                // 设置 generic = "true"
                generic = Boolean.TRUE.toString();
            }
        } else {
            // ref 非 GenericService 类型
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 对 interfaceClass，以及 <dubbo:method> 标签中的必要字段进行检查
            checkInterfaceAndMethods(interfaceClass, methods);
            // ref是否为interfaceClass的子类
            checkRef();
            // 设置 generic = "false"
            generic = Boolean.FALSE.toString();
        }

        // local 和 stub 在功能应该是一致的，用于配置本地存根
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // interfaceClass若不是localClass的父类，则抛出异常
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // interfaceClass若不是stubClass的父类，则抛出异常
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 检验成员变量local、stub 与 入参interfaceClass之间的关系
        checkStubAndLocal(interfaceClass);
        // 检验成员变量mock的值是否合法
        checkMock(interfaceClass);
    }

    public synchronized void export() {
        checkAndUpdateSubConfigs();

        // 如果当前对象的成员变量export值为false，则不导出服务
        if (!shouldExport()) {
            return;
        }

        // 如果当前对象的成员变量delay > 0，则延时导出服务（延迟注册服务）
        if (shouldDelay()) {
            /**
             * 这句代码等价于
             *       delayExportExecutor.schedule(new Runnable() {
             *             @Override
             *             public void run() {
             *                 doExport();
             *             }
             *         }, delay, TimeUnit.MILLISECONDS);
             *
             */
            delayExportExecutor.schedule(this::doExport, delay, TimeUnit.MILLISECONDS);
        } else {
            doExport();
        }
    }

    private boolean shouldExport() {
        // 是否导出服务
        Boolean shouldExport = getExport();
        if (shouldExport == null && provider != null) {
            shouldExport = provider.getExport();
        }

        // default value is true
        // 默认为true（导出）
        if (shouldExport == null) {
            return true;
        }

        return shouldExport;
    }

    // 是否延迟
    // 若成员变量delay>0， 则返回true
    // 其他情况返回false
    private boolean shouldDelay() {
        Integer delay = getDelay();
        if (delay == null && provider != null) {
            delay = provider.getDelay();
        }
        return delay != null && delay > 0;
    }

    protected synchronized void doExport() {
        if (unexported) {
            // 已被取消导出
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            // 已导出
            return;
        }
        exported = true;

        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        doExportUrls();
    }

    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        // 若interfaceClass不是ref的父类或接口，抛出异常
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occured when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        // 加载注册中心链接
        List<URL> registryURLs = loadRegistries(true);
        // 遍历 protocols，并在每个协议下导出服务
        for (ProtocolConfig protocolConfig : protocols) {
            // 先用protocolConfig得到pathKey，再用pathKey得到ProviderModel对象，之后将（pathKey，providerModel）放到ApplicationModel中

            // getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path) ,这句代码返回{contextpath}/{path}  或者 {path}
            // pathKey= "{group}/{path}:{version}"
            String pathKey = URL.buildKey(getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), group, version);
            // 生成一个ProviderModel对象，并给它的成员变量赋值
            ProviderModel providerModel = new ProviderModel(pathKey, ref, interfaceClass);
            // 将providerModel添加到ApplicationModel中
            ApplicationModel.initProviderModel(pathKey, providerModel);

            //  URL 组装的过程
            // 遍历 protocols，并在每个协议下导出服务，并在导出服务的过程中，将服务注册到注册中心。
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    // 在每个协议下导出服务，并在导出服务的过程中，将服务注册到注册中心。
    // 入参registryURLs是注册中心的url对象集合
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        // 如果协议名为空，或空串，则将协议名变量设置为 dubbo
        if (StringUtils.isEmpty(name)) {
            name = Constants.DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        // 添加 side、版本、时间戳以及进程号等信息到 map 中
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE); // 新增entry("side"， "provider")
        appendRuntimeParameters(map);

        // 通过反射将各个对象的字段信息添加到 map 中
        appendParameters(map, metrics);
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);

        // 这段代码用于检测 <dubbo:method> 标签中的配置信息，并将相关配置添加到 map 中。
        // MethodConfig对象中存储了 <dubbo:method> 标签的配置信息，methods是MethodConfig对象的集合
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig method : methods) {
                // 添加 MethodConfig 对象的字段信息到 map 中。
                // 比如 <dubbo:method name="sayHello" retries="2"> 对应的 MethodConfig，
                // 存储到map中，map = {"sayHello.retries": 2}，可以看出 key = 方法名.属性名
                appendParameters(map, method, method.getName());

                // 检测 MethodConfig对象的 retry属性值 是否为 false，若是，则设置重试次数为0， 将（"sayHello.retries", "0"）放到map中
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    // 移除 retryKey = "sayHello.retry" ，注意： 这里是"retry" 不是上面的 "retries"
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }

                // 获取单个MethodConfig对象的 ArgumentConfig 列表
                List<ArgumentConfig> arguments = method.getArguments();

                // 以下就是把ArgumentConfig对象列表中， 每个ArgumentConfig对象含有的字段值添加到map中
                // ArgumentConfig是代表的参数对象，所以需要先找到这个参数所属的方法， 之后使用方法名来标识下该参数后， 再放到map中
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        // 若 ArgumentConfig对象的type属性有值
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            // 接口的所有方法
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            // 遍历方法，找到名字和MethodConfig对象的name属性值相同的 方法对象
                            // （其实就是找MethodConfig对象所代表的那个方法）
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    // 找到了 （方法名和MethodConfig对象的name属性值相同）
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        // ArgumentConfig对象设置了index属性值
                                        if (argument.getIndex() != -1) {
                                            // 检测 argument对象中的 type属性值 与方法的参数列表中的参数名称是否一致，不一致则抛出异常
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                // 添加 argument对象的 字段信息到 map 中，
                                                // key的前缀 = 方法名.index，比如: map = {"sayHello.3.type": "java.lang.String"}, 其中"type"是argument对象的属性名
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            // 在方法的参数列表中，找与argument对象的type值相同的参数
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    // 添加 argument对象的 字段信息到 map 中，
                                                    // key的前缀 = 方法名.j，比如: map = {"sayHello.3.type": "java.lang.String"}, 其中"type"是argument对象的属性名
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }// 用户未配置 type 属性，但配置了 index 属性，且 index != -1 （即， ArgumentConfig对象的index属性有值）
                        else if (argument.getIndex() != -1) {
                            // 添加 argument对象的 字段信息到 map 中，
                            // key的前缀 = 方法名.index，比如: map = {"sayHello.3": true}
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // 查看generic值，并根据查看结果向 map 中添加不同的信息
        if (ProtocolUtils.isGeneric(generic)) {
            // 添加("generic"，{generic})
            map.put(Constants.GENERIC_KEY, generic);
            // 添加("methods"，"*")
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                // 添加("revision", {revision})
                map.put(Constants.REVISION_KEY, revision);
            }
            // 为接口生成包装类 Wrapper，Wrapper 中包含了接口的详细信息，比如接口方法名数组，字段信息等
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                // 将逗号作为分隔符连接方法名，并将连接后的字符串放入 map 中
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        // 添加 token 到 map 中
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                // 随机生成 token
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }
        // export service
        // 获取 host 和 port
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        Integer port = this.findConfigedPorts(protocolConfig, name, map);

        // 组装 URL
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        /**
         * 前置工作做完，接下来就可以进行服务导出了，服务导出分为导出到本地 (JVM)，和导出到远程
         *
         * hehe
         */

        // 如果url使用的协议存在扩展，则调用对应的扩展来修改原url
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            // 加载 ConfiguratorFactory，并生成 Configurator 实例，然后通过实例配置 url
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        // 取url的"scope"属性值，可以理解为导出范围
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // don't export when none is configured
        // 若scope != "none" 则进入if, 若scope = none，则什么都不做，直接结束
        // 配置为none不暴露
        if (!Constants.SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            // scope != remote，则导出到本地
            if (!Constants.SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            // scope != local，则暴露为远程服务
            if (!Constants.SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    for (URL registryURL : registryURLs) {
                        // url的parameters中添加"dynamic"参数
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        // 加载监视器链接
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            // 将监视器链接作为参数添加到 url 中
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }

                        // 通过代理工厂将ref对象转化成invoker对象
                        // 为服务提供类(ref)生成 Invoker
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        // DelegateProviderMetaDataInvoker 用于持有 Invoker 和 ServiceConfig
                        // 代理invoker对象
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        // 导出服务，并生成 Exporter （调用RegistryProtocol的export方法 ）
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        // 一个服务可能有多个提供者，保存在一起
                        exporters.add(exporter);
                    }
                }  // 不存在注册中心，仅导出服务
                else {
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
                /**
                 * @since 2.7.0
                 * ServiceData Store
                 */
                MetadataReportService metadataReportService = null;
                if ((metadataReportService = getMetadataReportService()) != null) {
                    metadataReportService.publishProvider(url);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        // 如果 URL 的协议头等于 "injvm"，说明已经导出到本地了，无需再次导出， 若协议头不等于"injvm"，则进入if
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            // 设置url的protocol="injvm"， host="127.0.0.1"，port=0
            URL local = URLBuilder.from(url)
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(LOCALHOST_VALUE)
                    .setPort(0)
                    .build();
            // 仅仅创建了一个 InjvmExporter，无其他逻辑。（这里的 protocol 会在运行时调用 InjvmProtocol类 的 export 方法）
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }
    // 返回contextpath值
    // 先取参数protocolConfig对象的contextpath属性值
    // 若为空则取ProviderConfig对象的contextpath属性值
    private Optional<String> getContextPath(ProtocolConfig protocolConfig) {
        String contextPath = protocolConfig.getContextpath();
        if (StringUtils.isEmpty(contextPath) && provider != null) {
            contextPath = provider.getContextpath();
        }
        return Optional.ofNullable(contextPath);
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    // 从系统环境变量中 或者 入参registryURLs中取一个ip地址 返回
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        // 从系统环境变量中取key="DUBBO_IP_TO_BIND"对应的配置值
        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);

        // if bind ip is not found in environment, keep looking up
        // 若系统环境变量中没找到，继续找
        if (StringUtils.isEmpty(hostToBind)) {
            // 从入参protocolConfig对象中取host
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                // 从成员变量provider中取host
                hostToBind = provider.getHost();
            }

            if (StringUtils.isEmpty(hostToBind)) {
                anyhost = true;
                // 取本机的ip地址
                hostToBind = getLocalHost();

                if (StringUtils.isEmpty(hostToBind)) {
                    // 从registryURLs中找有效的ip返回
                    hostToBind = findHostToBindByConnectRegistries(registryURLs);
                }
            }
        }

        // 到这里，终于找到个ip
        // key="bind.ip" value是ip地址
        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        // 从系统环境变量中取key="DUBBO_IP_TO_REGISTRY"对应的配置值
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            // 若系统环境变量取不到值，则默认值取hostToBind
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    // 从入参registryURLs中找有效的ip返回
    // 即，socket能连接上哪个ip，就返回哪个ip
    private String findHostToBindByConnectRegistries(List<URL> registryURLs) {
        if (CollectionUtils.isNotEmpty(registryURLs)) {
            for (URL registryURL : registryURLs) {
                // 若url的"registry"属性值是"multicast"，则跳过
                if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter(Constants.REGISTRY_KEY))) {
                    // skip multicast registry since we cannot connect to it via Socket
                    continue;
                }
                // socket能连接上哪个ip，就返回哪个ip
                try (Socket socket = new Socket()) {
                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                    socket.connect(addr, 1000);
                    return socket.getLocalAddress().getHostAddress();
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        return null;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    // 查找端口号
    // 查找顺序是 系统环境 -> @SPI对应的实现类 -> 随机生成
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        // 从系统环境变量中取key="DUBBO_PORT_TO_BIND"对应的配置值
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        // 如果从环境中取不到端口号，则继续从别处取
        if (portToBind == null) {
            // 从protocolConfig中取端口号
            portToBind = protocolConfig.getPort();
            // 从ProviderConfig对象中取port
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            // 入参name="dubbo"时， defaultPort=20880
            // 从Protocol的实现类中，取默认的端口号（从别名为name的实现类中取）
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
                // 从缓存中取该protocol的端口号
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    // 缓存没有，则任意获取一个可用的添加进去
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // 到这portToBind肯定是赋上值了。

        // save bind port, used as url's key later
        // 新增entry（"bind.port"，port）添加到入参map
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        // 从系统环境变量中取key="DUBBO_PORT_TO_REGISTRY"对应的配置值
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    // 把入参configPort从String转int
    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                // 检验port是否有效
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    // 分别用入参 key 和 前缀加key 来取系统环境变量的值（其中，前缀是入参protocolConfig的name属性值）
    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        // 举例: protocolPrefix="DUBBO_"
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        //  参数为： "DUBBO_{key}"， 即 加了前缀的key， 从环境中取值
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(port)) {
            // 参数key不加前缀，再取一次配置
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    private void completeCompoundConfigs() {
        // 下面几个 if 语句用于检测当前对象中 application、module 等属性（核心配置类对象）是否为空，
        // 若为空，则尝试从其他配置类对象中获取相应的属性值。
        // 使用其他配置类对象的顺序是 provider>module>application， 当前一个对象中没有对应的属性值，才使用后一个对象的属性值。

        if (provider != null) {
            if (application == null) {
                // 设置当前对象的application属性值
                setApplication(provider.getApplication());
            }
            if (module == null) {
                // 设置当前对象的module属性值
                setModule(provider.getModule());
            }
            if (registries == null) {
                // 设置当前对象的registries属性值
                setRegistries(provider.getRegistries());
            }
            if (monitor == null) {
                // 设置当前对象的monitor属性值
                setMonitor(provider.getMonitor());
            }
            if (protocols == null) {
                // 设置当前对象的protocols属性值
                setProtocols(provider.getProtocols());
            }
            if (configCenter == null) {
                // 设置当前对象的configCenter属性值
                setConfigCenter(provider.getConfigCenter());
            }
        }
        // module不为空, 则用module中的值给registries和monitor赋值
        if (module != null) {
            if (registries == null) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        // application不为空, 则用application中的值给registries和monitor赋值
        if (application != null) {
            if (registries == null) {
                setRegistries(application.getRegistries());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    // 为当前对象的provider属性赋值
    private void checkDefault() {
        createProviderIfAbsent();
    }

    // 为当前对象的provider属性赋值
    // 从ConfigManager对象中取一个 或者 新建一个
    private void createProviderIfAbsent() {
        if (provider != null) {
            return;
        }

        // 先从ConfigManager对象的providers集合中取默认值，若默认值为空，则new一个ProviderConfig对象
        setProvider (
                ConfigManager.getInstance()
                        .getDefaultProvider()
                        .orElseGet(() -> {
                            ProviderConfig providerConfig = new ProviderConfig();
                            providerConfig.refresh();
                            return providerConfig;
                        })
        );
    }

    private void checkProtocol() {
        if (CollectionUtils.isEmpty(protocols) && provider != null) {
            // 为ConfigManager对象的成员变量protocols 和 当前对象的成员变量protocols 设置值
            setProtocols(provider.getProtocols());
        }
        convertProtocolIdsToProtocols();
    }

    // 本函数的目的是 给ConfigManager对象和当前对象的成员变量protocols赋值，
    // 使用成员变量protocolIds中的元素，生成ProtocolConfig对象的集合，将该集合赋值给ConfigManager对象和当前对象的成员变量protocols
    private void convertProtocolIdsToProtocols() {
        if (StringUtils.isEmpty(protocolIds) && CollectionUtils.isEmpty(protocols)) {
            List<String> configedProtocols = new ArrayList<>();
            // 把externalConfigurationMap中带前缀"dubbo.protocols."的key，去掉前缀， 加入到set中
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getExternalConfigurationMap(), Constants.PROTOCOLS_SUFFIX));
            // 把appExternalConfigurationMap中带前缀"dubbo.protocols."的key，去掉前缀， 加入到set中
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getAppExternalConfigurationMap(), Constants.PROTOCOLS_SUFFIX));

            // set中的元素用逗号连接
            protocolIds = String.join(",", configedProtocols);
        }

        if (StringUtils.isEmpty(protocolIds)) {
            if (CollectionUtils.isEmpty(protocols)) {
               setProtocols(
                       ConfigManager.getInstance().getDefaultProtocols()
                        .filter(CollectionUtils::isNotEmpty)
                        .orElseGet(() -> {
                            ProtocolConfig protocolConfig = new ProtocolConfig();
                            protocolConfig.refresh();
                            return new ArrayList<>(Arrays.asList(protocolConfig));
                        })
               );
            }
        } else {
            String[] arr = Constants.COMMA_SPLIT_PATTERN.split(protocolIds);
            List<ProtocolConfig> tmpProtocols = CollectionUtils.isNotEmpty(protocols) ? protocols : new ArrayList<>();
            Arrays.stream(arr).forEach(id -> {
                if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                    tmpProtocols.add(ConfigManager.getInstance().getProtocol(id).orElseGet(() -> {
                        ProtocolConfig protocolConfig = new ProtocolConfig();
                        protocolConfig.setId(id);
                        protocolConfig.refresh();
                        return protocolConfig;
                    }));
                }
            });
            if (tmpProtocols.size() > arr.length) {
                throw new IllegalStateException("Too much protocols found, the protocols comply to this service are :" + protocolIds + " but got " + protocols
                        .size() + " registries!");
            }
            // 给ConfigManager对象和当前对象的成员变量protocols赋值
            setProtocols(tmpProtocols);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
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
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    // 给ConfigManager对象的providers属性添加entry
    // 给当前对象的provider属性赋值
    public void setProvider(ProviderConfig provider) {
        ConfigManager.getInstance().addProvider(provider);
        this.provider = provider;
    }

    @Parameter(excluded = true)
    public String getProviderIds() {
        return providerIds;
    }

    public void setProviderIds(String providerIds) {
        this.providerIds = providerIds;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    @Override
    public void setMock(Boolean mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    @Override
    public void setMock(String mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return Constants.DUBBO + ".service." + interfaceName;
    }
}
