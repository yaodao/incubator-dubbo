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
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy.
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = (proxy, method, args) -> null;
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    // key是类加载器， value的（key是接口名，value是Reference<Proxy> 实例， 就是一个Proxy的子类对象用Reference包了一下）
    // value是由key加载的
    private static final Map<ClassLoader, Map<String, Object>> ProxyCacheMap = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PendingGenerationMarker = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    // 返回Proxy类的实现类的对象
    public static Proxy getProxy(Class<?>... ics) {
        return getProxy(ClassHelper.getClassLoader(Proxy.class), ics);
    }

    /**
     * Get proxy.
     * 返回Proxy类的实现类的对象， 并使用cl装载 动态生成的，实现了ics接口的代理类（下面代码中，代理类名字是proxy0）
     * @param cl  class loader. 类加载器
     * @param ics interface class array. 接口clazz的数组
     * @return Proxy instance.
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        // 入参ics的长度超过65535，抛异常
        if (ics.length > Constants.MAX_PROXY_COUNT) {
            throw new IllegalArgumentException("interface limit exceeded");
        }

        StringBuilder sb = new StringBuilder();
        // 遍历所有接口，将接口全名拼接起来，用分号分隔
        for (int i = 0; i < ics.length; i++) {
            String itf = ics[i].getName();
            if (!ics[i].isInterface()) {
                // 若入参数组中的元素不是接口，抛出异常
                throw new RuntimeException(itf + " is not a interface.");
            }

            Class<?> tmp = null;
            try {
                // 重新加载接口类
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }

            // 检测接口是否相同，这里 tmp 有可能为空
            if (tmp != ics[i]) {
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");
            }

            // 将clazz的全名连接起来，用分号分隔
            sb.append(itf).append(';');
        }

        // use interface class name list as key.
        // 使用拼接在一起的接口名作为 key
        String key = sb.toString();

        // get cache by class loader.
        // 从成员变量ProxyCacheMap中取类加载器cl对应的map，若没有则新建一个，这个map相当于缓存
        Map<String, Object> cache;
        synchronized (ProxyCacheMap) {
            // 将（cl，map）添加到成员变量ProxyCacheMap中，返回map
            cache = ProxyCacheMap.computeIfAbsent(cl, k -> new HashMap<>());
        }

        Proxy proxy = null;
        // 从缓存中取key对应的Reference<Proxy> 实例，若取到，则函数直接返回
        // 若没取到，则将(key, 标志位)放到缓存，跳出循环向下执行。
        synchronized (cache) {
            do {
                // 从缓存中获取key对应的 Reference<Proxy> 实例
                Object value = cache.get(key);
                if (value instanceof Reference<?>) {
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null) {
                        return proxy;
                    }
                }

                if (value == PendingGenerationMarker) {
                    try {
                        // 若value值是标志位，则等待（也就是value值是默认的标志位时，会等待，被唤醒后再循环取value值）
                        cache.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    // 放置标志位到缓存中，并跳出 while 循环进行后续操作
                    cache.put(key, PendingGenerationMarker);
                    break;
                }
            }
            while (true);
        }

        // 能到这说明缓存cache中该key对应的entry是 (key, 标志位)，也就是value值还没有造出来，下面就造这个value值

        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        ClassGenerator ccp = null, ccm = null;
        try {
            // 创建 ClassGenerator 对象
            ccp = ClassGenerator.newInstance(cl);

            // methods集合中每个方法的方法描述
            Set<String> worked = new HashSet<>();
            // 已经生成自身对应的字符串的 方法集合
            List<Method> methods = new ArrayList<>();

            // 遍历每个接口，并为每个接口中的方法生成对应的字符串
            for (int i = 0; i < ics.length; i++) {
                // 检测接口是否为 protected 或 privete（也就是只有非public的接口，才能进if）
                if (!Modifier.isPublic(ics[i].getModifiers())) {
                    // 获取接口包名
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {
                        pkg = npkg;
                    } else {
                        if (!pkg.equals(npkg)) {
                            // 非 public 级别的接口必须在同一个包下，否者抛出异常
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                        }
                    }
                }
                // 添加接口到 ClassGenerator对象的mInterfaces属性中， 这里就会生成 implements interA，interB
                ccp.addInterface(ics[i]);

                // 为接口中的每个方法生成一个完整的方法字符串，并将该字符串加入ccp对象的mMethods属性中
                for (Method method : ics[i].getMethods()) {
                    // 过滤掉已生成的方法
                    String desc = ReflectUtils.getDesc(method);
                    if (worked.contains(desc)) {
                        continue;
                    }
                    worked.add(desc);

                    // 当前生成的方法序号（生成一个方法的字符串后，methods长度+1）
                    int ix = methods.size();
                    Class<?> rt = method.getReturnType();
                    Class<?>[] pts = method.getParameterTypes();

                    /**
                     * 生成方法体字符串，举例如下：
                     *         Object[] args = new Object[1];
                     *         args[0] = ($w) $1;
                     *         Object ret = handler.invoke(this, methods[0], args);
                     *         return (java.lang.String) ret;
                     */
                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    for (int j = 0; j < pts.length; j++) {
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    }
                    code.append(" Object ret = handler.invoke(this, methods[").append(ix).append("], args);");
                    if (!Void.TYPE.equals(rt)) {
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");
                    }

                    methods.add(method);
                    // 添加方法名、访问控制符、参数列表、方法代码等信息到 ClassGenerator 中
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            // pkg为空，说明ics数组中的接口都由public修饰（不存在非public接口，因为L162）
            if (pkg == null) {
                // pkg="org.apache.dubbo.common.bytecode"
                pkg = PACKAGE_NAME;
            }

            /**
             * 这段代码生成字节码，举例：（就是创建一个接口的实现类）
             * public class proxy0 implements org.apache.dubbo.demo.DemoService {
             *
             *     public static java.lang.reflect.Method[] methods;
             *
             *     private java.lang.reflect.InvocationHandler handler;
             *
             *     public proxy0() {
             *     }
             *
             *     public proxy0(java.lang.reflect.InvocationHandler arg0) {
             *         handler = $1;
             *     }
             *
             *     public java.lang.String sayHello(java.lang.String arg0) {
             *         Object[] args = new Object[1];
             *         args[0] = ($w) $1;
             *         Object ret = handler.invoke(this, methods[0], args);
             *         return (java.lang.String) ret;
             *     }
             * }
             */
            // create ProxyInstance class.
            // 构建接口代理类名称：pkg + ".proxy" + id，比如 org.apache.dubbo.proxy0
            // pcn="org.apache.dubbo.common.bytecode.proxy0"
            String pcn = pkg + ".proxy" + id;
            ccp.setClassName(pcn);
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            ccp.addDefaultConstructor();
            Class<?> clazz = ccp.toClass();
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            /**
             * 这段代码生成的字节码，举例：（就是创建一个Proxy的子类）
             * public class Proxy0 extends org.apache.dubbo.common.bytecode.Proxy {
             *     public Object newInstance(java.lang.reflect.InvocationHandler h) {
             *         return new proxy0($1);
             *     }
             * }
             */
            // create Proxy class.
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");
            // 生成 Proxy 的实现类
            Class<?> pc = ccm.toClass();
            // 通过反射创建 Proxy的实现类的对象
            proxy = (Proxy) pc.newInstance();

            /**
             * 由上面可以看出
             * ccp 用于为服务接口生成代理类，比如我们有一个 DemoService 接口，这个接口代理类就是由 ccp 生成的。
             * ccm 则是用于为 org.apache.dubbo.common.bytecode.Proxy 抽象类生成子类对象，主要是实现 Proxy 类的抽象方法。
             */
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // release ClassGenerator
            if (ccp != null) {
                ccp.release();
            }
            if (ccm != null) {
                ccm.release();
            }
            synchronized (cache) {
                if (proxy == null) {
                    cache.remove(key);
                } else {
                    // 将key和 Reference<Proxy>实例放到缓存中
                    cache.put(key, new WeakReference<Proxy>(proxy));
                }
                // 唤醒其他等待线程
                cache.notifyAll();
            }
        }
        return proxy;
    }

    /**
     * 根据cl的具体类型，返回一个字符串
     * @param cl 变量的类型
     * @param name 变量的名字，与cl代表的是同一个变量 （这里假设name="ret"）
     * @return
     */
    private static String asArgument(Class<?> cl, String name) {
        // cl是基本类型
        if (cl.isPrimitive()) {
            // 若cl是布尔类型
            if (Boolean.TYPE == cl) {
                // 返回字符串 "ret==null?false:((Boolean)ret).booleanValue()"
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            }
            if (Byte.TYPE == cl) {
                // 返回字符串 "ret==null?(byte)0:((Byte)ret).byteValue()"
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            }
            if (Character.TYPE == cl) {
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            }
            if (Double.TYPE == cl) {
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            }
            if (Float.TYPE == cl) {
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            }
            if (Integer.TYPE == cl) {
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            }
            if (Long.TYPE == cl) {
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            }
            if (Short.TYPE == cl) {
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            }
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        // cl不是基本类型
        // 返回 "(java.lang.String)ret"
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}
