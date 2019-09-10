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
package org.apache.dubbo.rpc;

import java.lang.reflect.Field;

/**
 * RPC Result.
 *
 * @serial Don't change the class name and properties.
 */
// RPC调用结果
public class RpcResult extends AbstractResult {

    private static final long serialVersionUID = -6925924956850004727L;

    public RpcResult() {
    }

    public RpcResult(Object result) {
        this.result = result;
    }

    public RpcResult(Throwable exception) {
        this.exception = handleStackTraceNull(exception);
    }

    @Override
    public Object recreate() throws Throwable {
        if (exception != null) {
            throw exception;
        }
        return result;
    }

    /**
     * @see org.apache.dubbo.rpc.RpcResult#getValue()
     * @deprecated Replace to getValue()
     */
    @Override
    @Deprecated
    public Object getResult() {
        return getValue();
    }

    /**
     * @see org.apache.dubbo.rpc.RpcResult#setValue(Object)
     * @deprecated Replace to setValue()
     */
    @Deprecated
    public void setResult(Object result) {
        setValue(result);
    }

    @Override
    public Object getValue() {
        return result;
    }

    public void setValue(Object value) {
        this.result = value;
    }

    @Override
    public Throwable getException() {
        return exception;
    }

    public void setException(Throwable e) {
        this.exception = handleStackTraceNull(e);
    }

    @Override
    public boolean hasException() {
        return exception != null;
    }

    @Override
    public String toString() {
        return "RpcResult [result=" + result + ", exception=" + exception + "]";
    }

    /**
     * we need to deal the exception whose stack trace is null.
     * <p>
     * see https://github.com/apache/incubator-dubbo/pull/2956
     * and https://github.com/apache/incubator-dubbo/pull/3634
     * and https://github.com/apache/incubator-dubbo/issues/619
     *
     * @param e exception
     * @return exception after deal with stack trace
     */
    // 若对象e的stackTrace属性为null，则给该属性设置为空数组，并返回e
    // 即 e.setStackTrace(new StackTraceElement[0]);
    private Throwable handleStackTraceNull(Throwable e) {
        if (e != null) {
            try {
                // get Throwable class
                Class clazz = e.getClass();
                while (!clazz.getName().equals(Throwable.class.getName())) {
                    clazz = clazz.getSuperclass();
                }

                // 能到这，说明clazz已经等于Throwable.class

                // get stackTrace value
                // 取Throwable.class的成员变量stackTrace
                Field stackTraceField = clazz.getDeclaredField("stackTrace");
                stackTraceField.setAccessible(true);
                // 取对象e的成员变量stackTrace的值
                Object stackTrace = stackTraceField.get(e);
                if (stackTrace == null) {
                    // 若对象e的stackTrace值是null，则置为空数组
                    e.setStackTrace(new StackTraceElement[0]);
                }
            } catch (Throwable t) {
                // ignore
            }
        }

        return e;
    }
}
