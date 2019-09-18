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
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.Constants.HASH_ARGUMENTS;
import static org.apache.dubbo.common.Constants.HASH_NODES;

/**
 * ConsistentHashLoadBalance
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    // key = 全限定类名 + "." + 方法名，比如 com.xxx.DemoService.sayHello
    // value是ConsistentHashSelector对象，该Selector对象中存放着，一个方法所有invoker对象的所有虚拟节点
    // （一个方法对应一个ConsistentHashSelector对象，一个方法对应多个invoker对象）
    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    /**
     * 使用一致性hash的思想，选出一个invoker，并返回
     *
     * @param invokers 服务提供者集合
     * @param url 没用上
     * @param invocation 一个方法对应一个invocation对象，这个invocation对象记录该方法的相关信息
     * @param <T>
     * @return
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String methodName = RpcUtils.getMethodName(invocation);
        // key = 全限定类名 + "." + 方法名，比如 com.xxx.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        // 获取 invokers 原始的 hashcode （这个值用于检测invokers是否变化过）
        int identityHashCode = System.identityHashCode(invokers);
        // 从缓存中取该key对应的selector对象
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        // 没有 或者 invokers集合有变化， 都会new一个ConsistentHashSelector对象， 添加到缓存中。
        if (selector == null || selector.identityHashCode != identityHashCode) {
            // 创建新的 ConsistentHashSelector添加到selectors中
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        // 从selector对象中取invoker对象，并返回
        return selector.select(invocation);
    }

    // 一个方法对应一个ConsistentHashSelector对象
    private static final class ConsistentHashSelector<T> {

        // 使用TreeMap存储Invoker的虚拟节点
        // key是该虚拟节点的位置（其实就是在圆盘上的位置），value是该虚拟节点对应的实际节点
        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        // 虚拟节点的数量，默认为160
        private final int replicaNumber;

        // 这个值用于检测invokers集合是否变化过
        private final int identityHashCode;

        // 参加hash运算的那些参数的下标 （参数是指 Invocation所表示的方法的参数）
        private final int[] argumentIndex;

        // 对每个invoker生成虚拟节点，并将这些虚拟节点存放到成员变量virtualInvokers中,
        // 每个invoker生成replicaNumber个虚拟节点
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            // 虚拟节点的数量，默认为160
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);
            // 获取url的parameters中key="hash.arguments"对应的值，并将值以逗号分隔成数组。（也就是这些下标对应的值才参加hash运算）
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }

            // 对每个invoker生成replicaNumber个虚拟节点，存放到成员变量virtualInvokers中
            for (Invoker<T> invoker : invokers) {
                // 获取该invoker对应的地址， 每个虚拟节点的位置由该地址的hash值得到
                String address = invoker.getUrl().getAddress();

                // 每轮循环生成4个虚拟节点， 一共进行replicaNumber/4轮循环，所以最终能生成replicaNumber个虚拟节点，这些虚节点对应同一个invoker
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 对address+i进行 md5 运算，得到一个长度为16的字节数组
                    byte[] digest = md5(address + i);

                    // 生成4个虚拟节点，并添加到virtualInvokers
                    // 将digest数组分成4份，下标范围分别是（0 - 3）（4 - 7）（8 - 11）（12 - 15）
                    // 每份都进行一次hash运算，得到四个不同的 long 型正整数
                    for (int h = 0; h < 4; h++) {
                        // digest中每4个字节，组成一个long值返回。
                        long m = hash(digest, h);
                        // key是该虚拟节点的位置，value是该虚拟节点对应的实际节点
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        // 从当前对象的成员变量virtualInvokers中取invoker对象
        // 入参invocation提供计算hash值的参数
        public Invoker<T> select(Invocation invocation) {
            // 将参数的值转为key（invocation对象中有方法的具体参数值）
            String key = toKey(invocation.getArguments());
            // md5得到digest数组，长度为16
            byte[] digest = md5(key);
            // 先将digest数组中下标为 0 - 3 的4个字节，连接起来组成一个long
            // 再从成员变量virtualInvokers中取该long对应的invoker对象
            return selectForKey(hash(digest, 0));
        }

        // 将入参args数组中，参加hash值计算的元素连接起来，返回连接串
        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            // 将参加hash值计算的那些参数，追加到buf后面
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        // 取hash对应的invoker对象，并返回该invoker对象（从成员变量virtualInvokers中取）
        private Invoker<T> selectForKey(long hash) {
            // 返回map中 key>=hash的entry
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
            if (entry == null) {
                // 返回map中的第一个entry
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();
        }

        /**
         * 将入参digest数组中的4个字节连接在一起，以long值返回。
         *
         * @param digest byte数组，长度为16
         * @param number 取值[0,3]
         * @return
         */
        // number = 0 时，取digest数组中下标为 0 - 3 的4个字节进行位运算
        // number = 1 时，取digest数组中下标为 4 - 7 的4个字节进行位运算
        // number = 2 时，取digest数组中下标为 8 - 11 的4个字节进行位运算
        // number = 3 时，取digest数组中下标为 12 - 15 的4个字节进行位运算

        // 以number = 0举例， 将digest[3] 左移24位，digest[2] 左移16位，digest[1] 左移8位，digest[0]不动， 将这四个字节放到一个long里，返回long
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        // 对value进行md5，返回一个16字节的数组。
        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}
