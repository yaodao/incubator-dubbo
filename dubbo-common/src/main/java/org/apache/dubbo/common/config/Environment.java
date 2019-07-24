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
package org.apache.dubbo.common.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TODO load as SPI will be better?
 */
public class Environment {
    private static final Environment INSTANCE = new Environment();

    // key是 prefix+id+"."  value是一个Configuration类型的对象, 下面的一样
    private Map<String, PropertiesConfiguration> propertiesConfigs = new ConcurrentHashMap<>();
    private Map<String, SystemConfiguration> systemConfigs = new ConcurrentHashMap<>();
    private Map<String, EnvironmentConfiguration> environmentConfigs = new ConcurrentHashMap<>();
    // key是prefix+id+"."  value是InmemoryConfiguration对象)
    private Map<String, InmemoryConfiguration> externalConfigs = new ConcurrentHashMap<>();
    private Map<String, InmemoryConfiguration> appExternalConfigs = new ConcurrentHashMap<>();

    private Map<String, String> externalConfigurationMap = new HashMap<>();
    private Map<String, String> appExternalConfigurationMap = new HashMap<>();

    private boolean configCenterFirst = true;

    /**
     * FIXME, this instance will always be a type of DynamicConfiguration, ConfigCenterConfig will load the instance at startup and assign it to here.
     */
    private Configuration dynamicConfiguration;

    public static Environment getInstance() {
        return INSTANCE;
    }

    // 将 (key=prefix+id+"."  value=SystemConfiguration) 放到propertiesConfigs中, 并返回PropertiesConfiguration对象
    public PropertiesConfiguration getPropertiesConfig(String prefix, String id) {
        return propertiesConfigs.computeIfAbsent(toKey(prefix, id), k -> new PropertiesConfiguration(prefix, id));
    }

    // 将 (key=prefix+id+"."  value=SystemConfiguration) 放到systemConfigs中, 并返回SystemConfiguration对象
    public SystemConfiguration getSystemConfig(String prefix, String id) {
        return systemConfigs.computeIfAbsent(toKey(prefix, id), k -> new SystemConfiguration(prefix, id));
    }

    // 将 (key=prefix+id+"."  value=InmemoryConfiguration对象) 放到externalConfigs中, 并返回InmemoryConfiguration对象
    public InmemoryConfiguration getExternalConfig(String prefix, String id) {
        return externalConfigs.computeIfAbsent(toKey(prefix, id), k -> {
            InmemoryConfiguration configuration = new InmemoryConfiguration(prefix, id);
            // 设置InmemoryConfiguration对象的成员变量store值
            configuration.setProperties(externalConfigurationMap);
            return configuration;
        });
    }

    // 将 (key=prefix+id+"."  value=InmemoryConfiguration对象) 放到appExternalConfigs中, 并返回InmemoryConfiguration对象
    public InmemoryConfiguration getAppExternalConfig(String prefix, String id) {
        return appExternalConfigs.computeIfAbsent(toKey(prefix, id), k -> {
            InmemoryConfiguration configuration = new InmemoryConfiguration(prefix, id);
            // 设置InmemoryConfiguration对象的成员变量store值
            configuration.setProperties(appExternalConfigurationMap);
            return configuration;
        });
    }

    public EnvironmentConfiguration getEnvironmentConfig(String prefix, String id) {
        return environmentConfigs.computeIfAbsent(toKey(prefix, id), k -> new EnvironmentConfiguration(prefix, id));
    }

    public void setExternalConfigMap(Map<String, String> externalConfiguration) {
        this.externalConfigurationMap = externalConfiguration;
    }

    public void setAppExternalConfigMap(Map<String, String> appExternalConfiguration) {
        this.appExternalConfigurationMap = appExternalConfiguration;
    }

    public Map<String, String> getExternalConfigurationMap() {
        return externalConfigurationMap;
    }

    public Map<String, String> getAppExternalConfigurationMap() {
        return appExternalConfigurationMap;
    }

    // 将参数map中的所有entry添加到externalConfigurationMap中
    public void updateExternalConfigurationMap(Map<String, String> externalMap) {
        this.externalConfigurationMap.putAll(externalMap);
    }

    // 将参数map中的所有entry添加到appExternalConfigurationMap中
    public void updateAppExternalConfigurationMap(Map<String, String> externalMap) {
        this.appExternalConfigurationMap.putAll(externalMap);
    }

    /**
     * Create new instance for each call, since it will be called only at startup, I think there's no big deal of the potential cost.
     * Otherwise, if use cache, we should make sure each Config has a unique id which is difficult to guarantee because is on the user's side,
     * especially when it comes to ServiceConfig and ReferenceConfig.
     *
     * @param prefix
     * @param id
     * @return
     */
    // 新生成一个CompositeConfiguration对象, 将各种Configuration对象添加到它的configList中, 返回该对象
    public CompositeConfiguration getConfiguration(String prefix, String id) {
        CompositeConfiguration compositeConfiguration = new CompositeConfiguration();
        // Config center has the highest priority
        compositeConfiguration.addConfiguration(this.getSystemConfig(prefix, id));
        // 添加一个InmemoryConfiguration对象, 该InmemoryConfiguration对象的store属性值为本类的成员变量appExternalConfigurationMap
        compositeConfiguration.addConfiguration(this.getAppExternalConfig(prefix, id));
        // 添加一个InmemoryConfiguration对象, 该InmemoryConfiguration对象的store属性值为本类的成员变量externalConfigurationMap
        compositeConfiguration.addConfiguration(this.getExternalConfig(prefix, id));
        //添加到configList中
        compositeConfiguration.addConfiguration(this.getPropertiesConfig(prefix, id));
        return compositeConfiguration;
    }

    public Configuration getConfiguration() {
        return getConfiguration(null, null);
    }

    // 返回 prefix+id+"." 或者 prefix和id都为空, 则返回"dubbo"
    private static String toKey(String prefix, String id) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(prefix)) {
            sb.append(prefix);
        }
        if (StringUtils.isNotEmpty(id)) {
            sb.append(id);
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '.') {
            sb.append(".");
        }

        if (sb.length() > 0) {
            return sb.toString();
        }
        return Constants.DUBBO;
    }

    public boolean isConfigCenterFirst() {
        return configCenterFirst;
    }

    public void setConfigCenterFirst(boolean configCenterFirst) {
        this.configCenterFirst = configCenterFirst;
    }

    public Optional<Configuration> getDynamicConfiguration() {
        return Optional.ofNullable(dynamicConfiguration);
    }

    public void setDynamicConfiguration(Configuration dynamicConfiguration) {
        this.dynamicConfiguration = dynamicConfiguration;
    }

    // For test
    public void clearExternalConfigs() {
        this.externalConfigs.clear();
        this.externalConfigurationMap.clear();
    }

    // For test
    public void clearAppExternalConfigs() {
        this.appExternalConfigs.clear();
        this.appExternalConfigurationMap.clear();
    }
}
