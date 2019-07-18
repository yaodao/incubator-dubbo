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
package org.apache.dubbo.common.extension.factory;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        // 返回ExtensionLoader对象(type=ExtensionFactory.class, objectFactory=null)
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();

        // 在这里 loader的成员变量type=ExtensionFactory.class
        for (String name : loader.getSupportedExtensions()) {// loader.getSupportedExtensions() 会将所有ExtensionFactory接口的实现类的名字取到
            list.add(loader.getExtension(name));// loader的成员变量type=ExtensionFactory.class
        }
        // factories中存放所有ExtensionFactory接口的实现类的对象
        factories = Collections.unmodifiableList(list);
    }

    @Override
    // type是 method中第一个参数的clazz对象, name是 method对应的属性名
    public <T> T getExtension(Class<T> type, String name) {
        for (ExtensionFactory factory : factories) {
            // 依次遍历各个ExtensionFactory实现的getExtension方法，一旦获取到Extension即返回
            // 如果遍历完所有的ExtensionFactory实现均无法找到Extension,则返回null
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
