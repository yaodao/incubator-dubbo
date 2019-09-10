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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    // 示例 "override://10.20.153.10/com.foo.BarService?timeout=200"
    // 感觉这个对象就是用于增强与自己匹配的 url对象的parameters属性值
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    @Override
    // 使用成员变量configuratorUrl的parameters属性值来增强入参url对象的parameters属性值
    public URL configure(URL url) {
        // If override url is not enabled or is invalid, just return.
        // 若url/configuratorUrl的host为空，直接返回url； 若configuratorUrl的"enabled"属性值为true，直接返回url
        if (!configuratorUrl.getParameter(Constants.ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /**
         * This if branch is created since 2.7.0.
         */
        // 使用configuratorUrl来增强url对象的parameters属性值
        // 取configuratorUrl对象的"configVersion"属性值
        String apiVersion = configuratorUrl.getParameter(Constants.CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            // 取入参url的"side"值
            String currentSide = url.getParameter(Constants.SIDE_KEY);
            // 取configuratorUrl的"side"值
            String configuratorSide = configuratorUrl.getParameter(Constants.SIDE_KEY);
            // 若configuratorSide="consumer"  且 configuratorSide与currentSide相等
            if (currentSide.equals(configuratorSide) && Constants.CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                // 使用成员变量configuratorUrl的属性值来增强url的parameters值
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } // 若configuratorSide="provider" 且 configuratorSide与currentSide相等  且入参url和configuratorUrl的port值相等
            else if (currentSide.equals(configuratorSide) && Constants.PROVIDER.equals(configuratorSide) && url.getPort() == configuratorUrl.getPort()) {
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /**
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        return url;
    }

    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {// override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider
            if (url.getParameter(Constants.SIDE_KEY, Constants.PROVIDER).equals(Constants.CONSUMER)) {
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.
            } else if (url.getParameter(Constants.SIDE_KEY, Constants.CONSUMER).equals(Constants.PROVIDER)) {
                return configureIfMatch(Constants.ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    // 使用成员变量configuratorUrl的属性值来增强url
    // 就是根据成员变量configuratorUrl的属性值来判断是否使用它的parameters属性值，来给参数url的parameters属性增加entry
    private URL configureIfMatch(String host, URL url) {
        // 若成员变量configuratorUrl的host值为"0.0.0.0"  或者等于入参host，进入if
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            // 取成员变量configuratorUrl的"providerAddresses"属性值
            String providers = configuratorUrl.getParameter(Constants.OVERRIDE_PROVIDERS_KEY);
            // providers为空 或者 providers包含入参url的host值 或者  providers包含"0.0.0.0" 进入if
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(Constants.ANYHOST_VALUE)) {
                // 取configuratorUrl的"application"属性值，默认值为它的"username"属性值
                String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY,
                        configuratorUrl.getUsername());
                // 取url的"application"属性值，默认值为它的"username"属性值
                String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());
                // 若configApplication是"*"  或者 configApplication与currentApplication相等，则进if
                if (configApplication == null || Constants.ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    Set<String> conditionKeys = new HashSet<String>();
                    conditionKeys.add(Constants.CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(Constants.DYNAMIC_KEY);
                    conditionKeys.add(Constants.ENABLED_KEY);
                    conditionKeys.add(Constants.GROUP_KEY);
                    conditionKeys.add(Constants.VERSION_KEY);
                    conditionKeys.add(Constants.APPLICATION_KEY);
                    conditionKeys.add(Constants.SIDE_KEY);
                    conditionKeys.add(Constants.CONFIG_VERSION_KEY);
                    // 将configuratorUrl的parameters属性值中的某些特定key，加入conditionKeys
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        // 若key以波浪线开头  或者 key="application" 或者key="side"，将key加入conditionKeys
                        if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                            // 将key添加到conditionKeys
                            conditionKeys.add(key);
                            // 若value不是"*"，且不是url的属性值， 则返回url
                            if (value != null && !Constants.ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    // 重新生成一个url并返回
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
