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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ProviderModel which is about published services
 */
// ProviderModel 表示服务提供者模型，此对象中存储了与服务提供者相关的信息。
// 比如服务的配置信息，服务实例等。每个被导出的服务对应一个 ProviderModel。
// ApplicationModel 持有所有的 ProviderModel。
public class ProviderModel {
    // "{group}/{interfaceName}:{version}"
    private final String serviceName;
    // 接口的实例对象
    private final Object serviceInstance;
    // 对外导出的服务接口类型
    private final Class<?> serviceInterfaceClass;
    // key是导出接口中的方法名，value是ProviderMethodModel对象的集合（该接口中的每个方法对应一个ProviderMethodModel对象，这里用集合做value，可能是用于后期扩展）
    private final Map<String, List<ProviderMethodModel>> methods = new HashMap<String, List<ProviderMethodModel>>();

    public ProviderModel(String serviceName, Object serviceInstance, Class<?> serviceInterfaceClass) {
        if (null == serviceInstance) {
            throw new IllegalArgumentException("Service[" + serviceName + "]Target is NULL.");
        }

        this.serviceName = serviceName;
        this.serviceInstance = serviceInstance;
        this.serviceInterfaceClass = serviceInterfaceClass;
        // 使用接口serviceInterfaceClass中的方法 填充成员变量methods
        initMethod();
    }


    public String getServiceName() {
        return serviceName;
    }

    public Class<?> getServiceInterfaceClass() {
        return serviceInterfaceClass;
    }

    public Object getServiceInstance() {
        return serviceInstance;
    }

    public List<ProviderMethodModel> getAllMethods() {
        List<ProviderMethodModel> result = new ArrayList<ProviderMethodModel>();
        for (List<ProviderMethodModel> models : methods.values()) {
            result.addAll(models);
        }
        return result;
    }

    public ProviderMethodModel getMethodModel(String methodName, String[] argTypes) {
        List<ProviderMethodModel> methodModels = methods.get(methodName);
        if (methodModels != null) {
            for (ProviderMethodModel methodModel : methodModels) {
                if (Arrays.equals(argTypes, methodModel.getMethodArgTypes())) {
                    return methodModel;
                }
            }
        }
        return null;
    }

    // 该方法就是使用接口serviceInterfaceClass中的方法 填充成员变量methods（key是方法名，value是ProviderMethodModel对象的集合）
    private void initMethod() {
        Method[] methodsToExport = null;
        // 接口中的所有方法
        methodsToExport = this.serviceInterfaceClass.getMethods();

        for (Method method : methodsToExport) {
            method.setAccessible(true);

            // 为单个方法生成对应的ProviderMethodModel对象
            List<ProviderMethodModel> methodModels = methods.get(method.getName());
            if (methodModels == null) {
                methodModels = new ArrayList<ProviderMethodModel>(1);
                methods.put(method.getName(), methodModels);
            }
            methodModels.add(new ProviderMethodModel(method, serviceName));
        }
    }

}
