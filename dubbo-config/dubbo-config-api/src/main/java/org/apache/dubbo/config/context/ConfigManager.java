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
package org.apache.dubbo.config.context;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.Constants.DEFAULT_KEY;

/**
 * TODO
 * Experimental API, should only being used internally at present.
 * <p>
 * Maybe we can consider open to end user in the following version by providing a fluent style builder.
 *
 * <pre>{@code
 *  public void class DubboBuilder() {
 *
 *      public static DubboBuilder create() {
 *          return new DubboBuilder();
 *      }
 *
 *      public DubboBuilder application(ApplicationConfig application) {
 *          ConfigManager.getInstance().addApplication(application);
 *          return this;
 *      }
 *
 *      ...
 *
 *      public void build() {
 *          // export all ServiceConfigs
 *          // refer all ReferenceConfigs
 *      }
 *  }
 *  }
 * </pre>
 * </p>
 * TODO
 * The properties defined here are duplicate with that in ReferenceConfig/ServiceConfig,
 * the properties here are currently only used for duplication check but are still not being used in the export/refer process yet.
 * Maybe we can remove the property definition in ReferenceConfig/ServiceConfig and only keep the setXxxConfig() as an entrance.
 * All workflow internally can rely on ConfigManager.
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final ConfigManager configManager = new ConfigManager();

    private ApplicationConfig application;
    private MonitorConfig monitor;
    private ModuleConfig module;
    private ConfigCenterConfig configCenter;

    // 下面map都是  key为id, value为相应的Config对象
    private Map<String, ProtocolConfig> protocols = new ConcurrentHashMap<>();
    private Map<String, RegistryConfig> registries = new ConcurrentHashMap<>();
    private Map<String, ProviderConfig> providers = new ConcurrentHashMap<>();
    private Map<String, ConsumerConfig> consumers = new ConcurrentHashMap<>();

    public static ConfigManager getInstance() {
        return configManager;
    }

    private ConfigManager() {

    }

    public Optional<ApplicationConfig> getApplication() {
        return Optional.ofNullable(application);
    }

    public void setApplication(ApplicationConfig application) {
        if (application != null) {
            checkDuplicate(this.application, application);
            this.application = application;
        }
    }

    public Optional<MonitorConfig> getMonitor() {
        return Optional.ofNullable(monitor);
    }

    // 给成员变量monitor赋值
    public void setMonitor(MonitorConfig monitor) {
        if (monitor != null) {
            // 若this.monitor有值 且 this.monitor和参数monitor对象的属性值不同, 则抛出异常; 若相同则无动作
            checkDuplicate(this.monitor, monitor);
            this.monitor = monitor;
        }
    }

    public Optional<ModuleConfig> getModule() {
        return Optional.ofNullable(module);
    }

    public void setModule(ModuleConfig module) {
        if (module != null) {
            checkDuplicate(this.module, module);
            this.module = module;
        }
    }

    public Optional<ConfigCenterConfig> getConfigCenter() {
        return Optional.ofNullable(configCenter);
    }

    public void setConfigCenter(ConfigCenterConfig configCenter) {
        if (configCenter != null) {
            checkDuplicate(this.configCenter, configCenter);
            this.configCenter = configCenter;
        }
    }

    public Optional<ProviderConfig> getProvider(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public Optional<ProviderConfig> getDefaultProvider() {
        return Optional.ofNullable(providers.get(DEFAULT_KEY));
    }

    // 将参数providerConfig加入成员变量providers中 (key是providerConfig的id字段, value是providerConfig对象)
    public void addProvider(ProviderConfig providerConfig) {
        if (providerConfig == null) {
            return;
        }

        // key= 若id不为空, 返回id, 否则 若允许默认值 则返回"default" 否则返回null
        String key = StringUtils.isNotEmpty(providerConfig.getId())
                ? providerConfig.getId()
                : (providerConfig.isDefault() == null || providerConfig.isDefault()) ? DEFAULT_KEY : null;

        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A ProviderConfig should either has an id or it's the default one, " + providerConfig);
        }

        // providers中key对应的value和参数providerConfig不同, 则warn一下 (key相同, 但是两个providerConfig对象的属性值不同)
        if (providers.containsKey(key) && !providerConfig.equals(providers.get(key))) {
            logger.warn("Duplicate ProviderConfig found, there already has one default ProviderConfig or more than two ProviderConfigs have the same id, " +
                                                    "you can try to give each ProviderConfig a different id. " + providerConfig);
        } else {
            // add
            providers.put(key, providerConfig);
        }
    }

    public Optional<ConsumerConfig> getConsumer(String id) {
        return Optional.ofNullable(consumers.get(id));
    }

    public Optional<ConsumerConfig> getDefaultConsumer() {
        return Optional.ofNullable(consumers.get(DEFAULT_KEY));
    }

    // 把参数consumerConfig添加到成员变量consumers中
    public void addConsumer(ConsumerConfig consumerConfig) {
        if (consumerConfig == null) {
            return;
        }
        // key= 若id不为空, 返回id, 否则 若允许默认值 则返回"default" 否则返回null
        String key = StringUtils.isNotEmpty(consumerConfig.getId())
                ? consumerConfig.getId()
                : (consumerConfig.isDefault() == null || consumerConfig.isDefault()) ? DEFAULT_KEY : null;

        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A ConsumerConfig should either has an id or it's the default one, " + consumerConfig);
        }
        // 若有id相同(即,key相同) 但属性值不同的consumerConfig对象, 则warn下, 不添加到consumers中
        if (consumers.containsKey(key) && !consumerConfig.equals(consumers.get(key))) {
            logger.warn("Duplicate ConsumerConfig found, there already has one default ConsumerConfig or more than two ConsumerConfigs have the same id, " +
                                                    "you can try to give each ConsumerConfig a different id. " + consumerConfig);
        } else {
            // add
            consumers.put(key, consumerConfig);
        }
    }

    public Optional<ProtocolConfig> getProtocol(String id) {
        return Optional.ofNullable(protocols.get(id));
    }

    // 从成员变量protocols中 获取默认的ProtocolConfig对象集合并返回
    public Optional<List<ProtocolConfig>> getDefaultProtocols() {
        List<ProtocolConfig> defaults = new ArrayList<>();
        protocols.forEach((k, v) -> {
            // key="default" 则添加到集合defaults
            if (DEFAULT_KEY.equalsIgnoreCase(k)) {
                defaults.add(v);
            } else if (v.isDefault() == null || v.isDefault()) {
                // value的isDefault属性是true, 则添加到集合defaults
                defaults.add(v);
            }
        });
        // 返回defaults集合
        return Optional.of(defaults);
    }

    // 将参数protocolConfigs中的元素添加到当前对象的成员变量protocols中
    public void addProtocols(List<ProtocolConfig> protocolConfigs) {
        if (protocolConfigs != null) {
            protocolConfigs.forEach(this::addProtocol);
        }
    }

    // 将(protocolConfig.getId(),  protocolConfig) 添加到成员变量protocols中
    // 估计是增加一条protocol的配置信息
    public void addProtocol(ProtocolConfig protocolConfig) {
        if (protocolConfig == null) {
            return;
        }

        // key= 若id不为空, 返回id, 否则 若允许默认值 则返回"default" 否则返回null
        String key = StringUtils.isNotEmpty(protocolConfig.getId())
                ? protocolConfig.getId()
                : (protocolConfig.isDefault() == null || protocolConfig.isDefault()) ? DEFAULT_KEY : null;

        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A ProtocolConfig should either has an id or it's the default one, " + protocolConfig);
        }
        // 若有id相同(即,key相同) 且属性值不同的ProtocolConfig对象, 则warn下, 不添加到protocols中
        if (protocols.containsKey(key) && !protocolConfig.equals(protocols.get(key))) {
            logger.warn("Duplicate ProtocolConfig found, there already has one default ProtocolConfig or more than two ProtocolConfigs have the same id, " +
                                                    "you can try to give each ProtocolConfig a different id. " + protocolConfig);
        } else {
            // key= id, value= protocolConfig对象
            protocols.put(key, protocolConfig);
        }
    }

    // 返回一个有值或者空的Optional对象 (若有值则值是RegistryConfig对象)
    public Optional<RegistryConfig> getRegistry(String id) {
        return Optional.ofNullable(registries.get(id));
    }

    // 从成员变量registries中 获取默认的RegistryConfig对象集合并返回
    public Optional<List<RegistryConfig>> getDefaultRegistries() {
        List<RegistryConfig> defaults = new ArrayList<>();
        registries.forEach((k, v) -> {
            if (DEFAULT_KEY.equalsIgnoreCase(k)) {
                // 若key="default"则把RegistryConfig对象添加到成员变量defaults中
                defaults.add(v);
            } else if (v.isDefault() == null || v.isDefault()) {
                // value的isDefault属性为true, 则把value添加到成员变量defaults中
                defaults.add(v);
            }
        });
        return Optional.of(defaults);
    }

    // 将参数registryConfigs 中的元素添加到成员变量registries中
    public void addRegistries(List<RegistryConfig> registryConfigs) {
        if (registryConfigs != null) {
            registryConfigs.forEach(this::addRegistry);
        }
    }

    // 将 (registryConfig.getId(), registryConfig) 添加到成员变量registries中 (若key对应的value 和参数registryConfig对象的属性值一样, 则不添加)
    public void addRegistry(RegistryConfig registryConfig) {
        if (registryConfig == null) {
            return;
        }

        // id不是空, 则返回id, 否则若允许有默认值, 则返回 "default", 否则返回null
        String key = StringUtils.isNotEmpty(registryConfig.getId())
                ? registryConfig.getId()
                : (registryConfig.isDefault() == null || registryConfig.isDefault()) ? DEFAULT_KEY : null;

        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A RegistryConfig should either has an id or it's the default one, " + registryConfig);
        }

        // key对应的RegistryConfig对象存在, 且参数registryConfig 与key对应的registryConfig对象 属性值不同, 则warn下, 不添加到registries中
        if (registries.containsKey(key) && !registryConfig.equals(registries.get(key))) {
            logger.warn("Duplicate RegistryConfig found, there already has one default RegistryConfig or more than two RegistryConfigs have the same id, " +
                                                    "you can try to give each RegistryConfig a different id. " + registryConfig);
        } else {
            // add
            registries.put(key, registryConfig);
        }
    }

    public Map<String, ProtocolConfig> getProtocols() {
        return protocols;
    }

    public Map<String, RegistryConfig> getRegistries() {
        return registries;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public Map<String, ConsumerConfig> getConsumers() {
        return consumers;
    }

    public void refreshAll() {
        // refresh all configs here,
        getApplication().ifPresent(ApplicationConfig::refresh);
        getMonitor().ifPresent(MonitorConfig::refresh);
        getModule().ifPresent(ModuleConfig::refresh);

        getProtocols().values().forEach(ProtocolConfig::refresh);
        getRegistries().values().forEach(RegistryConfig::refresh);
        getProviders().values().forEach(ProviderConfig::refresh);
        getConsumers().values().forEach(ConsumerConfig::refresh);
    }

    // 若oldOne为空, 则直接返回.
    // 若oldOne不为空, 则比较 oldOne和newOne 两个对象成员变量值是否相同, 若相同则直接返回, 若不同则抛出异常;
    private void checkDuplicate(AbstractConfig oldOne, AbstractConfig newOne) {
        // 若oldOne和newOne对象的属性值不同, 则抛出异常
        if (oldOne != null && !oldOne.equals(newOne)) {
            String configName = oldOne.getClass().getSimpleName();
            // 若oldOne和newOne对象的属性值不同, 则抛出异常
            throw new IllegalStateException("Duplicate Config found for " + configName + ", you should use only one unique " + configName + " for one application.");
        }
    }

    // For test purpose
    public void clear() {
        this.application = null;
        this.configCenter = null;
        this.monitor = null;
        this.module = null;
        this.registries.clear();
        this.protocols.clear();
        this.providers.clear();
        this.consumers.clear();
    }

}
