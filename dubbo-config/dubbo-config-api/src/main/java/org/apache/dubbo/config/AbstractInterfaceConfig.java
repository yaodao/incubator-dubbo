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
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * AbstractDefaultConfig
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    /**
     * Local impl class name for the service interface
     */
    protected String local;

    /**
     * Local stub class name for the service interface
     */
    protected String stub;

    /**
     * Service monitor
     */
    protected MonitorConfig monitor;

    /**
     * Strategies for generating dynamic agents，there are two strategies can be choosed: jdk and javassist
     */
    protected String proxy;

    /**
     * Cluster type
     */
    protected String cluster;

    /**
     * The {@link Filter} when the provider side exposed a service or the customer side references a remote service used,
     * if there are more than one, you can use commas to separate them
     */
    protected String filter;

    /**
     * The Listener when the provider side exposes a service or the customer side references a remote service used
     * if there are more than one, you can use commas to separate them
     */
    protected String listener;

    /**
     * The owner of the service providers
     */
    protected String owner;

    /**
     * Connection limits, 0 means shared connection, otherwise it defines the connections delegated to the current service
     */
    protected Integer connections;

    /**
     * The layer of service providers
     */
    protected String layer;

    /**
     * The application info
     */
    protected ApplicationConfig application;

    /**
     * The module info
     */
    protected ModuleConfig module;

    /**
     * Registry centers
     */
    // 注册中心对象集合
    protected List<RegistryConfig> registries;

    /**
     * 举例：
     * 同一Zookeeper，分成多组注册中心:
     * <dubbo:registry id="chinaRegistry" protocol="zookeeper" address="10.20.153.10:2181" group="china" />
     * <dubbo:registry id="intlRegistry" protocol="zookeeper" address="10.20.153.10:2181" group="intl" />
     *
     * 这里的registryIds就是多个id的连接串
     */
    protected String registryIds;

    // connection events
    protected String onconnect;

    /**
     * Disconnection events
     */
    protected String ondisconnect;

    /**
     * The metrics configuration
     */
    protected MetricsConfig metrics;
    protected MetadataReportConfig metadataReportConfig;

    // 配置中心
    protected ConfigCenterConfig configCenter;

    // callback limits
    private Integer callbacks;
    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    /**
     * Check whether the registry config is exists, and then conversion it to {@link RegistryConfig}
     */
    protected void checkRegistry() {
        // 获取代表注册中心配置的RegistryConfig对象， 填充成员变量registries
        loadRegistriesFromBackwardConfig();

        convertRegistryIdsToRegistries();

        // 校验registries中的对象
        for (RegistryConfig registryConfig : registries) {
            if (!registryConfig.isValid()) {
                throw new IllegalStateException("No registry config found or it's not a valid config! " +
                        "The registry config is: " + registryConfig);
            }
        }

        useRegistryForConfigIfNecessary();
    }

    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // for backward compatibility
        // 为当前对象的application属性赋值
        createApplicationIfAbsent();

        if (!application.isValid()) {
            throw new IllegalStateException("No application config found or it's not a valid config! " +
                    "Please add <dubbo:application name=\"...\" /> to your spring config.");
        }

        ApplicationModel.setApplication(application.getName());

        // backward compatibility
        // 取"dubbo.service.shutdown.wait"对应的配置值
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            // 设置系统变量"dubbo.service.shutdown.wait"
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            // 取"dubbo.service.shutdown.wait.seconds"对应的配置值
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                // 设置系统变量"dubbo.service.shutdown.wait.seconds"
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    // 检验当前对象的成员变量monitor是否为空，若为空则创建MonitorConfig对象赋值给它
    protected void checkMonitor() {
        createMonitorIfAbsent();
        if (!monitor.isValid()) {
            logger.info("There's no valid monitor config found, if you want to open monitor statistics for Dubbo, " +
                    "please make sure your monitor is configured properly.");
        }
    }

    // 若当前对象的成员变量monitor为空，则创建MonitorConfig对象赋值给它
    // 若当前对象的成员变量monitor不为空，则直接返回
    private void createMonitorIfAbsent() {
        if (this.monitor != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        // 给当前对象的monitor属性赋值 （使用新生成的MonitorConfig对象 或者 使用configManager对象的monitor属性）
        setMonitor(
                configManager
                        .getMonitor()
                        .orElseGet(() -> {
                            MonitorConfig monitorConfig = new MonitorConfig();
                            monitorConfig.refresh();
                            return monitorConfig;
                        })
        );
    }

    // 若当前对象的metadataReportConfig为空，则新建一个
    protected void checkMetadataReport() {
        // TODO get from ConfigManager first, only create if absent.
        if (metadataReportConfig == null) {
            setMetadataReportConfig(new MetadataReportConfig());
        }
        metadataReportConfig.refresh();
        if (!metadataReportConfig.isValid()) {
            logger.warn("There's no valid metadata config found, if you are using the simplified mode of registry url, " +
                    "please make sure you have a metadata address configured properly.");
        }
    }


    void startConfigCenter() {
        if (configCenter == null) {
            // 从ConfigManager对象中取configCenter属性值 赋给当前对象的configCenter属性。
            ConfigManager.getInstance().getConfigCenter().ifPresent(cc -> this.configCenter = cc);
        }

        if (this.configCenter != null) {
            // TODO there may have duplicate refresh
            this.configCenter.refresh();
            prepareEnvironment();
        }
        ConfigManager.getInstance().refreshAll();
    }

    // 给Environment的成员变量赋值
    private void prepareEnvironment() {
        // configCenter对象的address字段值是否合法
        if (configCenter.isValid()) {
            // cas操作configCenter的inited属性，保证了prepareEnvironment() 只被执行一次
            if (!configCenter.checkOrUpdateInited()) {
                return;
            }
            DynamicConfiguration dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            // 从zk上取group+key对应的配置内容
            String configContent = dynamicConfiguration.getConfig(configCenter.getConfigFile(), configCenter.getGroup());


            // 从zk上取appGroup+key对应的配置内容
            String appGroup = application != null ? application.getName() : null;
            String appConfigContent = null;
            if (StringUtils.isNotEmpty(appGroup)) {
                // 取zk
                appConfigContent = dynamicConfiguration.getConfig
                        (StringUtils.isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                         appGroup
                        );
            }
            try {
                // 解析上面取到的zk节点的内容，存入Environment的map对象中
                Environment.getInstance().setConfigCenterFirst(configCenter.isHighestPriority());
                Environment.getInstance().updateExternalConfigurationMap(parseProperties(configContent));
                Environment.getInstance().updateAppExternalConfigurationMap(parseProperties(appConfigContent));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
        }
    }

    // 获取一个DynamicConfiguration接口的子类对象
    private DynamicConfiguration getDynamicConfiguration(URL url) {
        // 获取别名是"{protocol}"的一个实现类对象 （DynamicConfigurationFactory接口的实现类对象）
        DynamicConfigurationFactory factories = ExtensionLoader
                .getExtensionLoader(DynamicConfigurationFactory.class)
                .getExtension(url.getProtocol());
        // 获取factories对象的dynamicConfiguration属性
        DynamicConfiguration configuration = factories.getDynamicConfiguration(url);
        // 设置到Environment中
        Environment.getInstance().setDynamicConfiguration(configuration);
        return configuration;
    }

    /**
     *
     * Load the registry and conversion it to {@link URL}, the priority order is: system property > dubbo registry config
     *
     * @param provider whether it is the provider side
     * @return
     */
    // 将代表注册中心的配置对象 转成 代表注册中心的URL对象
    // 也就是RegistryConfig对象 转成 URL对象，并返回URL对象集合（因为可以有多种类型的注册中心，zk，redis等等）
    protected List<URL> loadRegistries(boolean provider) {
        // check && override if necessary
        List<URL> registryList = new ArrayList<URL>();
        if (CollectionUtils.isNotEmpty(registries)) {
            // 遍历每个注册中心的配置对象
            for (RegistryConfig config : registries) {
                // 注册中心的地址
                String address = config.getAddress();
                if (StringUtils.isEmpty(address)) {
                    // 若 address 为空，则将其设为 0.0.0.0
                    address = Constants.ANYHOST_VALUE;
                }
                // 若注册中心address有值， 且值不是"N/A"
                if (!RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    Map<String, String> map = new HashMap<String, String>();
                    // 添加 ApplicationConfig 中的字段信息到 map 中
                    appendParameters(map, application);
                    // 添加 RegistryConfig 字段信息到 map 中
                    appendParameters(map, config);
                    // 增加entry("path", " org.apache.dubbo.registry.RegistryService")
                    map.put(Constants.PATH_KEY, RegistryService.class.getName());
                    appendRuntimeParameters(map);
                    if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                        // 增加entry("protocol", "dubbo")
                        map.put(Constants.PROTOCOL_KEY, Constants.DUBBO_PROTOCOL);
                    }
                    // 上面就是填充map，这里开始用

                    // 解析得到 URL 列表，address 可能包含多个注册中心 ip，
                    // 因此解析得到的是一个 URL 列表
                    List<URL> urls = UrlUtils.parseURLs(address, map);

                    for (URL url : urls) {
                        url = URLBuilder.from(url)
                                .addParameter(Constants.REGISTRY_KEY, url.getProtocol())
                                // 将 URL 协议头设置为 "registry"
                                .setProtocol(Constants.REGISTRY_PROTOCOL)
                                .build();
                        // 通过判断条件，决定是否添加 url 到 registryList 中，条件如下：
                        // (服务提供者 && register = true 或 null)
                        //    || (非服务提供者 && subscribe = true 或 null)
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                                || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    /**
     *
     * Load the monitor config from the system properties and conversation it to {@link URL}
     *
     * @param registryURL
     * @return
     */
    // 使用当前对象的monitor成员变量内的属性，构造一个url返回
    protected URL loadMonitor(URL registryURL) {
        // 保证当前对象的monitor属性有值
        checkMonitor();
        Map<String, String> map = new HashMap<String, String>();
        // 新增entry("interface", "org.apache.dubbo.monitor.MonitorService")
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        appendRuntimeParameters(map);
        //set ip
        // 从系统环境中取"DUBBO_IP_TO_REGISTRY"对应的值
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        }
        // 新增entry（"register.ip"，{hostToRegistry}）
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);
        appendParameters(map, monitor);
        appendParameters(map, application);
        String address = monitor.getAddress();
        // 从环境变量中取地址
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        if (ConfigUtils.isNotEmpty(address)) {
            // map中没有key="protocol"对应的entry
            if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                // MonitorFactory接口是否有别名为"logstat"的实现类
                if (getExtensionLoader(MonitorFactory.class).hasExtension(Constants.LOGSTAT_PROTOCOL)) {
                    // map中添加（"protocol"，"logstat"）
                    map.put(Constants.PROTOCOL_KEY, Constants.LOGSTAT_PROTOCOL);
                } else {
                    // map中添加（"protocol"，"dubbo"）
                    map.put(Constants.PROTOCOL_KEY, Constants.DUBBO_PROTOCOL);
                }
            }
            // 生成url返回
            return UrlUtils.parseURL(address, map);
        } // monitor对象的protocol属性值="registry" 且 入参registryURL不为空
        else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            return URLBuilder.from(registryURL)
                    // 设置url的protocal属性值为"dubbo"
                    .setProtocol(Constants.DUBBO_PROTOCOL)
                    // url的parameters中增加entry（"protocol"，"registry"）
                    .addParameter(Constants.PROTOCOL_KEY, Constants.REGISTRY_PROTOCOL)
                    // url的parameters中增加entry（"refer"，***）
                    .addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map))
                    .build();
        }
        return null;
    }

    // 在map中添加一些项目运行时的参数
    static void appendRuntimeParameters(Map<String, String> map) {
        // 增加entry("dubbo", "2.0.2")
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        // key="release"， vlaue是Version.class所在jar包的版本号
        map.put(Constants.RELEASE_KEY, Version.getVersion());
        // 增加entry("timestamp", 当前时间戳)
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            // 增加entry("pid", 当前进程的PID)
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
    }

    private URL loadMetadataReporterURL() {
        String address = metadataReportConfig.getAddress();
        if (StringUtils.isEmpty(address)) {
            return null;
        }
        Map<String, String> map = new HashMap<String, String>();
        appendParameters(map, metadataReportConfig);
        return UrlUtils.parseURL(address, map);
    }

    protected MetadataReportService getMetadataReportService() {

        if (metadataReportConfig == null || !metadataReportConfig.isValid()) {
            return null;
        }
        return MetadataReportService.instance(this::loadMetadataReporterURL);
    }

    /**
     * Check whether the remote service interface and the methods meet with Dubbo's requirements.it mainly check, if the
     * methods configured in the configuration file are included in the interface of remote service
     *
     * @param interfaceClass the interface of remote service
     * @param methods the methods configured
     */
    // 判断入参methods中的每一个元素，都是入参interfaceClass中的方法
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        Assert.notNull(interfaceClass, new IllegalStateException("interface not allow null!"));

        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // 到这说明interfaceClass是接口 且 不为空

        // check if methods exist in the remote service interface
        // 判断这些方法属于interfaceClass接口
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig methodBean : methods) {
                methodBean.setService(interfaceClass.getName());
                methodBean.setServiceId(this.getId());
                methodBean.refresh();
                String methodName = methodBean.getName();
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: " +
                            "<dubbo:service interface=\"" + interfaceClass.getName() + "\" ... >" +
                            "<dubbo:method name=\"\" ... /></<dubbo:reference>");
                }

                // 判断interfaceClass中是否有名字为methodName的方法，没有就抛异常
                boolean hasMethod = Arrays.stream(interfaceClass.getMethods()).anyMatch(method -> method.getName().equals(methodName));
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }

    /**
     * Legitimacy check and setup of local simulated operations. The operations can be a string with Simple operation or
     * a classname whose {@link Class} implements a particular function
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface that will be referenced
     */
    // 检验成员变量mock的值是否合法， 不合法则抛出异常
    // （mock是否为可解析的值  或者 mock是否为异常类的全名 或者 mock是否是interfaceClass的子类。 根据mock的值 三选一的校验）
    void checkMock(Class<?> interfaceClass) {
        if (ConfigUtils.isEmpty(mock)) {
            return;
        }
        // 标准化mock串
        String normalizedMock = MockInvoker.normalizeMock(mock);
        // 若normalizedMock以"return "开头
        if (normalizedMock.startsWith(Constants.RETURN_PREFIX)) {
            // 截取normalizedMock，剩下"return "之后的串
            normalizedMock = normalizedMock.substring(Constants.RETURN_PREFIX.length()).trim();
            try {
                //Check whether the mock value is legal, if it is illegal, throw exception
                // 解析入参normalizedMock 成为一个值
                MockInvoker.parseMockValue(normalizedMock);
            } catch (Exception e) {
                throw new IllegalStateException("Illegal mock return in <dubbo:service/reference ... " +
                        "mock=\"" + mock + "\" />");
            }
        } // 若normalizedMock以"throw"开头
        else if (normalizedMock.startsWith(Constants.THROW_PREFIX)) {
            // 截取得到"throw"后面的串
            normalizedMock = normalizedMock.substring(Constants.THROW_PREFIX.length()).trim();
            if (ConfigUtils.isNotEmpty(normalizedMock)) {
                try {
                    //Check whether the mock value is legal
                    // 校验normalizedMock是否为异常类的名字（这里用到的是这个作用）
                    MockInvoker.getThrowable(normalizedMock);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock throw in <dubbo:service/reference ... " +
                            "mock=\"" + mock + "\" />");
                }
            }
        } else {
            //Check whether the mock class is a implementation of the interfaceClass, and if it has a default constructor
            // 校验normalizedMock是interfaceClass的子类或者同类 （这里用到的是这个作用）
            MockInvoker.getMockObject(normalizedMock, interfaceClass);
        }
    }

    /**
     * Legitimacy check of stub, note that: the local will deprecated, and replace with <code>stub</code>
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface
     */
    // 加载"{local}" 和 "{stub}" 所描述的类的clazz，并验证该clazz是否为入参interfaceClass的子类，不是则抛出异常
    // 其中，"{local}" 和 "{stub}" 分别表示成员变量local和stub的值
    void checkStubAndLocal(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            // 加载"{local}" 或者 "{interfaceClass}Local" 类的clazz
            Class<?> localClass = ConfigUtils.isDefault(local) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            // 校验入参interfaceClass是否为localClass的父类或同类，
            // 检验入参localClass对象中，是否有参数类型为interfaceClass的构造函数
            // 其中任一个不满足，则抛出异常
            verify(interfaceClass, localClass);
        }
        if (ConfigUtils.isNotEmpty(stub)) {
            // 加载"{stub}" 或者 "{interfaceClass}Stub" 类的clazz
            Class<?> localClass = ConfigUtils.isDefault(stub) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            verify(interfaceClass, localClass);
        }
    }

    // 验证
    // 入参interfaceClass是否为localClass的父类或同类，
    // 入参localClass对象中，是否有参数类型为interfaceClass的构造函数
    // 其中任一个不满足，则抛出异常
    private void verify(Class<?> interfaceClass, Class<?> localClass) {
        // interfaceClass必须是localClass的父类或同类，否则抛异常
        if (!interfaceClass.isAssignableFrom(localClass)) {
            throw new IllegalStateException("The local implementation class " + localClass.getName() +
                    " not implement interface " + interfaceClass.getName());
        }

        try {
            //Check if the localClass a constructor with parameter who's type is interfaceClass
            // 查看localClass对象中，是否有参数类型为interfaceClass的构造函数
            ReflectUtils.findConstructor(localClass, interfaceClass);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() +
                    "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
        }
    }

    // 本函数的目的是 给ConfigManager对象和当前对象的成员变量registries赋值，
    // 使用成员变量registryIds中的元素，生成RegistryConfig对象的集合，将该集合赋值给ConfigManager对象和当前对象的成员变量registries
    private void convertRegistryIdsToRegistries() {
        // 若registryIds和registries都为空，则先从Environment对象中收集RegistryConfig对象的id属性值 到set
        if (StringUtils.isEmpty(registryIds) && CollectionUtils.isEmpty(registries)) {
            Set<String> configedRegistries = new HashSet<>();
            // 把externalConfigurationMap中带前缀"dubbo.registries."的key，去掉前缀， 加入到set中
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getExternalConfigurationMap(),
                    Constants.REGISTRIES_SUFFIX));
            // 把appExternalConfigurationMap中带前缀"dubbo.registries."的key，去掉前缀， 加入到set中
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getAppExternalConfigurationMap(),
                    Constants.REGISTRIES_SUFFIX));

            // 将set中的元素使用","连接起来
            registryIds = String.join(Constants.COMMA_SEPARATOR, configedRegistries);
        }

        // 若上面没有收集到RegistryConfig对象的id属性值，即 registryIds为空
        if (StringUtils.isEmpty(registryIds)) {
            // registries也为空
            if (CollectionUtils.isEmpty(registries)) {
                // 则先从ConfigManager对象中取默认的RegistryConfig对象， 若没取到则new一个，
                // 给ConfigManager对象和当前对象的成员变量registries赋值
                setRegistries(
                        ConfigManager.getInstance().getDefaultRegistries()
                        .filter(CollectionUtils::isNotEmpty)
                        .orElseGet(() -> {
                            RegistryConfig registryConfig = new RegistryConfig();
                            registryConfig.refresh();
                            return Arrays.asList(registryConfig);
                        })
                );
            }
        } else {
            // 若上面收集到了RegistryConfig对象的id属性值，
            // 即 registryIds有值，则使用registryIds中的元素，new一些RegistryConfig对象
            String[] ids = Constants.COMMA_SPLIT_PATTERN.split(registryIds);
            List<RegistryConfig> tmpRegistries = CollectionUtils.isNotEmpty(registries) ? registries : new ArrayList<>();
            Arrays.stream(ids).forEach(id -> {
                // 若registries中没有该id的RegistryConfig对象 且 ConfigManager中也没有该id的RegistryConfig对象，
                // 则new一个RegistryConfig对象添加到tmpRegistries中
                if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                    tmpRegistries.add(ConfigManager.getInstance().getRegistry(id).orElseGet(() -> {
                        RegistryConfig registryConfig = new RegistryConfig();
                        registryConfig.setId(id);
                        registryConfig.refresh();
                        return registryConfig;
                    }));
                }
            });

            if (tmpRegistries.size() > ids.length) {
                throw new IllegalStateException("Too much registries found, the registries assigned to this service " +
                        "are :" + registryIds + ", but got " + tmpRegistries.size() + " registries!");
            }

            // 给ConfigManager对象和当前对象的成员变量registries赋值
            setRegistries(tmpRegistries);
        }

    }

    // 这个函数就是用于兼容之前版本功能的函数
    // 若当前对象的registries属性为空，则说明没有取到注册中心的配置，则使用早期版本的方式取注册中心的配置
    // 若可以取到，则生成对应的RegistryConfig对象，并赋值给当前对象的registries属性。
    private void loadRegistriesFromBackwardConfig() {
        // for backward compatibility
        // -Ddubbo.registry.address is now deprecated.
        if (registries == null || registries.isEmpty()) {
            // 从环境变量或者配置文件"dubbo.properties"里， 取注册中心的地址
            // 例如： dubbo.registry.address=10.20.153.10:9090 相当于 <dubbo:registry address="10.20.153.10:9090" />
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                List<RegistryConfig> tmpRegistries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    // new一个RegistryConfig对象，并添加到tmpRegistries中
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registryConfig.refresh();
                    tmpRegistries.add(registryConfig);
                }
                // 给ConfigManager对象和当前对象的成员变量registries赋值
                setRegistries(tmpRegistries);
            }
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center if the registry protocol is zookeeper and
     * there's no config center specified explicitly.
     */
    private void useRegistryForConfigIfNecessary() {
        registries.stream().filter(RegistryConfig::isZookeeperProtocol).findFirst().ifPresent(rc -> {
            // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
            Environment.getInstance().getDynamicConfiguration().orElseGet(() -> {
                ConfigManager configManager = ConfigManager.getInstance();
                ConfigCenterConfig cc = configManager.getConfigCenter().orElse(new ConfigCenterConfig());
                cc.setProtocol(rc.getProtocol());
                cc.setAddress(rc.getAddress());
                cc.setHighestPriority(false);
                setConfigCenter(cc);
                startConfigCenter();
                return null;
            });
        });
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(local.toString());
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName(Constants.LOCAL_KEY, local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(stub.toString());
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, Constants.CLUSTER_KEY, cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, Constants.PROXY_KEY, proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, Constants.FILE_KEY, filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, Constants.LISTENER_KEY, listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol(Constants.LAYER_KEY, layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    // 给ConfigManager对象 和 当前对象的application属性赋值
    public void setApplication(ApplicationConfig application) {
        // 给ConfigManager对象的application属性赋值。
        ConfigManager.getInstance().setApplication(application);
        // 给当前对象的application属性赋值
        this.application = application;
    }

    // 为当前对象的application属性赋值
    // 从ConfigManager对象中取一个 或者 新建一个
    private void createApplicationIfAbsent() {
        if (this.application != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        // 先从ConfigManager对象中取值，若取不到，则new一个ApplicationConfig对象
        setApplication(
                configManager
                        .getApplication()
                        .orElseGet(() -> {
                            ApplicationConfig applicationConfig = new ApplicationConfig();
                            applicationConfig.refresh();
                            return applicationConfig;
                        })
        );
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        ConfigManager.getInstance().setModule(module);
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return CollectionUtils.isEmpty(registries) ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        setRegistries(registries);
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    // 给ConfigManager对象和当前对象的成员变量registries赋值
    public void setRegistries(List<? extends RegistryConfig> registries) {
        // 将入参registryConfigs 中的元素添加到ConfigManager对象的registries集合中
        ConfigManager.getInstance().addRegistries((List<RegistryConfig>) registries);
        // 设置当前对象的registries值
        this.registries = (List<RegistryConfig>) registries;
    }

    @Parameter(excluded = true)
    public String getRegistryIds() {
        return registryIds;
    }

    public void setRegistryIds(String registryIds) {
        this.registryIds = registryIds;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        setMonitor(new MonitorConfig(monitor));
    }

    // 给ConfigManager对象和当前对象的成员变量monitor赋值
    public void setMonitor(MonitorConfig monitor) {
        // 给ConfigManager对象的成员变量monitor赋值
        ConfigManager.getInstance().setMonitor(monitor);
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public ConfigCenterConfig getConfigCenter() {
        return configCenter;
    }

    // 给ConfigManager对象的configCenter属性 和 当前对象的configCenter属性赋值
    public void setConfigCenter(ConfigCenterConfig configCenter) {
        ConfigManager.getInstance().setConfigCenter(configCenter);
        this.configCenter = configCenter;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public MetadataReportConfig getMetadataReportConfig() {
        return metadataReportConfig;
    }

    public void setMetadataReportConfig(MetadataReportConfig metadataReportConfig) {
        this.metadataReportConfig = metadataReportConfig;
    }

    public MetricsConfig getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsConfig metrics) {
        this.metrics = metrics;
    }
}
