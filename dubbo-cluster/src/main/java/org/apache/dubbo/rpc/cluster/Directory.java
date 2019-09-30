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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.Node;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

/**
 * Directory. (SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Directory_service">Directory Service</a>
 *
 *
 * 服务目录在获取注册中心的服务配置信息后，会为每条配置信息生成一个 Invoker 对象，
 * 并把这个 Invoker 对象存储起来，这个 Invoker 才是服务目录最终持有的对象。
 *
 * 也就是说Directory代表多个Invoker，可以把它看成List<Invoker>，但与List不同的是，这个集合中的元素会随注册中心的变化而进行动态调整。
 * Cluster将Directory中的多个Invoker伪装成一个Invoker，对上层透明，伪装过程包含了容错逻辑，调用失败后，重试另一个。
 * @see org.apache.dubbo.rpc.cluster.Cluster#join(Directory)
 */
public interface Directory<T> extends Node {

    /**
     * get service type.
     *
     * @return service type.
     */
    // 获取服务接口
    Class<T> getInterface();

    /**
     * list invokers.
     *
     * @return invokers
     */
    // invoker列表，服务的列表
    List<Invoker<T>> list(Invocation invocation) throws RpcException;

}