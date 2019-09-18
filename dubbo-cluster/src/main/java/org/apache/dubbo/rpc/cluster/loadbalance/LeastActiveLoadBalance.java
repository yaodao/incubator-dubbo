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
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */

/**
 * LeastActiveLoadBalance 翻译过来是最小活跃数负载均衡。
 * 活跃调用数越小，表明该服务提供者效率越高，单位时间内可处理更多的请求。此时应优先将请求分配给该服务提供者。
 * 在具体实现中，每个服务提供者对应一个活跃数 active。
 * 初始情况下，所有服务提供者活跃数均为0。每收到一个请求，活跃数加1，完成请求后则将活跃数减1。
 * 在服务运行一段时间后，性能好的服务提供者处理请求的速度更快，因此活跃数下降的也越快，
 * 此时这样的服务提供者能够优先获取到新的服务请求、这就是最小活跃数负载均衡算法的基本思想。
 * 除了最小活跃数，LeastActiveLoadBalance 在实现上还引入了权重值。
 * 所以准确的来说，LeastActiveLoadBalance 是基于加权最小活跃数算法实现的。
 * 举个例子说明一下，在一个服务提供者集群中，有两个性能优异的服务提供者。
 * 某一时刻它们的活跃数相同，此时 Dubbo 会根据它们的权重去分配请求，权重越大，获取到新请求的概率就越大。
 * 如果两个服务提供者权重相同，此时随机选择一个即可。
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    @Override
    /**
     * 遍历 invokers 列表，寻找活跃数最小的 Invoker，返回该Invoker。
     *
     * 具体执行过程：
     * 如果有多个 Invoker 具有相同的最小活跃数，此时记录下这些 Invoker 在 invokers 集合中的下标，并累加它们的权重，比较它们的权重值是否相等
     * 如果只有一个 Invoker 具有最小的活跃数，此时直接返回该 Invoker 即可
     * 如果有多个 Invoker 具有最小活跃数，且它们的权重不相等，此时处理方式和 RandomLoadBalance 一致
     * 如果有多个 Invoker 具有最小活跃数，但它们的权重相等，此时随机返回一个即可
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size();
        // The least active value of all invokers
        // 最小活跃数
        int leastActive = -1;
        // 具有相同“最小活跃数”的服务者提供者（Invoker）的数量
        int leastCount = 0;
        // 记录具有相同“最小活跃数”的 Invoker 在 invokers 列表中的下标信息
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        int[] weights = new int[length];
        // 具有相同“最小活跃数”的Invoker 的权重 的和。（是最小活跃数的Invoker，不是所有Invoker）
        int totalWeight = 0;
        // 第一个最小活跃数的 Invoker 的权重值（用于与其他具有相同最小活跃数的 Invoker 的权重进行对比，以检测是否“所有具有相同最小活跃数的 Invoker 的权重”均相等)
        int firstWeight = 0;
        // 标识那些有最小活跃数的Invoker是否有相同的权重
        boolean sameWeight = true;


        // Filter out all the least active invokers
        // 遍历invokers集合，记录活跃数最小的Invoker的数量和下标，并标识这些活跃数最小的invoker的权重是否相同
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // 当前invoke的活跃数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoke configuration. The default value is 100.
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use
            weights[i] = afterWarmup;

            // 若当前invoker的活跃数 < 最小活跃数  或者  这是第一个invoker
            if (leastActive == -1 || active < leastActive) {
                // 因为找到了更小的活跃数，所以这里给leastActive赋值
                leastActive = active;
                // 因为找到了更小活跃数的invoker，所以这里给leastCount重新置为1
                leastCount = 1;
                // 因为找到了更小活跃数的invoker，所以这里重新记录invoker的下标
                leastIndexes[0] = i;
                // 因为找到了更小活跃数的invoker，所以这里给totalWeight重新设置为该invoker的权重值
                totalWeight = afterWarmup;
                // Record the weight the first least active invoker
                firstWeight = afterWarmup;
                // 因为找到了更小活跃数的invoker，所以将sameWeight重新置为true
                sameWeight = true;

            } // 当前 Invoker 的活跃数 active 与最小活跃数 leastActive 相同
            else if (active == leastActive) {
                // 在 leastIndexs 中记录下当前 Invoker 在 invokers 集合中的下标
                leastIndexes[leastCount++] = i;
                // 累加权重
                totalWeight += afterWarmup;
                // 检测当前 Invoker 的权重与 firstWeight 是否相等，
                // 不相等则将 sameWeight 置为 false
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }

        // 当只有一个 Invoker 具有最小活跃数，此时直接返回该 Invoker 即可
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }

        // 有多个 Invoker 具有相同的最小活跃数，但它们之间的权重不同
        if (!sameWeight && totalWeight > 0) {
            // 这块按totalWeight取一个invokers，计算方法和RandomLoadBalance中使用的方法一样。
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }

        // 如果所有invoker的权重相同或totalWeight为0时，则随机返回一个 Invoker
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}