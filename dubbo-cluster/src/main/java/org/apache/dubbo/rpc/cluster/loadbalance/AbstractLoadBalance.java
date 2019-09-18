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

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance
 */
public abstract class AbstractLoadBalance implements LoadBalance {
    /**
     * Calculate the weight according to the uptime proportion of warmup time
     * the new weight will be within 1(inclusive) to weight(inclusive)
     *
     * 使用(uptime / warmup) * weight计算新的权重值，新权重值控制在[1，weight]之间，返回整数新权重值
     *
     * @param uptime the uptime in milliseconds 服务已经运行的时间
     * @param warmup the warmup time in milliseconds 服务预热时间（就是服务在这个时间内，还没有完全起来，所以这个时间内请求的话可能会超时异常）
     * @param weight the weight of an invoker 权重
     * @return weight which takes warmup into account
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        // 计算权重，下面代码逻辑上形似于 (uptime / warmup) * weight
        // 随着服务运行时间 uptime 增大，权重计算值 ww 会慢慢接近配置值 weight
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }

    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        return doSelect(invokers, url, invocation);
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);


    /**
     * Get the weight of the invoker's invocation which takes warmup time into account
     * if the uptime is within the warmup time, the weight will be reduce proportionally
     *
     * 返回invocation所代表的方法的权重值。
     * 权重值考虑了uptime（服务已经启动的时间），warmup（服务需要预热的时间，从invoker对象中取）
     * 使用(uptime / warmup) * weight计算新的权重值。
     * 例如：
     * 假设 uptime=1分钟，warmup=10分钟，weight=100，那么新的权重值为10，表示该服务的这个方法现在只需要承担10%的流量。
     *
     * 权重的计算，主要用于保证当服务运行时长小于服务预热时间时，对服务进行降权，避免让服务在启动之初就处于高负载状态。
     * 服务预热是一个优化手段，与此类似的还有 JVM 预热。
     * 主要目的是让服务启动后“低功率”运行一段时间，使其效率慢慢提升至最佳状态。
     *
     *
     * @param invoker    the invoker 从invoker中取url对象，进而得到url对象中的属性值（取"remote.timestamp"和"warmup"属性值）
     * @param invocation the invocation of this invoker 从invocation中取方法名字
     * @return weight
     */

    // 当服务稳定运行后，返回的权重值是 已设置好的值 或者默认值100 （因为这时候服务已经预热完成了，可以火力全开）
    protected int getWeight(Invoker<?> invoker, Invocation invocation) {
        // 从url对象中取"{methodName}.weight" 或者 "weight" 属性对应的值， 默认值是100
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);
        if (weight > 0) {
            // 取url对象的"remote.timestamp"属性值，默认是0
            // （url的这个属性值=0，说明没有配置，那就不需要让权重值逐步增加到100，而是直接返回100）
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);
            if (timestamp > 0L) {
                // 服务已经运行的时间
                int uptime = (int) (System.currentTimeMillis() - timestamp);
                // 预热时间 = 取url对象的 "warmup"属性值，默认是10分钟
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP);
                // 若服务已经运行的时间 < 服务需要的预热时间，则计算新的权重
                if (uptime > 0 && uptime < warmup) {
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }
        // 返回[0， weight]
        return weight >= 0 ? weight : 0;
    }

}
