/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.threadlocal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the threadLocal variables for Netty and all {@link InternalThread}s.
 * Note that this class is for internal use only. Use {@link InternalThread}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap {

    // 存储当前类的数据的数组，当前这个map类的底层实现就是这个数组，一开始元素都是默认值UNSET
    // 0位置 是个set集合，set中存放单个线程使用到的所有InternalThreadLocal对象
    // 其他位置，是具体的变量值，即 调用InternalThreadLocal.get()时 取到的那个值
    private Object[] indexedVariables;

    // 为每个线程 对应 一个InternalThreadLocalMap对象， 就是普通的ThreadLocal。
    private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();

    // 可以理解为一个针对InternalThreadLocal对象数量的计数值。
    private static final AtomicInteger nextIndex = new AtomicInteger();

    // 表示默认值
    public static final Object UNSET = new Object();

    // 获取当前线程对应的map，并返回该map，返回的map有可能为null
    // 若当前线程是InternalThread类型的，则将线程内部的threadLocalMap属性返回
    // 若当前线程是Thread类型的，则从当前类的ThreadLocal变量取该线程对应的map， 并返回
    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            // 若当前线程是InternalThread类型的，则返回线程内的map变量
            return ((InternalThread) thread).threadLocalMap();
        }
        // 若当前线程是普通Thread类型的对象，则返回当前类的成员变量ThreadLocal中的map变量
        return slowThreadLocalMap.get();
    }

    // 取一个和当前线程相关的map， （若在ThreadLocal对象中，当前线程对应的这个map为null，则会set一个map到ThreadLocal中，再返回该map）
    // 反正是无论怎样，这个函数最后都会返回一个和当前线程对应的InternalThreadLocalMap类型的对象，而且map不为null。
    // 注意： 每个线程取到的map只对应该线程，可以理解为该线程自己的map，不存在多个线程对应同一个map的情况
    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            // 若thread是InternalThread类型的，则返回thread对象内的map属性
            return fastGet((InternalThread) thread);
        }
        // 若thread是Thread类型的，则返回当前对象的ThreadLocal变量中的map变量
        return slowGet();
    }

    // 若当前线程是InternalThread类型的，则将线程内部的threadLocalMap属性设置为null
    // 若当前线程是Thread类型的，则从ThreadLocal类型的成员变量中移除该线程及其对应的值
    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            // 将thread对象的threadLocalMap属性设置为null
            ((InternalThread) thread).setThreadLocalMap(null);
        } else {
            // 移除ThreadLocal中的元素
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        slowThreadLocalMap = null;
    }

    public static int nextVariableIndex() {
        // 先返回值再加1
        int index = nextIndex.getAndIncrement();
        if (index < 0) {// 小于0表示溢出了
            nextIndex.decrementAndGet();
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }

    // 给数组设置初始值（当前类的构造函数）
    private InternalThreadLocalMap() {
        indexedVariables = newIndexedVariableTable();
    }

    // 返回index位置的数组元素， 若index超过数组长度，则返回默认值
    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    // 将indexedVariables数组中第index个元素设置为value，并返回是否成功
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            // 扩大数组，并将value值赋给 数组中第index个元素
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    // 将indexedVariables数组中第index个位置设置为默认值，并返回该位置的原有值。
    // 这个操作也相当于删除第index个位置的元素了
    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    // 返回indexedVariables数组中 InternalThreadLocal对象的对应值的个数。
    // （因为indexedVariables数组的第0个元素是set集合，而不是InternalThreadLocal对象的对应值，所以-1）
    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }

        //the fist element in `indexedVariables` is a set to keep all the InternalThreadLocal to remove
        //look at method `addToVariablesToRemove`
        return count - 1;
    }

    // 返回一个长度为32的数组，数组元素为Object类型
    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        // 用同一个对象将array填充满（元素是同一个对象）
        Arrays.fill(array, UNSET);
        return array;
    }

    // 若参数thread的成员变量threadLocalMap为空，则给它初始化个新值，返回该map
    // 若不空，则返回该成员变量map
    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    // 从本类的ThreadLocal变量中取出当前线程对应的 InternalThreadLocalMap对象，并返回该map对象
    private static InternalThreadLocalMap slowGet() {
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            // 若还没有，则给当前线程的ThreadLocal变量 赋初始值
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    // 将成员变量indexedVariables数组的长度扩大到最贴近index值的2的整数倍 （例如： index=5 则长度为8， index=11，则长度为16）
    // 并将value值赋给数组元素，即indexedVariables[index]=value
    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        // 就是要把index占的那几位，全部置成1 （int是4字节，除符号位外 占31位，这里共右移31位）
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        // 搞成2的倍数
        newCapacity++;

        // 创建一个长度为newCapacity的新数组，包含老数组的元素
        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        // 把超出老数组长度的那些元素，置为默认值。
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        // 把index位置的元素设置为value
        newArray[index] = value;
        indexedVariables = newArray;
    }
}
