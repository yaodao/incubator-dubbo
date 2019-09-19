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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.Constants.CATEGORY_KEY;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.Constants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.Constants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.Constants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.Constants.ROUTE_PROTOCOL;

public class UrlUtils {

    /**
     *  in the url string,mark the param begin
     */
    private final static String URL_PARAM_STARTING_SYMBOL = "?";

    // 这个函数作用就是解析address 解析得到的元素用于构造URL对象; URL对象的某个属性, 若在address中没有给值, 则使用defaults中的默认值
    public static URL parseURL(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }
        String url;
        // address例子1:  "remote://root:alibaba@127.0.0.1:9090/dubbo.test.api"
        //例子2:  "127.0.0.1:2181?backup=127.0.0.1:2182,127.0.0.1:2183";
        if (address.contains("://") || address.contains(URL_PARAM_STARTING_SYMBOL)) {
            url = address;
        } else {
            // 逗号分开的多个地址
            // 例如: "192.168.0.1,192.168.0.2,192.168.0.3";
            String[] addresses = Constants.COMMA_SPLIT_PATTERN.split(address);
            url = addresses[0];
            if (addresses.length > 1) {
                // 若有多个地址
                StringBuilder backup = new StringBuilder();
                for (int i = 1; i < addresses.length; i++) {
                    if (i > 1) {
                        backup.append(",");
                    }
                    backup.append(addresses[i]);
                }
                // url="address0?backup=address1,address2"
                url += URL_PARAM_STARTING_SYMBOL + Constants.BACKUP_KEY + "=" + backup.toString();
            }
        }
        // 从参数defaults中获取默认值
        String defaultProtocol = defaults == null ? null : defaults.get(Constants.PROTOCOL_KEY);
        if (defaultProtocol == null || defaultProtocol.length() == 0) {
            // defaultProtocol = "dubbo"
            defaultProtocol = Constants.DUBBO_PROTOCOL;
        }
        String defaultUsername = defaults == null ? null : defaults.get(Constants.USERNAME_KEY);
        String defaultPassword = defaults == null ? null : defaults.get(Constants.PASSWORD_KEY);
        int defaultPort = StringUtils.parseInteger(defaults == null ? null : defaults.get(Constants.PORT_KEY));
        // 这个path感觉就是api层的包名
        String defaultPath = defaults == null ? null : defaults.get(Constants.PATH_KEY);
        Map<String, String> defaultParameters = defaults == null ? null : new HashMap<String, String>(defaults);
        // 上面是给变量赋默认值, 这里把默认值从map里移除, 方便后面加入自定义值
        if (defaultParameters != null) {
            defaultParameters.remove(Constants.PROTOCOL_KEY);
            defaultParameters.remove(Constants.USERNAME_KEY);
            defaultParameters.remove(Constants.PASSWORD_KEY);
            defaultParameters.remove(Constants.HOST_KEY);
            defaultParameters.remove(Constants.PORT_KEY);
            defaultParameters.remove(Constants.PATH_KEY);
        }
        // 解析url中的参数, 有 protocol, username, password, host, port, path, parameters,
        URL u = URL.valueOf(url);
        boolean changed = false;
        String protocol = u.getProtocol();
        String username = u.getUsername();
        String password = u.getPassword();
        String host = u.getHost();
        int port = u.getPort();
        String path = u.getPath();
        Map<String, String> parameters = new HashMap<String, String>(u.getParameters());
        // 解析url得到的值是空, 而默认值不空, 则取默认值
        if ((protocol == null || protocol.length() == 0) && defaultProtocol != null && defaultProtocol.length() > 0) {
            changed = true;
            protocol = defaultProtocol;
        }
        if ((username == null || username.length() == 0) && defaultUsername != null && defaultUsername.length() > 0) {
            changed = true;
            username = defaultUsername;
        }
        if ((password == null || password.length() == 0) && defaultPassword != null && defaultPassword.length() > 0) {
            changed = true;
            password = defaultPassword;
        }
        /*if (u.isAnyHost() || u.isLocalHost()) {
            changed = true;
            host = NetUtils.getLocalHost();
        }*/
        if (port <= 0) {
            if (defaultPort > 0) {
                changed = true;
                port = defaultPort;
            } else {
                changed = true;
                port = 9090;
            }
        }
        if (path == null || path.length() == 0) {
            if (defaultPath != null && defaultPath.length() > 0) {
                changed = true;
                path = defaultPath;
            }
        }
        // 解析url得到的值是空, 默认值不空, 则取默认值
        if (defaultParameters != null && defaultParameters.size() > 0) {
            for (Map.Entry<String, String> entry : defaultParameters.entrySet()) {
                String key = entry.getKey();
                String defaultValue = entry.getValue();
                if (defaultValue != null && defaultValue.length() > 0) {
                    // parameters是解析url得到的参数map
                    String value = parameters.get(key);
                    if (StringUtils.isEmpty(value)) {
                        changed = true;
                        // 将(key, 默认值)加到parameters中
                        parameters.put(key, defaultValue);
                    }
                }
            }
        }
        if (changed) {
            u = new URL(protocol, username, password, host, port, path, parameters);
        }
        return u;
    }

    // 由参数address和defaults，构造出url对象
    public static List<URL> parseURLs(String address, Map<String, String> defaults) {
        // address形如 "192.168.0.1|192.168.0.2|192.168.0.3";
        if (address == null || address.length() == 0) {
            return null;
        }
        String[] addresses = Constants.REGISTRY_SPLIT_PATTERN.split(address);
        if (addresses == null || addresses.length == 0) {
            return null; //here won't be empty
        }
        List<URL> registries = new ArrayList<URL>();
        for (String addr : addresses) {
            registries.add(parseURL(addr, defaults));
        }
        // registries中存放的是URL的集合
        return registries;
    }

    // 参数register中key是serviceName 类似: "dubbo.test.api.HelloService"
    // value的entry类似: ("dubbo://127.0.0.1:20880/com.xxx.XxxService", "version=1.0.0&group=test&dubbo.version=2.0.0")

    // 返回值的map, key是"test/dubbo.test.api.HelloService:1.0.0" 是group/serviceName:version，
    // value和入参的value一样
    // 也是key为"dubbo://127.0.0.1:20880/com.xxx.XxxService", value为"version=1.0.0&group=test&dubbo.version=2.0.0"

    // 这个函数的作用是把参数register的key从"dubbo.test.api.HelloService" 改成 "test/dubbo.test.api.HelloService:1.0.0" 即group/serviceName:version ,其它不变
    // 也就是参数register 和返回值 newRegister 除了key不同, 其它都一样
    public static Map<String, Map<String, String>> convertRegister(Map<String, Map<String, String>> register) {
        Map<String, Map<String, String>> newRegister = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : register.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, String> serviceUrls = entry.getValue();
            // serviceName不包含冒号和斜线 (从函数最后的返回结果来看, 包含冒号和斜线的都是已经处理完成的serviceName)
            if (!serviceName.contains(":") && !serviceName.contains("/")) {
                for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                    String serviceUrl = entry2.getKey();
                    String serviceQuery = entry2.getValue();
                    // 将value中的 "version=1.0.0&group=test&dubbo.version=2.0.0" 分解成map, key是参数名 value是参数值
                    Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                    String group = params.get("group");
                    String version = params.get("version");
                    //params.remove("group");
                    //params.remove("version");
                    // name类似 "dubbo.test.api.HelloService"
                    String name = serviceName;
                    if (group != null && group.length() > 0) {
                        name = group + "/" + name;
                    }
                    if (version != null && version.length() > 0) {
                        name = name + ":" + version;
                    }
                    // name为 "test/dubbo.test.api.HelloService:1.0.0" 是group/serviceName:version
                    Map<String, String> newUrls = newRegister.get(name);
                    if (newUrls == null) {
                        newUrls = new HashMap<String, String>();
                        // key为 "test/dubbo.test.api.HelloService:1.0.0" 是group/serviceName:version， value为map
                        newRegister.put(name, newUrls);
                    }
                    // key为"dubbo://127.0.0.1:20880/com.xxx.XxxService", value为params组成的字符串， value可以直接取serviceQuery的值, 因为params没有任何变化
                    newUrls.put(serviceUrl, StringUtils.toQueryString(params));
                }
            } else {
                newRegister.put(serviceName, serviceUrls);
            }
        }
        return newRegister;
    }

    // 参数subscribe中key是serviceName 类似: "dubbo.test.api.HelloService"  value是"version=1.0.0&group=test&dubbo.version=2.0.0"
    // 返回的结果map中key是"test/dubbo.test.api.HelloService:1.0.0" 是group/serviceName:version, value不变
    public static Map<String, String> convertSubscribe(Map<String, String> subscribe) {
        Map<String, String> newSubscribe = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : subscribe.entrySet()) {
            String serviceName = entry.getKey();
            String serviceQuery = entry.getValue();
            if (!serviceName.contains(":") && !serviceName.contains("/")) {
                Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                String group = params.get("group");
                String version = params.get("version");
                //params.remove("group");
                //params.remove("version");
                String name = serviceName;
                if (group != null && group.length() > 0) {
                    name = group + "/" + name;
                }
                if (version != null && version.length() > 0) {
                    name = name + ":" + version;
                }
                newSubscribe.put(name, StringUtils.toQueryString(params));
            } else {
                newSubscribe.put(serviceName, serviceQuery);
            }
        }
        return newSubscribe;
    }

    // 参数register的key是"test/dubbo.test.api.HelloService:1.0.0" 即 group/serviceName:version
    // value的entry类似: ("dubbo://127.0.0.1:20880/com.xxx.XxxService", "version=1.0.0&group=test&dubbo.version=2.0.0")

    // 返回值的key为 "dubbo.test.api.HelloService"
    // value的entry为 ("dubbo://127.0.0.1:20880/com.xxx.XxxService", "version=1.0.0&group=test&dubbo.version=2.0.0")
    // entry后面的value值由解析出的group和version值来决定是否有变化， 如果和参数key中的一样, 则没有变化
    public static Map<String, Map<String, String>> revertRegister(Map<String, Map<String, String>> register) {
        Map<String, Map<String, String>> newRegister = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : register.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, String> serviceUrls = entry.getValue();
            if (serviceName.contains(":") || serviceName.contains("/")) {
                for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                    String serviceUrl = entry2.getKey();
                    String serviceQuery = entry2.getValue();
                    Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                    String name = serviceName;
                    int i = name.indexOf('/');
                    if (i >= 0) {
                        params.put("group", name.substring(0, i));
                        name = name.substring(i + 1);
                    }
                    i = name.lastIndexOf(':');
                    if (i >= 0) {
                        params.put("version", name.substring(i + 1));
                        name = name.substring(0, i);
                    }
                    Map<String, String> newUrls = newRegister.get(name);
                    if (newUrls == null) {
                        newUrls = new HashMap<String, String>();
                        newRegister.put(name, newUrls);
                    }
                    // value值由前面解析出的group和version值来决定是否有变化
                    newUrls.put(serviceUrl, StringUtils.toQueryString(params));
                }
            } else {
                newRegister.put(serviceName, serviceUrls);
            }
        }
        return newRegister;
    }

    // 参数subscribe的key是"test/dubbo.test.api.HelloService:1.0.0" 即 group/serviceName:version
    // value是 "version=1.0.0&group=test&dubbo.version=2.0.0"

    // 返回值的key为 "dubbo.test.api.HelloService"
    // value为 "version=1.0.0&group=test&dubbo.version=2.0.0"
    // value值由key解析出的group和version值来决定是否有变化， 如果和参数key中的一样, 则没有变化
    public static Map<String, String> revertSubscribe(Map<String, String> subscribe) {
        Map<String, String> newSubscribe = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : subscribe.entrySet()) {
            String serviceName = entry.getKey();
            String serviceQuery = entry.getValue();
            if (serviceName.contains(":") || serviceName.contains("/")) {
                Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                String name = serviceName;
                int i = name.indexOf('/');
                if (i >= 0) {
                    params.put("group", name.substring(0, i));
                    name = name.substring(i + 1);
                }
                i = name.lastIndexOf(':');
                if (i >= 0) {
                    params.put("version", name.substring(i + 1));
                    name = name.substring(0, i);
                }
                newSubscribe.put(name, StringUtils.toQueryString(params));
            } else {
                newSubscribe.put(serviceName, serviceQuery);
            }
        }
        return newSubscribe;
    }

    // 参数notify中key是serviceName 类似: "dubbo.test.api.HelloService"
    // value的entry类似: ("dubbo://127.0.0.1:20880/com.xxx.XxxService", "version=1.0.0&group=test&dubbo.version=2.0.0")

    // 返回值的map, key是"test/dubbo.test.api.HelloService:1.0.0" 是group/serviceName:version，
    // value和入参的value一样, 没有变化
    // 也是key为"dubbo://127.0.0.1:20880/com.xxx.XxxService", value为"version=1.0.0&group=test&dubbo.version=2.0.0"
    public static Map<String, Map<String, String>> revertNotify(Map<String, Map<String, String>> notify) {
        if (notify != null && notify.size() > 0) {
            Map<String, Map<String, String>> newNotify = new HashMap<String, Map<String, String>>();
            for (Map.Entry<String, Map<String, String>> entry : notify.entrySet()) {
                String serviceName = entry.getKey();
                Map<String, String> serviceUrls = entry.getValue();
                if (!serviceName.contains(":") && !serviceName.contains("/")) {
                    if (serviceUrls != null && serviceUrls.size() > 0) {
                        for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                            String url = entry2.getKey();
                            String query = entry2.getValue();
                            Map<String, String> params = StringUtils.parseQueryString(query);
                            String group = params.get("group");
                            String version = params.get("version");
                            // params.remove("group");
                            // params.remove("version");
                            String name = serviceName;
                            if (group != null && group.length() > 0) {
                                name = group + "/" + name;
                            }
                            if (version != null && version.length() > 0) {
                                name = name + ":" + version;
                            }
                            Map<String, String> newUrls = newNotify.get(name);
                            if (newUrls == null) {
                                newUrls = new HashMap<String, String>();
                                newNotify.put(name, newUrls);
                            }
                            newUrls.put(url, StringUtils.toQueryString(params));
                        }
                    }
                } else {
                    newNotify.put(serviceName, serviceUrls);
                }
            }
            return newNotify;
        }
        return notify;
    }

    //compatible for dubbo-2.0.0
    public static List<String> revertForbid(List<String> forbid, Set<URL> subscribed) {
        if (CollectionUtils.isNotEmpty(forbid)) {
            List<String> newForbid = new ArrayList<String>();
            for (String serviceName : forbid) {
                if (!serviceName.contains(":") && !serviceName.contains("/")) {
                    for (URL url : subscribed) {
                        // 这里意思是若serviceName还没有经过处理 就进入if
                        if (serviceName.equals(url.getServiceInterface())) {
                            // 使用url对象中的成员变量构造字符串 "{group}/{interfaceName}:{version}", 之后添加到newForbid
                            newForbid.add(url.getServiceKey());
                            break;
                        }
                    }
                } else {
                    newForbid.add(serviceName);
                }
            }
            return newForbid;
        }
        return forbid;
    }

    public static URL getEmptyUrl(String service, String category) {
        String group = null;
        String version = null;
        int i = service.indexOf('/');
        if (i > 0) {
            group = service.substring(0, i);
            service = service.substring(i + 1);
        }
        i = service.lastIndexOf(':');
        if (i > 0) {
            version = service.substring(i + 1);
            service = service.substring(0, i);
        }
        return URL.valueOf(Constants.EMPTY_PROTOCOL + "://0.0.0.0/" + service + URL_PARAM_STARTING_SYMBOL
                + CATEGORY_KEY + "=" + category
                + (group == null ? "" : "&" + Constants.GROUP_KEY + "=" + group)
                + (version == null ? "" : "&" + Constants.VERSION_KEY + "=" + version));
    }

    // 判断category是否为categories的子串, true 是
    public static boolean isMatchCategory(String category, String categories) {
        if (categories == null || categories.length() == 0) {
            // 若入参categories是空, 则判断category是否等于 "providers"
            return DEFAULT_CATEGORY.equals(category);
        } else if (categories.contains(Constants.ANY_VALUE)) {
            return true;
        } else if (categories.contains(Constants.REMOVE_VALUE_PREFIX)) {
            // "-"表示移除的意思,  也就是如果categories包含有 "-category" 的字符串, 被认为是不包含
            return !categories.contains(Constants.REMOVE_VALUE_PREFIX + category);
        } else {
            return categories.contains(category);
        }
    }

    // 判断consumerUrl和providerUrl中的以下几个元素是否相同,  都相同返回true， 有一个不同返回false
    // Interface， category， group， version， classifier
    public static boolean isMatch(URL consumerUrl, URL providerUrl) {
        String consumerInterface = consumerUrl.getServiceInterface();
        String providerInterface = providerUrl.getServiceInterface();

        // consumerInterface和providerInterface若相等则继续向下执行， 否则函数直接返回false
        // 若其中任一个是星号则继续向下执行， 否则函数直接返回false
        if (!(Constants.ANY_VALUE.equals(consumerInterface)
                || Constants.ANY_VALUE.equals(providerInterface)
                || StringUtils.isEquals(consumerInterface, providerInterface))) {
            return false;
        }

        // providerUrl中"category"对应的值 不是 consumerUrl中"category"对应值的子串, 函数直接返回false， 是子串 则继续向下
        if (!isMatchCategory(providerUrl.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY),
                consumerUrl.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY))) {
            return false;
        }
        // providerUrl中"enabled"值为false 且 consumerUrl中"enabled"值不为 "*", 函数直接返回false
        if (!providerUrl.getParameter(Constants.ENABLED_KEY, true)
                && !Constants.ANY_VALUE.equals(consumerUrl.getParameter(Constants.ENABLED_KEY))) {
            return false;
        }

        // 取url中key="group"对应的值
        String consumerGroup = consumerUrl.getParameter(Constants.GROUP_KEY);
        // 取url中key="version"对应的值
        String consumerVersion = consumerUrl.getParameter(Constants.VERSION_KEY);
        // 取url中key="classifier"对应的值
        String consumerClassifier = consumerUrl.getParameter(Constants.CLASSIFIER_KEY, Constants.ANY_VALUE);

        String providerGroup = providerUrl.getParameter(Constants.GROUP_KEY);
        String providerVersion = providerUrl.getParameter(Constants.VERSION_KEY);
        String providerClassifier = providerUrl.getParameter(Constants.CLASSIFIER_KEY, Constants.ANY_VALUE);

        // 若consumerGroup和providerGroup相同 且 consumerVersion和providerVersion相同 且 consumerClassifier和providerClassifier相同，则返回true，有一对不相同，就返回false
        // 若consumerGroup, consumerClassifier ，consumerVersion 中任意一个是星号， 则看做它和对应的providerGroup/Classifier/Version相等
        return (Constants.ANY_VALUE.equals(consumerGroup) || StringUtils.isEquals(consumerGroup, providerGroup) || StringUtils.isContains(consumerGroup, providerGroup))
                && (Constants.ANY_VALUE.equals(consumerVersion) || StringUtils.isEquals(consumerVersion, providerVersion))
                && (consumerClassifier == null || Constants.ANY_VALUE.equals(consumerClassifier) || StringUtils.isEquals(consumerClassifier, providerClassifier));
    }

    // 判断value是否匹配pattern （支持pattern中的*通配符）
    // 入参param的作用是，当入参pattern是一个key值时（以"$"开头的字符串，往往被看做是一个变量名，而不是一个具体的值）
    // 所以，还需要取param中该key对应的value做pattern。 入参param是消费者url
    public static boolean isMatchGlobPattern(String pattern, String value, URL param) {
        // 若入参param不空 且 pattern串以"$"开头
        if (param != null && pattern.startsWith("$")) {
            // 从消费者url中取pattern值，覆盖入参pattern
            pattern = param.getRawParameter(pattern.substring(1));
        }

        // 判断value是否匹配pattern, 是返回true
        return isMatchGlobPattern(pattern, value);
    }

    // 判断value是否匹配pattern, 匹配返回true （支持pattern中的*通配符）
    // 1、若pattern是"*" 返回true
    // 2、若pattern和value都为空, 则返回true
    // 3、若pattern和value只有一个为空, 则返回false
    // 4、若pattern和value值相等，返回true
    // 5、若pattern中包含"*", 则判断pattern中星前的字符 和星后的字符 是否是value的前缀和后缀 ，是则返回true
    // (这就相当于把星号解析为任意多个字符)
    public static boolean isMatchGlobPattern(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        // pattern和value都为空, 则返回true
        if (StringUtils.isEmpty(pattern) && StringUtils.isEmpty(value)) {
            return true;
        }
        // pattern和value只有一个为空, 则返回false
        if (StringUtils.isEmpty(pattern) || StringUtils.isEmpty(value)) {
            return false;
        }

        // 取星号最后出现的位置
        int i = pattern.lastIndexOf('*');
        // doesn't find "*"
        if (i == -1) {
            return value.equals(pattern);
        }
        // "*" is at the end
        else if (i == pattern.length() - 1) {
            return value.startsWith(pattern.substring(0, i));
        }
        // "*" is at the beginning
        else if (i == 0) {
            return value.endsWith(pattern.substring(i + 1));
        }
        // "*" is in the middle
        else {
            String prefix = pattern.substring(0, i);
            String suffix = pattern.substring(i + 1);
            return value.startsWith(prefix) && value.endsWith(suffix);
        }
    }

    // 比较pattern和value中 "interface", "group", "version" 这三项的值是否相同 （pattern和value都是URL对象）
    public static boolean isServiceKeyMatch(URL pattern, URL value) {
        return pattern.getParameter(Constants.INTERFACE_KEY).equals(
                value.getParameter(Constants.INTERFACE_KEY))
                && isItemMatch(pattern.getParameter(Constants.GROUP_KEY),
                value.getParameter(Constants.GROUP_KEY))
                && isItemMatch(pattern.getParameter(Constants.VERSION_KEY),
                value.getParameter(Constants.VERSION_KEY));
    }

    // 按参数predicate过滤url
    public static List<URL> classifyUrls(List<URL> urls, Predicate<URL> predicate) {
        return urls.stream().filter(predicate).collect(Collectors.toList());
    }

    public static boolean isConfigurator(URL url) {
        return OVERRIDE_PROTOCOL.equals(url.getProtocol()) ||
                CONFIGURATORS_CATEGORY.equals(url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY));
    }

    public static boolean isRoute(URL url) {
        return ROUTE_PROTOCOL.equals(url.getProtocol()) ||
                ROUTERS_CATEGORY.equals(url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY));
    }

    public static boolean isProvider(URL url) {
        return !OVERRIDE_PROTOCOL.equals(url.getProtocol()) &&
                !ROUTE_PROTOCOL.equals(url.getProtocol()) &&
                PROVIDERS_CATEGORY.equals(url.getParameter(CATEGORY_KEY, PROVIDERS_CATEGORY));
    }

    public static int getHeartbeat(URL url) {
        // 取url中的"heartbeat"值, 默认为60s
        return url.getParameter(Constants.HEARTBEAT_KEY, Constants.DEFAULT_HEARTBEAT);
    }

    // 从url中取"heartbeat.timeout"值, 若值< 2倍的心跳时间则抛出异常
    public static int getIdleTimeout(URL url) {
        int heartBeat = getHeartbeat(url);
        int idleTimeout = url.getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartBeat * 3);
        if (idleTimeout < heartBeat * 2) {
            throw new IllegalStateException("idleTimeout < heartbeatInterval * 2");
        }
        return idleTimeout;
    }

    /**
     * Check if the given value matches the given pattern. The pattern supports wildcard "*".
     *
     * @param pattern pattern
     * @param value   value
     * @return true if match otherwise false
     */
    // 判断patternn和value是否相同, 支持通配符"*"
    static boolean isItemMatch(String pattern, String value) {
        if (pattern == null) {
            return value == null;
        } else {
            return "*".equals(pattern) || pattern.equals(value);
        }
    }
}
