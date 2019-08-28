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

    // 工厂集合，所有实现了ExtensionFactory接口的类的对象集合
    private final List<ExtensionFactory> factories;

    // 给成员变量factories赋值
    public AdaptiveExtensionFactory() {
        // 返回ExtensionLoader对象(type=ExtensionFactory.class, objectFactory=null)， loader的成员变量type=ExtensionFactory.class
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();


        // loader.getSupportedExtensions() 会将所有ExtensionFactory接口的实现类的名字取到
        // 在根据名字，取到该名字对应的对象
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        // factories中存放所有ExtensionFactory接口的实现类的对象
        factories = Collections.unmodifiableList(list);
    }

    @Override
    // type是 method中第一个参数的类型的clazz对象, name是 method对应的属性名
    // 简单点， type是属性的类型， name是属性名
    public <T> T getExtension(Class<T> type, String name) {
        for (ExtensionFactory factory : factories) {
            // 其实就是查看各个工厂对象，看看里面是否有该type类型的实例对象，有就返回。没有返回null
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
