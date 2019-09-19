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
package org.apache.dubbo.rpc.cluster.router.condition;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter
 *
 */
public class ConditionRouter extends AbstractRouter {
    public static final String NAME = "condition";

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);

    // 第一个小括号内的表达式用于匹配"&", "!", "=" 和 "," 等符号。0次或多次
    // 中间是匹配任意数量的空格
    // 第二小括号 用于匹配英文字母，数字等字符。（就是匹配非中括号里的那些字符 1次或多次）
    protected static final Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");


    /**
     * 服务消费者的规则 被解析后得到的map
     * 举例：
     * 若服务消费者的规则串 "host = 2.2.2.2 & host != 1.1.1.1 & method = hello"
     * 解析后得到的map为
     * {
     *     "host": {
     *         "matches": ["2.2.2.2"],
     *         "mismatches": ["1.1.1.1"]
     *     },
     *     "method": {
     *         "matches": ["hello"],
     *         "mismatches": []
     *     }
     * }
     *
     *
     * 可以看出，
     * map的key是消费者的规则串中的参数名，
     * value是MatchPair对象，MatchPair对象的matches属性和mismatches属性中存放着规则串中的参数值
     */
    protected Map<String, MatchPair> whenCondition;

    // 服务提供者的规则 被解析后得到的map，
    protected Map<String, MatchPair> thenCondition;

    private boolean enabled;

    public ConditionRouter(String rule, boolean force, boolean enabled) {
        this.force = force;
        this.enabled = enabled;
        this.init(rule);
    }

    public ConditionRouter(URL url) {
        this.url = url;
        // 获取 priority 和 force 配置
        this.priority = url.getParameter(Constants.PRIORITY_KEY, 0);
        this.force = url.getParameter(Constants.FORCE_KEY, false);
        this.enabled = url.getParameter(Constants.ENABLED_KEY, true);
        // 获取路由规则
        init(url.getParameterAndDecoded(Constants.RULE_KEY));
    }

    // rule举例： "host = 2.2.2.2,1.1.1.1,3.3.3.3 & host !=1.1.1.1 => host = 1.2.3.4"
    // rule的前面是服务消费者的规则，后面是服务提供者的规则， =>是分隔符
    // 当满足*** 则***， 意思是当满足前面条件时，则服务提供者是"1.2.3.4"
    // 本函数就是解析规则后，给成员变量whenCondition， thenCondition赋值
    public void init(String rule) {
        try {
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }

            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            // 把rule用"=>"分隔成前后两部分，分别获取服务消费者和提供者匹配规则
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();

            // 解析服务消费者匹配规则
            // 若whenRule是空 或者是 "true"，则返回空的map，
            // 若whenRule有值，则解析whenRule，返回map
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);

            // 解析服务提供者匹配规则
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.

            // 将解析出的匹配规则分别赋值给 whenCondition 和 thenCondition 成员变量
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    //

    /**
     * 入参rule举例： "host = 2.2.2.2 & host != 1.1.1.1 & method = hello"
     *
     * 最后返回的map，如下，key是rule串中的参数名，value是MatchPair对象，存着rule串中的参数值
     * {
     *      "host": pair(matches:["2.2.2.2"]，mismatches:["1.1.1.1"]),
     *      "method":pair(matches:["hello"])
     * }
     * 其中 pair(matches:["2.2.2.2"]) 表示， pair对象的matches集合中的元素是"2.2.2.2"
     * 其实key就可以看做是一个关键字，value就是该关键字的可以取哪些值，不可以取哪些值。
     *
     * @param rule
     * @return
     * @throws ParseException
     */
    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        // key是rule串中的参数名， value是MatchPair对象，存着rule串中的参数值
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        // 通过正则表达式匹配路由规则，ROUTE_PATTERN = ([&!=,]*)\s*([^&!=,\s]+)
        // 第一个括号内的表达式用于匹配"&", "!", "=" 和 "," 等符号。
        // 第二括号内的用于匹配英文字母，数字等字符。举个例子说明一下：
        //    host = 2.2.2.2 & host != 1.1.1.1 & method = hello
        // 匹配结果如下：
        //     括号一      括号二
        // 1.  null       host
        // 2.   =         2.2.2.2
        // 3.   &         host
        // 4.   !=        1.1.1.1
        // 5.   &         method
        // 6.   =         hello
        //
        // 最后生成的map是
        // {
        //      "host": pair(matches:["2.2.2.2"]，mismatches:["1.1.1.1"]),
        //      "method":pair(matches:["hello"])
        // }
        // 其中 pair(matches:["2.2.2.2"]) 表示， pair对象的matches集合中的元素是"2.2.2.2"

        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        while (matcher.find()) { // Try to match one by one
            // 获取括号一内的匹配结果
            String separator = matcher.group(1);
            // 获取括号二内的匹配结果
            String content = matcher.group(2);

            // 分隔符为空，表示匹配的是表达式的开始部分
            if (StringUtils.isEmpty(separator)) {
                // 给pair赋值
                pair = new MatchPair();
                // condition中添加（"{content}"，pair对象）
                condition.put(content, pair);
            }
            // 如果分隔符为 &，表明接下来也是一个条件，
            // 一个条件对应一个MatchPair对象，&号就是多个条件之间的分隔符
            else if ("&".equals(separator)) {
                // 若condition中没有content对应的pair
                if (condition.get(content) == null) {
                    // new一个pair，添加到condition中
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    // 取已有的pair
                    pair = condition.get(content);
                }
            }
            // 分隔符为 =
            else if ("=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 这里的values就是matches集合， 将content存入到pair的matches集合中
                values = pair.matches;
                values.add(content);
            }
            //  分隔符为 !=
            else if ("!=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 这里的values就是mismatches集合，将content存入到pair的 mismatches 集合中
                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            // 分隔符为 ,
            else if (",".equals(separator)) { // Should be separated by ','
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                // 将 content 存入到上一步获取到的 values 中，可能是 matches，也可能是 mismatches
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    @Override
    // 为入参url选择一个服务提供者，并返回
    // 入参url可以理解为服务消费者。
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (!enabled) {
            return invokers;
        }

        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }
        try {
            // 先对入参url使用服务消费者的规则进行匹配，如果匹配失败，表明入参url不符合匹配规则，（就是没有匹配上路由规则）
            // 无需进行后续匹配，直接返回 Invoker 列表即可。比如下面的规则：
            //     host = 10.20.153.10 => host = 10.0.0.10
            // 这条路由规则希望 IP 为 10.20.153.10 的服务消费者调用 IP 为 10.0.0.10 机器上的服务。
            // 当消费者 ip 为 10.20.153.11 时，matchWhen 返回 false，表明当前这条路由规则不适用于
            // 当前的服务消费者，此时无需再进行后续匹配，直接返回即可。
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();

            // 能到这，说明入参url符合匹配规则，
            // 也就是入参url 需要使用路由规则，下一步就是路由到具体的服务提供者了。
            // 若服务提供者为空，则表示该入参url（服务消费者）在黑名单中。所以不给服务提供者列表。

            // 服务提供者匹配条件未配置，表明对指定的服务消费者禁用服务，也就是服务消费者在黑名单中
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            // 从invokers集合中找 路由规则then指定的那些服务提供者，添加到result中
            for (Invoker<T> invoker : invokers) {
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }

            // 当匹配结果不为空时，返回匹配结果result
            // 当匹配结果result为空 且 force = true，表示强制返回空列表（这个意思是，我就是要找匹配的结果，如果没有匹配上，就返回空集合给我）
            // 其他情况下，返回invokers
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        // 原样返回，此时 force = false，表示该条路由规则失效（就是返回的结果不是路由匹配的结果）
        return invokers;
    }

    @Override
    public boolean isRuntime() {
        // We always return true for previously defined Router, that is, old Router doesn't support cache anymore.
//        return true;
        return this.url.getParameter(Constants.RUNTIME_KEY, false);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    // 判断入参url是否满足使用路由的条件，true 满足
    // 入参url是服务消费者 url
    boolean matchWhen(URL url, Invocation invocation) {
        // 服务消费者条件为空，返回 true，比如： （注意  =>的左面为空串）
        //     => host != 172.22.3.91
        // 表示所有的服务消费者都不得调用 IP 为 172.22.3.91 的机器上的服务
        return CollectionUtils.isEmptyMap(whenCondition) || matchCondition(whenCondition, url, null, invocation);
    }

    // 判断入参url是否为 匹配的服务提供者
    // 入参url是服务提供者 url， 入参param是服务消费者 url
    private boolean matchThen(URL url, URL param) {
        // 服务提供者条件为 null 或空，表示禁用服务
        return CollectionUtils.isNotEmptyMap(thenCondition) && matchCondition(thenCondition, url, param, null);
    }

    // 验证入参url中的属性值，与入参condition中的 matchPair对象的 matches、mismatches集合中的元素是否能匹配上
    // 若属性值与 mismatches集合中的元素匹配，则返回false
    // 若属性值与 matches集合中的元素匹配，则返回true
    // 其实就是验证入参url 是否需要使用路由（根据url对象的属性值，判断其是否符合使用路由的条件，符合就使用路由）
    // 返回true 表示需要使用路由，返回false表示不使用路由
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        // 将服务提供者或消费者 url 转成 Map（即， 将入参url对象中的属性和属性值存到map）
        Map<String, String> sample = url.toMap();
        boolean result = false;
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            // 待验证的值（验证sampleValue是否和入参condition中的某个值匹配上）
            String sampleValue;

            // 从入参url中取key对应的属性值，赋给sampleValue
            // 若key="method" 或者 key="methods"
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            }// 若key="address"
            else if (Constants.ADDRESS_KEY.equals(key)) {
                sampleValue = url.getAddress();
            } // 若key="host"
            else if (Constants.HOST_KEY.equals(key)) {
                sampleValue = url.getHost();
            }// 若key为其他情况
            else {
                // 从sample中取key对应的值赋给sampleValue
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    // 从sample中取"default.{key}"对应的值赋给sampleValue
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }


            // 验证sampleValue
            // sampleValue不为空，则验证sampleValue的值与 matchPair对象的 matches、mismatches集合中的元素是否能匹配上
            if (sampleValue != null) {
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    // 若sampleValue与 mismatches集合中的元素匹配，则返回false
                    return false;
                } else {
                    // 若sampleValue与 matches集合中的元素匹配，则返回true
                    result = true;
                }
            }
            // sampleValue 为空，表示入参url中没有该key对应的属性值
            // 若这时，MatchPair的matches不为空，表示匹配失败，因为路由规则matches不是空
            else {
                // sampleValue 为空，表明服务提供者或消费者 url 中不包含相关字段。此时如果
                // MatchPair 的 matches 不为空，表示匹配失败，返回 false。比如我们有这样
                // 一条匹配条件 loadbalance = random，假设 url 中并不包含 loadbalance 参数，
                // 此时 sampleValue = null。既然路由规则里限制了 loadbalance 必须为 random，
                // 但 sampleValue = null，明显不符合规则，因此返回 false
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }

    protected static final class MatchPair {
        // 匹配条件，类似正则表达式的串 集合
        final Set<String> matches = new HashSet<String>();
        // 不匹配条件
        final Set<String> mismatches = new HashSet<String>();

        // 判断value是否匹配成员变量matches和mismatches中的元素 （这里的匹配类似正则中的匹配）
        // 匹配上matches中的元素则返回true， 匹配上mismatches中的元素则返回false，都没匹配上返回false。
        private boolean isMatch(String value, URL param) {
            // 情况一：matches不空 且 mismatches为空
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                // 遍历 matches 集合，检测入参 value 是否能被 matches 集合元素匹配到。
                // 举个例子，如果 value = 10.20.153.11，matches = [10.20.153.*],
                // 此时 isMatchGlobPattern 方法返回 true
                for (String match : matches) {
                    // 入参param只是在某些情况下用来辅助取得模式串match的
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                // 如果所有匹配项都无法匹配到入参value，则返回 false
                return false;
            }

            // 情况二：mismatches不空 且 matches为空
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    // 只要value被 mismatches 集合中的任意一个元素匹配到，就返回 false
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                return true;
            }

            // 情况三：matches和mismatches都不空
            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                // matches 和 mismatches 均为非空，此时优先使用 mismatches 集合元素对入参进行匹配。
                // 只要 mismatches 集合中任意一个元素与入参匹配成功，就立即返回 false，结束方法逻辑
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }

                // mismatches 集合元素无法匹配到入参，此时再使用 matches 继续匹配
                for (String match : matches) {
                    // 只要 matches 集合中任意一个元素与入参匹配成功，就立即返回 true
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                // 全部失配，则返回 false
                return false;
            }
            // 情况四：matches 和 mismatches 均为空，此时返回 false
            return false;
        }
    }
}
