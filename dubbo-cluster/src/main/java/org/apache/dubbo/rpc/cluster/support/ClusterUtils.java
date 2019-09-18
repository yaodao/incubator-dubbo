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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ClusterUtils
 */
public class ClusterUtils {

    private ClusterUtils() {
    }

    /**
     * 将remoteUrl的parameters属性和localMap合并，并生成一个新的url对象 返回
     * @param remoteUrl 服务提供者url
     * @param localMap 服务消费者url的parameters属性
     * @return
     */
    public static URL mergeUrl(URL remoteUrl, Map<String, String> localMap) {
        // 最后的结果map （就是最后返回的url的parameters属性值）
        Map<String, String> map = new HashMap<String, String>();
        // 获取入参remoteUrl的parameters属性
        Map<String, String> remoteMap = remoteUrl.getParameters();

        if (remoteMap != null && remoteMap.size() > 0) {
            // 将入参remoteUrl中的parameters添加到map
            map.putAll(remoteMap);

            // Remove configurations from provider, some items should be affected by provider.
            // 移除参数 "threadname" ，"default.threadname"
            map.remove(Constants.THREAD_NAME_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.THREAD_NAME_KEY);

            // 移除参数 "threadpool" ，"default.threadpool"
            map.remove(Constants.THREADPOOL_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.THREADPOOL_KEY);

            // 移除参数 "corethreads" ，"default.corethreads"
            map.remove(Constants.CORE_THREADS_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.CORE_THREADS_KEY);

            // 移除参数 "threads" ，"default.threads"
            map.remove(Constants.THREADS_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.THREADS_KEY);

            // 移除参数 "queues" ，"default.queues"
            map.remove(Constants.QUEUES_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.QUEUES_KEY);

            // 移除参数 "alive" ，"default.alive"
            map.remove(Constants.ALIVE_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.ALIVE_KEY);

            // 移除参数 "transporter" ，"default.transporter"
            map.remove(Constants.TRANSPORTER_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.TRANSPORTER_KEY);

            // 移除参数 "async" ，"default.async"
            map.remove(Constants.ASYNC_KEY);
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.ASYNC_KEY);

            // remove method async entry.
            // 移除map中以".async"结尾的key
            Set<String> methodAsyncKey = new HashSet<>();
            for (String key : map.keySet()) {
                if (key != null && key.endsWith("." + Constants.ASYNC_KEY)) {
                    methodAsyncKey.add(key);
                }
            }
            for (String needRemove : methodAsyncKey) {
                map.remove(needRemove);
            }
        }

        // 若入参localMap不为空，则将其元素添加到map中，但要保留map中"group"，"release"对应的原有值
        if (localMap != null && localMap.size() > 0) {
            // All providers come to here have been filtered by group, which means only those providers that have the exact same group value with the consumer could come to here.
            // So, generally, we don't need to care about the group value here.
            // But when comes to group merger, there is an exception, the consumer group may be '*' while the provider group can be empty or any other values.
            // 取map中"group"，"release"对应的值
            String remoteGroup = map.get(Constants.GROUP_KEY);
            String remoteRelease = map.get(Constants.RELEASE_KEY);
            // 把入参localMap中的值再添加到map中
            map.putAll(localMap);
            // 上面这句可能把map中的"group"，"release"对应的值改变，
            // 这里把map中"group"，"release"对应的原值再覆盖回去
            if (StringUtils.isNotEmpty(remoteGroup)) {
                map.put(Constants.GROUP_KEY, remoteGroup);
            }
            // we should always keep the Provider RELEASE_KEY not overrode by the the value on Consumer side.
            map.remove(Constants.RELEASE_KEY);
            if (StringUtils.isNotEmpty(remoteRelease)) {
                map.put(Constants.RELEASE_KEY, remoteRelease);
            }
        }
        // 若remoteMap不为空， 则从remoteMap中取值，添加到map中（这个感觉就是让map中属于remoteMap的那些元素不变）
        if (remoteMap != null && remoteMap.size() > 0) {
            // Use version passed from provider side
            // 从remoteMap中取"dubbo"对应的值
            String dubbo = remoteMap.get(Constants.DUBBO_VERSION_KEY);
            // 往map中添加（"dubbo"，"{dubbo}"）
            if (dubbo != null && dubbo.length() > 0) {
                map.put(Constants.DUBBO_VERSION_KEY, dubbo);
            }
            String version = remoteMap.get(Constants.VERSION_KEY);
            if (version != null && version.length() > 0) {
                // 往map中添加（"version"，"{version}"）
                map.put(Constants.VERSION_KEY, version);
            }
            String methods = remoteMap.get(Constants.METHODS_KEY);
            if (methods != null && methods.length() > 0) {
                // 往map中添加（"methods"，"{methods}"）
                map.put(Constants.METHODS_KEY, methods);
            }
            // Reserve timestamp of provider url.
            String remoteTimestamp = remoteMap.get(Constants.TIMESTAMP_KEY);
            if (remoteTimestamp != null && remoteTimestamp.length() > 0) {
                // 往map中添加（"remote.timestamp"，"{remoteTimestamp}"）
                map.put(Constants.REMOTE_TIMESTAMP_KEY, remoteMap.get(Constants.TIMESTAMP_KEY));
            }

            // TODO, for compatibility consideration, we cannot simply change the value behind APPLICATION_KEY from Consumer to Provider. So just add an extra key here.
            // Reserve application name from provider.
            // 往map中添加（"remote.application"，**）
            map.put(Constants.REMOTE_APPLICATION_KEY, remoteMap.get(Constants.APPLICATION_KEY));

            // Combine filters and listeners on Provider and Consumer
            // 取remoteMap和localMap的"reference.filter"属性
            String remoteFilter = remoteMap.get(Constants.REFERENCE_FILTER_KEY);
            String localFilter = localMap.get(Constants.REFERENCE_FILTER_KEY);
            if (remoteFilter != null && remoteFilter.length() > 0
                    && localFilter != null && localFilter.length() > 0) {
                // localMap中添加（"reference.filter"，"{remoteFilter},{localFilter}"）
                localMap.put(Constants.REFERENCE_FILTER_KEY, remoteFilter + "," + localFilter);
            }

            // 取remoteMap和localMap的"invoker.listener"属性
            String remoteListener = remoteMap.get(Constants.INVOKER_LISTENER_KEY);
            String localListener = localMap.get(Constants.INVOKER_LISTENER_KEY);
            if (remoteListener != null && remoteListener.length() > 0
                    && localListener != null && localListener.length() > 0) {
                // localMap中添加（"invoker.listener"，"{remoteListener},{localListener}"）
                localMap.put(Constants.INVOKER_LISTENER_KEY, remoteListener + "," + localListener);
            }
        }

        // 生成一个新的url对象， 并将map赋值给该url对象的parameters属性
        return remoteUrl.clearParameters().addParameters(map);
    }

}