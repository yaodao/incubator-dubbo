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
package org.apache.dubbo.rpc.model;

import java.lang.reflect.Method;

// ProviderMethodModel对象是 包含 单个的方法、方法名、方法参数列表、方法路径 的对象
// （一个ProviderMethodModel对应一个method对象）
public class ProviderMethodModel {
    // 方法
    private transient final Method method;
    // 方法名字
    private final String methodName;
    // 方法的参数列表中，每个参数的类型的全名的集合
    private final String[] methodArgTypes;
    // "{group}/{interfaceName}:{version}"
    private final String serviceName;

    // ProviderMethodModel对象是 包含 单个的方法、方法名、方法参数列表、方法路径 的对象
    // （一个ProviderMethodModel对应一个method对象）
    public ProviderMethodModel(Method method, String serviceName) {
        this.method = method;
        this.serviceName = serviceName;
        this.methodName = method.getName();
        this.methodArgTypes = getArgTypes(method);
    }

    public Method getMethod() {
        return method;
    }

    public String getMethodName() {
        return methodName;
    }

    public String[] getMethodArgTypes() {
        return methodArgTypes;
    }

    public String getServiceName() {
        return serviceName;
    }

    // 得到入参method的参数列表中，每个参数的类型的全名，返回全名的集合
    private static String[] getArgTypes(Method method) {
        String[] methodArgTypes = new String[0];
        // 得到方法的参数列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length > 0) {
            methodArgTypes = new String[parameterTypes.length];
            int index = 0;
            // 将每个参数的类型的全名，放到数组中
            for (Class<?> paramType : parameterTypes) {
                methodArgTypes[index++] = paramType.getName();
            }
        }
        return methodArgTypes;
    }
}
