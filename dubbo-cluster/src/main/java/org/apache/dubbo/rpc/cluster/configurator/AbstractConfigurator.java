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

    // 配置url
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
    // 为入参url的parameters属性新增或者修改entry （使用成员变量configuratorUrl的parameters属性值来搞）
    // 详细情况见 本函数中调用的configureIfMatch()方法的说明
    public URL configure(URL url) {
        // 若configuratorUrl的"enabled"属性值为false，直接返回url； 若url/configuratorUrl的host属性值为空，直接返回url；
        if (!configuratorUrl.getParameter(Constants.ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /**
         * This if branch is created since 2.7.0.
         */
        // 使用configuratorUrl来增强url对象的parameters属性值

        // 取configuratorUrl对象的"configVersion"属性值，若值不空，则进if
        String apiVersion = configuratorUrl.getParameter(Constants.CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            // 取入参url的"side"值
            String currentSide = url.getParameter(Constants.SIDE_KEY);
            // 取configuratorUrl的"side"值
            String configuratorSide = configuratorUrl.getParameter(Constants.SIDE_KEY);

            // 若currentSide与configuratorSide相等  且  configuratorSide="consumer"  且 configuratorUrl的port属性为0
            if (currentSide.equals(configuratorSide) && Constants.CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                // 为url的parameters属性新增或者修改entry
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            }
            // 若currentSide与configuratorSide相等  且 configuratorSide="provider"  且入参url和configuratorUrl的port值相等
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

    /**
     * 该方法主要实现的功能是：
     *  1）先收集不能动态修改的属性，
     *     不能动态修改的属性主要包括：category、check、dynamic、enabled、还有以波浪线开头的属性，
     *  2）对波浪线开头的属性，如果配置URL中的属性值与原URL的属性值不相同，则不使用该配置URL重写原URL，函数直接返回原URL
     *  3）将配置URL移除不支持动态修改的属性后，调用doConfigure方法覆盖入参url的属性
     *
     * 配置URL  就是  成员变量configuratorUrl
     * 配置URL  就是  成员变量configuratorUrl
     * 配置URL  就是  成员变量configuratorUrl
     *
     * Dubbo默认支持如下覆盖策略：
     * override 直接覆盖。
     * absent，如果原先存在该属性的配置，则以原先配置的属性值优先，如果原先没有配置该属性，则添加新的配置属性。
     *
     */
    // 大体上的操作是： 使用配置url中的parameters属性，给参数url的parameters属性增加或者覆盖entry，返回修改后的url
    // 具体看上面api说明
    private URL configureIfMatch(String host, URL url) {
        // 若配置url的host值为"0.0.0.0"  或者等于入参host，进入if
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // 取配置url的"providerAddresses"属性值
            String providers = configuratorUrl.getParameter(Constants.OVERRIDE_PROVIDERS_KEY);

            // 若providers为空 或者 providers包含入参url的host值 或者  providers包含"0.0.0.0" 进入if
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(Constants.ANYHOST_VALUE)) {
                // 取配置url的"application"属性值
                String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY,
                        configuratorUrl.getUsername());
                // 取url的"application"属性值
                String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());

                // 若configApplication是"*"  或者 configApplication与currentApplication相等，则进if
                if (configApplication == null || Constants.ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {

                    // 不能动态配置的属性名
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

                    // 遍历 配置url的parameters属性 （配置url就是configuratorUrl对象）
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        // 若key以波浪线开头，将key加入conditionKeys （key="application" ，key="side"，已经在conditionKeys中了）
                        if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                            conditionKeys.add(key);
                            // 若配置url中，该key对应的value值 与原url的属性值不相等， 则函数直接返回原url（表示带波浪线的属性名不能被动态修改）
                            if (value != null && !Constants.ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    // 先从配置URL的parameters属性中移除 不支持动态修改的属性，
                    // 再调用子类的doConfigure方法，使用配置URL中的parameters属性，为入参url的parameters属性 新增或者覆盖entry
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
