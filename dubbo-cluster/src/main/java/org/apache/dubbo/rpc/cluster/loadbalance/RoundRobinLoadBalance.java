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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 *
 * Dubbo 中加权轮询负载均衡的实现类 RoundRobinLoadBalance。
 * 我们先来了解一下什么是加权轮询。
 * 这里从最简单的轮询开始讲起，所谓轮询是指将请求轮流分配给每台服务器。
 * 举个例子，我们有三台服务器 A、B、C。
 * 我们将第一个请求分配给服务器 A，第二个请求分配给服务器 B，第三个请求分配给服务器 C，第四个请求再次分配给服务器 A。
 * 这个过程就叫做轮询。轮询是一种无状态负载均衡算法，实现简单，适用于每台服务器性能相近的场景下。
 * 但现实情况下，我们并不能保证每台服务器性能均相近。如果我们将等量的请求分配给性能较差的服务器，这显然是不合理的。
 *
 * 因此，这个时候我们需要对轮询过程进行加权，以调控每台服务器的负载。
 * 经过加权后，每台服务器能够得到的请求数比例，接近或等于他们的权重比。
 * 比如服务器 A、B、C 权重比为 5:2:1。那么在8次请求中，服务器 A 将收到其中的5次请求，服务器 B 会收到其中的2次请求，服务器 C 则收到其中的1次请求。
 * 以上就是加权轮询的算法思想，本类在选取Invoker时，就使用这个思想，实现了加权轮询的效果。
 *
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";
    
    private static final int RECYCLE_PERIOD = 60000; // 60s

    // 一个invoker对应一个WeightedRoundRobin
    protected static class WeightedRoundRobin {
        // invoker的权重值，是个固定值（配置值）
        private int weight;

        // invoker的当前权重值（该值会动态调整，初始值为0）
        private AtomicLong current = new AtomicLong(0);

        // 最后一次更新当前对象的属性的时间
        private long lastUpdate;
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }
    // key是 全限定类名 + "." + 方法名，
    // value的 key是url串（就是服务提供者的url串），value是WeightedRoundRobin对象
    // value也可以理解为同一个服务的 多个服务提供者
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    // 原子更新锁， 用于更新上面这个methodWeightMap
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    @Override
    /**
     * 平滑的选出一个invoker对象。
     * 这里所谓的平滑就是让请求均匀的分布在所有的机器上，而不是集中打到某一台机器上。使用由这个函数选出invoker来承接请求，就实现了这个平滑的效果
     *
     *
     *  想了解详情的可以看下面这段话。
     * 对这个函数的举例说明：http://dubbo.apache.org/zh-cn/docs/source_code_guide/loadbalance.html  例子举的可以
     *  从“这次重构参考自 Nginx 的平滑加权轮询负载均衡。”开始
     *  例子中的，currentWeight 数组就是 成员变量methodWeightMap的value值的WeightedRoundRobin对象的current属性 集合
     *  表格每行之间是有联系的。第一行最后一列的值，加上自己的固定权重，就是第二行第一列的值。
     *  例子中是对同一个方法的7次请求
     *
     * @param invokers 同一个服务的所有服务提供者的集合
     * @param url 没用到
     * @param invocation 一个方法对应的invocation对象
     * @param <T>
     * @return
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // key = 全限定类名 + "." + 方法名，比如 com.xxx.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();

        // 从methodWeightMap中取该key对应的服务提供者集合
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }

        // 总权重
        int totalWeight = 0;
        // 当前最大权重
        long maxCurrent = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        // 最终被选中的Invoker对象
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;

        // 下面这个循环完成 更新每个invoker的当前权重值， 找到当前权重值最大的invoker ，计算总权重值
        for (Invoker<T> invoker : invokers) {
            // 服务提供者的url串
            String identifyString = invoker.getUrl().toIdentityString();
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            // 获得invocation所代表的方法的权重值。
            int weight = getWeight(invoker, invocation);

            if (weightedRoundRobin == null) {
                weightedRoundRobin = new WeightedRoundRobin();
                weightedRoundRobin.setWeight(weight);
                map.putIfAbsent(identifyString, weightedRoundRobin);
            }
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                // 感觉这里在服务还没有稳定运行时，会更新weight，也就是服务预热的时候会用到
                // 在服务稳定运行后，这里就用不到了。
                weightedRoundRobin.setWeight(weight);
            }
            // 更新invoker的当前权重值
            long cur = weightedRoundRobin.increaseCurrent();
            // 更新时间
            weightedRoundRobin.setLastUpdate(now);
            // 找到具有最大current值的Invoker
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            // 计算总权重值
            totalWeight += weight;
        }

        // 如果入参invokers发生改变（增加或者失去连接），更新map
        // 从下面代码可以看出，入参invokers是最新的服务提供者集合，而map中可能存在挂掉的服务提供者，所以要更新map，移除这些挂掉的服务提供者
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    newMap.putAll(map);
                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        // 对 <url, WeightedRoundRobin> 进行检查，过滤掉长时间未被更新的节点。
                        // 因为入参invokers 中不包含该节点，所以该节点的 lastUpdate 长时间无法被更新。表示该节点可能挂了。
                        // 若未更新时长超过阈值后，就会被移除掉，默认阈值为60秒。
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            it.remove();
                        }
                    }
                    // 更新map中key对应的服务提供者集合
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        if (selectedInvoker != null) {
            // WeightedRoundRobin对象中的 current属性值 减去总权重
            // 减去totalWeight的作用是，下次请求就不会打到这台机器上了，因为该机器权重小。所以有平滑的效果
            selectedWRR.sel(totalWeight);
            // 返回具有最大 current 的 Invoker
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
