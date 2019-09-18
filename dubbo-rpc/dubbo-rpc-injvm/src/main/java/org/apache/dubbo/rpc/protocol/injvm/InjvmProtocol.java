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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

/**
 * InjvmProtocol
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    public static final String NAME = Constants.LOCAL_PROTOCOL;

    public static final int DEFAULT_PORT = 0;
    private static InjvmProtocol INSTANCE;

    // 反射newInstance()时，会调用这个函数，这时赋值给INSTANCE
    public InjvmProtocol() {
        INSTANCE = this;
    }

    // 返回InjvmProtocol对象
    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            // 反射生成InjvmProtocol.class的实例对象（这里应该是重试）
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    // 返回入参key对应的Exporter对象，若没有则返回null
    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        // 若由key的属性拼接成的串 "{group}/{interfaceName}:{version}" 中不包含"*"
        if (!key.getServiceKey().contains("*")) {
            // 从map中取该串对应的Exporter对象
            result = map.get(key.getServiceKey());
        } else {
            // 串中包含"*"
            if (CollectionUtils.isNotEmptyMap(map)) {
                for (Exporter<?> exporter : map.values()) {
                    // 比较key和exporter中 "interface", "group", "version" 这三项的值是否相同
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        result = exporter;
                        break;
                    }
                }
            }
        }

        if (result == null) {
            return null;
        } // 若"generic"是"true" 或者是 "nativejava" 或者是 "bean" 则进if
        else if (ProtocolUtils.isGeneric(
                result.getInvoker().getUrl().getParameter(Constants.GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    // 仅仅创建了一个 InjvmExporter，无其他逻辑
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 创建InjvmExporter对象，并将key="{group}/{interfaceName}:{version}", value=InjvmExporter对象添加到入参exporterMap
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        // 生成一个InjvmInvoker对象，并给它的成员变量赋值
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    /**
     * 根据url的parameters集合判断是否为本地引用。
     *      取入参url的parameters集合中的"scope"属性值，
     *          若scope="local" 或者 injvm=true，则返回true；
     *          若scope="remote"，则返回false；
     *      若url的parameters集合中的"generic"属性值为true，则返回false
     *      若当前对象的成员变量exporterMap中，有url对应的Exporter对象，则返回true
     * @param url
     * @return
     */
    public boolean isInjvmRefer(URL url) {
        // 取url的"scope"值
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        // scope="local" 或者 injvm=true
        if (Constants.SCOPE_LOCAL.equals(scope) || (url.getParameter(Constants.LOCAL_PROTOCOL, false))) {
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            return true;
        } else if (Constants.SCOPE_REMOTE.equals(scope)) {
            // it's declared as remote reference
            return false;
        } else if (url.getParameter(Constants.GENERIC_KEY, false)) {
            // generic invocation is not local reference
            return false;
        } else if (getExporter(exporterMap, url) != null) {
            // by default, go through local reference if there's the service exposed locally
            return true;
        } else {
            return false;
        }
    }
}
