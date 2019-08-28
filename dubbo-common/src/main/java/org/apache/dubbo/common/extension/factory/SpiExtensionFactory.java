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

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

/**
 * SpiExtensionFactory
 */
public class SpiExtensionFactory implements ExtensionFactory {

    @Override
    // type是属性的类型，name是属性名
    // 返回type接口的一个实现类的对象 （该实现类必须带@Adaptive注解），这里没用到入参name，所以不能根据name值来取实例
    public <T> T getExtension(Class<T> type, String name) {
        // 只能处理带@SPI注解的接口， 不带直接返回null
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            // 得到ExtensionLoader对象，例如当type=Compile.class时，返回loader对象是 (type=Compile.class, objectFactory=AdaptiveExtensionFactory类的对象)
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            if (!loader.getSupportedExtensions().isEmpty()) {
                // 若入参type有实现类，则返回使用@Adaptive注解标注的那个实现类的对象
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}
