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
package org.apache.dubbo.rpc.cluster.configurator.parser;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.configurator.parser.model.ConfigItem;
import org.apache.dubbo.rpc.cluster.configurator.parser.model.ConfiguratorConfig;

import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Config parser
 */
public class ConfigParser {

    // 以读取ServiceMultiApps.yml文件为例
    // 入参rawConfig就是文件内容的字符串
    // 将配置文件中的内容转成url对象集合
    public static List<URL> parseConfigurators(String rawConfig) {
        List<URL> urls = new ArrayList<>();
        // 将文件内容转成ConfiguratorConfig对象
        ConfiguratorConfig configuratorConfig = parseObject(rawConfig);

        // scope: service
        String scope = configuratorConfig.getScope();
        List<ConfigItem> items = configuratorConfig.getConfigs();

        if (ConfiguratorConfig.SCOPE_APPLICATION.equals(scope)) {
            // items中的每个元素，附加上各种参数后，转成url对象
            items.forEach(item -> urls.addAll(appItemToUrls(item, configuratorConfig)));
        } else {
            // service scope by default.
            // items中的每个元素，附加上各种参数后，转成url对象
            items.forEach(item -> urls.addAll(serviceItemToUrls(item, configuratorConfig)));
        }
        return urls;
    }

    private static <T> T parseObject(String rawConfig) {
        Constructor constructor = new Constructor(ConfiguratorConfig.class);
        TypeDescription itemDescription = new TypeDescription(ConfiguratorConfig.class);
        itemDescription.addPropertyParameters("items", ConfigItem.class);
        constructor.addTypeDescription(itemDescription);

        Yaml yaml = new Yaml(constructor);
        return yaml.load(rawConfig);
    }

    // 以ServiceMultiApps.yml文件内容为例
    // 就是将配置文件中的addresses项的值，附加上各种参数后，转成url对象
    // 附加的参数来自入参 item和config
    private static List<URL> serviceItemToUrls(ConfigItem item, ConfiguratorConfig config) {
        List<URL> urls = new ArrayList<>();
        // [127.0.0.1, 0.0.0.0]
        List<String> addresses = parseAddresses(item);

        // 对addresses中的每个元素，附加上各种参数后， 转成url对象
        addresses.forEach(addr -> {
            StringBuilder urlBuilder = new StringBuilder();
            // "override://127.0.0.1/"
            urlBuilder.append("override://").append(addr).append("/");

            // urlBuilder追加接口名和版本号
            urlBuilder.append(appendService(config.getKey()));
            // urlBuilder追加 "category=dynamicconfigurators&timeout=6666"
            urlBuilder.append(toParameterString(item));
            // urlBuilder后追加串 "&enabled={enabled}"
            parseEnabled(item, config, urlBuilder);

            urlBuilder.append("&category=").append(Constants.DYNAMIC_CONFIGURATORS_CATEGORY);
            // urlBuilder追加 "&configVersion=v2.7"
            urlBuilder.append("&configVersion=").append(config.getConfigVersion());

            // [app1, app2]
            List<String> apps = item.getApplications();
            if (apps != null && apps.size() > 0) {
                apps.forEach(app -> {
                    //  在urlBuilder后追加 "&application=app1"，再转成url对象
                    urls.add(URL.valueOf(urlBuilder.append("&application=").append(app).toString()));
                });
            } else {
                // 将urlBuilder转成url对象
                urls.add(URL.valueOf(urlBuilder.toString()));
            }
        });

        return urls;
    }

    // 以AppMultiServices.yml文件内容为例
    // 就是将配置文件中的addresses项的值，附加上各种参数后，转成url对象
    // 附加的参数来自入参 item和config
    private static List<URL> appItemToUrls(ConfigItem item, ConfiguratorConfig config) {
        List<URL> urls = new ArrayList<>();
        // [127.0.0.1, 0.0.0.0]
        List<String> addresses = parseAddresses(item);

        // 对addresses中的每个元素，附加上各种参数后， 转成url对象
        for (String addr : addresses) {
            StringBuilder urlBuilder = new StringBuilder();
            // "override://127.0.0.1/"
            urlBuilder.append("override://").append(addr).append("/");
            // ['service1', 'service2']
            List<String> services = item.getServices();
            if (services == null) {
                services = new ArrayList<>();
            }
            if (services.size() == 0) {
                services.add("*");
            }
            for (String s : services) {
                // urlBuilder追加接口名和版本号
                urlBuilder.append(appendService(s));
                // urlBuilder追加 "category=dynamicconfigurators&timeout=6666&loadbalance=random"
                urlBuilder.append(toParameterString(item));

                // urlBuilder追加 "&application=demo-consumer"
                urlBuilder.append("&application=").append(config.getKey());

                // urlBuilder后追加串 "&enabled={enabled}"
                parseEnabled(item, config, urlBuilder);

                // urlBuilder后追加串 "&category=appdynamicconfigurators"
                urlBuilder.append("&category=").append(Constants.APP_DYNAMIC_CONFIGURATORS_CATEGORY);

                // urlBuilder后追加串 "&configVersion=v2.7"
                urlBuilder.append("&configVersion=").append(config.getConfigVersion());

                // 将urlBuilder转成url对象
                urls.add(URL.valueOf(urlBuilder.toString()));
            }
        }
        return urls;
    }
    // 返回字符串
    // sb= "category=dynamicconfigurators&timeout=6666"
    private static String toParameterString(ConfigItem item) {
        StringBuilder sb = new StringBuilder();
        // sb="category=dynamicconfigurators"
        sb.append("category=");
        sb.append(Constants.DYNAMIC_CONFIGURATORS_CATEGORY);
        // 若入参的side有值
        if (item.getSide() != null) {
            // sb="category=dynamicconfigurators&side={side}"
            sb.append("&side=");
            sb.append(item.getSide());
        }
        Map<String, String> parameters = item.getParameters();
        if (CollectionUtils.isEmptyMap(parameters)) {
            throw new IllegalStateException("Invalid configurator rule, please specify at least one parameter " +
                    "you want to change in the rule.");
        }
        // sb="category=dynamicconfigurators&timeout=6666"
        parameters.forEach((k, v) -> {
            sb.append("&");
            sb.append(k);
            sb.append("=");
            sb.append(v);
        });

        // 若入参的providerAddresses属性有值
        if (CollectionUtils.isNotEmpty(item.getProviderAddresses())) {
            // sb= "category=dynamicconfigurators&timeout=6666&providerAddresses=192.168.1.12，192.168.1.13"
            sb.append("&");
            sb.append(Constants.OVERRIDE_PROVIDERS_KEY);
            sb.append("=");
            sb.append(CollectionUtils.join(item.getProviderAddresses(), ","));
        }

        return sb.toString();
    }

    // key: 类似字符串 "{group}/{interfaceName}:{version}"
    // 例如：groupA/com.its.test.HelloService:1.0.0
    //  返回串，
    //  若入参中含有{group}和{version}，则返回 sb="com.its.test.HelloService?group=groupA&version=1.0.0&"
    //  若不含有，则返回 sb="com.its.test.HelloService?"
    private static String appendService(String serviceKey) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isEmpty(serviceKey)) {
            throw new IllegalStateException("service field in configuration is null.");
        }

        String interfaceName = serviceKey;
        int i = interfaceName.indexOf("/");
        // 若有group
        if (i > 0) {
            // sb="group=groupA&"
            sb.append("group=");
            sb.append(interfaceName, 0, i);
            sb.append("&");

            // interfaceName="com.its.test.HelloService:1.0.0"
            interfaceName = interfaceName.substring(i + 1);
        }
        int j = interfaceName.indexOf(":");
        // 若有版本号
        if (j > 0) {
            // sb="group=groupA&version=1.0.0&"
            sb.append("version=");
            sb.append(interfaceName.substring(j + 1));
            sb.append("&");
            // interfaceName="com.its.test.HelloService"
            interfaceName = interfaceName.substring(0, j);
        }
        // 最终串
        // sb="com.its.test.HelloService?group=groupA&version=1.0.0&"
        sb.insert(0, interfaceName + "?");

        return sb.toString();
    }

    // 入参urlBuilder后追加串 "&enabled={enabled}"
    private static void parseEnabled(ConfigItem item, ConfiguratorConfig config, StringBuilder urlBuilder) {
        urlBuilder.append("&enabled=");
        if (item.getType() == null || ConfigItem.GENERAL_TYPE.equals(item.getType())) {
            urlBuilder.append(config.getEnabled());
        } else {
            urlBuilder.append(item.getEnabled());
        }
    }

    // 获取入参item中的addresses属性值，若为空，则返回含有"0.0.0.0"的集合
    private static List<String> parseAddresses(ConfigItem item) {
        List<String> addresses = item.getAddresses();
        if (addresses == null) {
            addresses = new ArrayList<>();
        }
        if (addresses.size() == 0) {
            // addresses中添加 "0.0.0.0"
            addresses.add(Constants.ANYHOST_VALUE);
        }
        return addresses;
    }
}
