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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * random load balance.
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    @Override
    /**
     * 从入参invokers中，选一个返回 (这里考虑了每台机器的权重，再随机返回，权重大的就大概率返回)
     * （入参invokers集合对应同一个方法，也就是多台机器提供相同的服务）
     *
     * 代码还是比较清晰，总体分为一下几步吧：
     * 1.统计目前有几个服务提供者，然后计算总的权重值
     * 2.如果每一个服务提供者的权重都一样，那就随机选择一个
     * 3.如果权重不同，则从总权重中取一个随机值，取随机值和权重值的差值，如果小于0则返回。
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        int length = invokers.size();
        // Every invoker has the same weight?
        boolean sameWeight = true;
        // the weight of every invokers
        int[] weights = new int[length];
        // the first invoker's weight
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // The sum of weights
        int totalWeight = firstWeight;
        // 下面这个循环有两个作用，第一是计算总权重 totalWeight，
        // 第二是检测每个服务提供者（invoker）的权重是否相同，相同sameWeight=true，不同sameWeight=false
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            weights[i] = weight;
            // Sum
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }


        // 获取随机数，并计算随机数落在哪个服务上
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            // 随机获取一个 [0, totalWeight) 区间内的数字
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // 循环让 offset 数减去服务提供者的权重值，当 offset 小于0时，返回相应的 Invoker。
            // 举例说明一下，假设我们有 servers = [A, B, C]，weights = [5, 3, 2]，offset = 7。
            // 第一次循环，offset - 5 = 2 > 0，即 offset > 5，
            // 表明其不会落在服务器 A 上。
            // 第二次循环，offset - 3 = -1 < 0，即 5 < offset < 8，
            // 表明其会落在服务器 B 上

            // 这个原理很简单，因为weights = [5, 3, 2]，所以totalWeight=10
            // 那么A，B，C占用的权重分别是 50%，30%，20%，也就是机器A应该接受50%的请求，机器B应该接受30%的请求
            // 那怎样保证这个比率呢，因为随机数[0，totalWeight)之间取，占用区间越大，随机数落在该区间的次数越多，所以就保证这个比率。
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有服务提供者权重值相同，此时直接随机返回一个即可
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
