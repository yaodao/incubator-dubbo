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
package org.apache.dubbo.rpc.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcInvocation;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RpcUtils
 */
public class RpcUtils {

    private static final Logger logger = LoggerFactory.getLogger(RpcUtils.class);
    private static final AtomicLong INVOKE_ID = new AtomicLong(0);

    public static Class<?> getReturnType(Invocation invocation) {
        try {
            if (invocation != null && invocation.getInvoker() != null
                    && invocation.getInvoker().getUrl() != null
                    && !invocation.getMethodName().startsWith("$")) {
                String service = invocation.getInvoker().getUrl().getServiceInterface();
                if (StringUtils.isNotEmpty(service)) {
                    Class<?> invokerInterface = invocation.getInvoker().getInterface();
                    Class<?> cls = invokerInterface != null ? ReflectUtils.forName(invokerInterface.getClassLoader(), service)
                            : ReflectUtils.forName(service);
                    Method method = cls.getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    return method.getReturnType();
                }
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        return null;
    }

    // TODO why not get return type when initialize Invocation?
    public static Type[] getReturnTypes(Invocation invocation) {
        try {
            if (invocation != null && invocation.getInvoker() != null
                    && invocation.getInvoker().getUrl() != null
                    && !invocation.getMethodName().startsWith("$")) {
                String service = invocation.getInvoker().getUrl().getServiceInterface();
                if (StringUtils.isNotEmpty(service)) {
                    Class<?> invokerInterface = invocation.getInvoker().getInterface();
                    Class<?> cls = invokerInterface != null ? ReflectUtils.forName(invokerInterface.getClassLoader(), service)
                            : ReflectUtils.forName(service);
                    Method method = cls.getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    Class<?> returnType = method.getReturnType();
                    Type genericReturnType = method.getGenericReturnType();
                    if (Future.class.isAssignableFrom(returnType)) {
                        if (genericReturnType instanceof ParameterizedType) {
                            Type actualArgType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
                            if (actualArgType instanceof ParameterizedType) {
                                returnType = (Class<?>) ((ParameterizedType) actualArgType).getRawType();
                                genericReturnType = actualArgType;
                            } else {
                                returnType = (Class<?>) actualArgType;
                                genericReturnType = returnType;
                            }
                        } else {
                            returnType = null;
                            genericReturnType = null;
                        }
                    }
                    return new Type[]{returnType, genericReturnType};
                }
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        return null;
    }

    // 从Invocation对象的attachments中获取"id"属性值，有则返回值，没有返回null
    public static Long getInvocationId(Invocation inv) {
        String id = inv.getAttachment(Constants.ID_KEY);
        return id == null ? null : new Long(id);
    }

    /**
     * Idempotent operation: invocation id will be added in async operation by default
     *
     * @param url
     * @param inv
     */
    // 添加（"id"，INVOKE_ID）到Invocation对象的attachments属性中
    public static void attachInvocationIdIfAsync(URL url, Invocation inv) {
        if (isAttachInvocationId(url, inv) && getInvocationId(inv) == null && inv instanceof RpcInvocation) {
            ((RpcInvocation) inv).setAttachment(Constants.ID_KEY, String.valueOf(INVOKE_ID.getAndIncrement()));
        }
    }

    // 根据入参url和invocation的属性， 判断是否需要添加invocationid。
    // 返回true 表示需要
    private static boolean isAttachInvocationId(URL url, Invocation invocation) {
        // 从url的parameters中取"{method}.invocationid.autoattach" 或者 "invocationid.autoattach" 对应的value值
        String value = url.getMethodParameter(invocation.getMethodName(), Constants.AUTO_ATTACH_INVOCATIONID_KEY);
        if (value == null) {
            // add invocationid in async operation by default
            // 异步操作，默认需要添加invocationid
            return isAsync(url, invocation);
        } else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
            return true;
        } else {
            return false;
        }
    }


    /**
     * 返回invocation对象的methodName属性值 或者 返回方法的第一个参数的值
     * （这里的方法是指入参invocation对象所代表的那个方法。）
     *
     * @param invocation 含有单个方法的信息的对象
     * @return
     */
    public static String getMethodName(Invocation invocation) {
        // 若入参invocation的methodName属性值是"$invoke"
        // 且 方法有参数 且 方法的第一个参数的值是String类型， 则进if
        if (Constants.$INVOKE.equals(invocation.getMethodName())
                && invocation.getArguments() != null
                && invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof String) {
            // 返回方法第一个参数的值
            return (String) invocation.getArguments()[0];
        }
        // 返回入参invocation的methodName属性值
        return invocation.getMethodName();
    }

    public static Object[] getArguments(Invocation invocation) {
        if (Constants.$INVOKE.equals(invocation.getMethodName())
                && invocation.getArguments() != null
                && invocation.getArguments().length > 2
                && invocation.getArguments()[2] instanceof Object[]) {
            return (Object[]) invocation.getArguments()[2];
        }
        return invocation.getArguments();
    }

    public static Class<?>[] getParameterTypes(Invocation invocation) {
        if (Constants.$INVOKE.equals(invocation.getMethodName())
                && invocation.getArguments() != null
                && invocation.getArguments().length > 1
                && invocation.getArguments()[1] instanceof String[]) {
            String[] types = (String[]) invocation.getArguments()[1];
            if (types == null) {
                return new Class<?>[0];
            }
            Class<?>[] parameterTypes = new Class<?>[types.length];
            for (int i = 0; i < types.length; i++) {
                parameterTypes[i] = ReflectUtils.forName(types[0]);
            }
            return parameterTypes;
        }
        return invocation.getParameterTypes();
    }

    // 根据入参url和inv的参数 来返回isAsync的值
    // 判断是否异步，true 异步
    public static boolean isAsync(URL url, Invocation inv) {
        boolean isAsync;
        // 若Invocation对象的attachments中，key="async"对应的值为true, 表示异步带回调
        if (Boolean.TRUE.toString().equals(inv.getAttachment(Constants.ASYNC_KEY))) {
            isAsync = true;
        } else {
            // 从url的parameters中取key="async"对应的值，默认值为false
            isAsync = url.getMethodParameter(getMethodName(inv), Constants.ASYNC_KEY, false);
        }
        return isAsync;
    }

    // 判断入参Invocation对象的"future_returntype"属性是否为true
    public static boolean isReturnTypeFuture(Invocation inv) {
        return Boolean.TRUE.toString().equals(inv.getAttachment(Constants.FUTURE_RETURNTYPE_KEY));
    }

    // CompletableFuture是否为method的返回值的父类/接口或者同类
    public static boolean hasFutureReturnType(Method method) {
        return CompletableFuture.class.isAssignableFrom(method.getReturnType());
    }

    // 根据入参url和inv的参数 来返回isOneway的值
    public static boolean isOneway(URL url, Invocation inv) {
        boolean isOneway;
        if (Boolean.FALSE.toString().equals(inv.getAttachment(Constants.RETURN_KEY))) {
            // 若Invocation对象的attachments中，key="return"对应的值为false，则表示不需要返回值
            isOneway = true;
        } else {
            // 从url的parameters中取key="return"对应的值，默认值为true
            isOneway = !url.getMethodParameter(getMethodName(inv), Constants.RETURN_KEY, true);
        }
        return isOneway;
    }

    public static Map<String, String> getNecessaryAttachments(Invocation inv) {
        Map<String, String> attachments = new HashMap<>(inv.getAttachments());
        attachments.remove(Constants.ASYNC_KEY);
        attachments.remove(Constants.FUTURE_GENERATED_KEY);
        return attachments;
    }

}
