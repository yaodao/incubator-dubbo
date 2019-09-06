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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * InternalThreadLocal
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link InternalThread}.
 * <p></p>
 * Internally, a {@link InternalThread} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * <p></p>
 * This design is learning from {@see io.netty.util.concurrent.FastThreadLocal} which is in Netty.
 */

// InternalThreadLocal 是ThreadLocal的一个变种， 当使用InternalThread类型的线程对它进行存取时，可以带来更高效的性能。
// （通常，我们使用Thread类型的线程对ThreadLocal类型的变量 进行存取。 这里自定义一个Thread类的子类InternalThread，来对InternalThreadLocal类型的变量进行存取）
// 从内部结构来看， InternalThread在查找自己对应的元素时，直接使用的下标来查找数组，而不是先计算得到hash值再查找hash table。
// 尽管性能提升表面看来不是很明显，但在存取频繁的场景下很有效。
public class InternalThreadLocal<V> {

    // 因为这个值只会被初始化一次， 所以值永远=0 （本类被加载时，静态变量初始化，就赋值了）
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    // index表示当前InternalThreadLocal对象的对应值，在线程对应的map中的下标（对应值的意思是，线程在调用InternalThreadLocal.get()时 取到的那个值）
    // 每个InternalThreadLocal对象有自己固定的index值，这点从下面的构造函数中也可以看出来。
    private final int index;

    // 每个InternalThreadLocal对象的index值是固定的， 点进去可以看出，不同的InternalThreadLocal对象的index值肯定是不一样的。
    public InternalThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Removes all {@link InternalThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     *
     * 删除所有和当前线程绑定的InternalThreadLocal变量，这个操作在某些容器环境中是有用的，
     * 因为我们不想将这些ThreadLocal变量留在线程中，当我们不再拥有这些线程的控制权的时候。
     * （实际上这句话的意思就是说，容器中的线程池的线程是复用的，当我的某一次请求结束之后，如果不清理掉这些线程本地变量，就会污染下一次分配到使用这个线程的请求）
     *
     */
    @SuppressWarnings("unchecked")
    // 将当前线程的所有InternalThreadLocal对象和对应值清空
    public static void removeAll() {
        // 获取当前线程对应的map
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            // 取当前线程所用到的InternalThreadLocal对象集合
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                Set<InternalThreadLocal<?>> variablesToRemove = (Set<InternalThreadLocal<?>>) v;
                InternalThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new InternalThreadLocal[variablesToRemove.size()]);
                for (InternalThreadLocal<?> tlv : variablesToRemoveArray) {
                    // 将单个InternalThreadLocal对象 和对应值 从map中移除
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            // 最后，为当前线程的map属性赋空值
            // （我个人认为，这里的做法是正确的，不能直接给当前线程的map属性赋空值，
            // 需要先把当前线程对应的map中元素先清空，也就是try那段代码是必须的，因为InternalThreadLocalMap的slowThreadLocalMap属性是强引用。
            // 如果没有try这段代码，而是直接给map赋空，那么map中的元素，依然在内存中，不会被回收，因为他们被slowThreadLocalMap引用到了）
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    // 返回当前线程所拥有的InternalThreadLocal对象的数量
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    @SuppressWarnings("unchecked")
    // 将入参variable添加到threadLocalMap的第0个位置的元素中
    // 本质上就是将InternalThreadLocal变量，添加到当前线程对应的map中（当前线程可能用到很多InternalThreadLocal对象，调用本函数一次就添加一个InternalThreadLocal对象到map）
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, InternalThreadLocal<?> variable) {
        // 获取map在位置0处的元素v（得到的v是一个set集合，该集合保存着当前线程用到的所有InternalThreadLocal对象）
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<InternalThreadLocal<?>> variablesToRemove;
        // 若v还没有值，则给v设置值
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            // IdentityHashMap中比较两个元素是否相等，通过==而不是equals来比较
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<InternalThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<InternalThreadLocal<?>>) v;
        }
        // 把入参variable对象添加到集合v中
        variablesToRemove.add(variable);
    }

    @SuppressWarnings("unchecked")
    // 取threadLocalMap的第0位置的元素（该元素是一个set集合），将variable从该集合中移除
    private static void removeFromVariablesToRemove(InternalThreadLocalMap threadLocalMap, InternalThreadLocal<?> variable) {

        // 取当前线程对应的map的第0位置的元素
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        Set<InternalThreadLocal<?>> variablesToRemove = (Set<InternalThreadLocal<?>>) v;
        // 从set中移除variable这个InternalThreadLocal类型的对象（这个set中存放的是该线程使用的所有InternalThreadLocal对象）
        variablesToRemove.remove(variable);
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    // 获取当前线程在InternalThreadLocal对象中保存的值
    public final V get() {
        // 获取一个和当前线程相关的map
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            // 若index位置有值了，则直接返回
            return (V) v;
        }

        // 若map中 index位置的值还是默认值InternalThreadLocalMap.UNSET，则需要给该位置赋一个初始值
        return initialize(threadLocalMap);
    }

    // 给当前线程对应的map的第index元素赋值，并添加InternalThreadLocal对象到当前线程对应的map的第0个元素中
    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 给map的index位置赋值为v
        threadLocalMap.setIndexedVariable(index, v);
        // 添加this到当前线程对应的map的第0个位置的元素中
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Sets the value for the current thread.
     */
    public final void set(V value) {
        if (value == null || value == InternalThreadLocalMap.UNSET) {
            remove();
        } else {
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            if (threadLocalMap.setIndexedVariable(index, value)) {
                addToVariablesToRemove(threadLocalMap, this);
            }
        }
    }

    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    @SuppressWarnings("unchecked")
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    // 将当前的InternalThreadLocal对象 和对应值 从入参map中移除
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }
        // 从当前线程对应的map中移除第index位置的元素，并返回该位置的值
        Object v = threadLocalMap.removeIndexedVariable(index);
        // 取threadLocalMap的第0位置的元素（该元素是一个集合），将this从该集合中移除
        removeFromVariablesToRemove(threadLocalMap, this);

        // 若被移除的v不是默认值
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                // 可以使用自定义的方法来处理这个被移除的值
                onRemoval((V) v);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}.
     */
    protected void onRemoval(@SuppressWarnings("unused") V value) throws Exception {
    }
}
