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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

public abstract class AbstractZookeeperClient<TargetDataListener, TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    private final URL url;

    // 一个状态监听器集合，用于存储所有的StateListener监听器对象。
    // 当zk连接状态改变时，会遍历这个集合，依次执行每个状态监听器对应的方法。
    // 所以，想要在zk连接状态改变时，做点自己的事，就实现这个StateListener接口，添加到这个集合中就行。
    // （因为在代码中这个集合只是在zk连接状态改变时才被使用到）
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();

    // key是zk上的路径， value是该路径对应的 子节点监听器 和 cruator监听器
    // （ cruator监听器是由子节点监听器转换来的，因为ChildListener是dubbo提供的监听器接口，需要转换为cruator的监听器接口，才能使用Curator框架操作zk）
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();

    // key是zk上的路径， value是该路径对应的 数据监听器 和 cruator监听器
    private final ConcurrentMap<String, ConcurrentMap<DataListener, TargetDataListener>> listeners = new ConcurrentHashMap<String, ConcurrentMap<DataListener, TargetDataListener>>();

    private volatile boolean closed = false;

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    /**
     * 递归创建节点，创建顺序是
     * "/dubbo"
     * "/dubbo/org.apache.dubbo.demo.DemoService"
     * "/dubbo/org.apache.dubbo.demo.DemoService/providers"
     * "/dubbo/org.apache.dubbo.demo.DemoService/providers/provider11"
     *
     *
     * （感觉用不上递归分层创建节点路径，直接创建完整节点路径不行？？）
     * 直接创建完整节点路径会报错，之前写注释的时候没有用过zk
     *
     * @param path 举例 "/dubbo/org.apache.dubbo.demo.DemoService/providers/provider11"
     * @param ephemeral 是否临时节点 true，
     *                  从代码来看ephemeral仅仅用于指定最外层的节点是否为临时节点，内层的节点已经在程序中写死为false（不是临时节点）
     *                  也就是上面例子中 ，指定provider11是否为临时节点
     */
    public void create(String path, boolean ephemeral) {
        if (!ephemeral) {
            if (checkExists(path)) {
                return;
            }
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path);
        } else {
            createPersistent(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    @Override
    // 将path对应的ChildListener和cruator监听器 添加到成员变量listeners中，并为path的直接子节点添加cruator监听器
    // 返回参数path的所有的子节点 （这个path下的直接子节点是被cruator监听器监听的）
    public List<String> addChildListener(String path, final ChildListener listener) {
        // 将path和该path对应的 子节点监听器 和 cruator监听器 添加到缓存childListeners中
        // 因为ChildListener是dubbo提供的监听器接口，需要转换为cruator的监听器接口
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            // 没有path对应的map则新增
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        // 通过子节点监听器得到对应的cruator监听器
        TargetChildListener targetListener = listeners.get(listener);
        if (targetListener == null) {
            // 若还没有对应的cruator监听器，则新建一个
            // createTargetChildListener会对ChildListener监听器进行转换，转成cruator监听器
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        // 为path的直接子节点添加cruator监听，并且返回这个path的所有子节点名（targetListener就是cruator监听器）
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener, null);
    }

    @Override
    // 将path对应的DataListener和cruator监听器 添加到成员变量listeners中，并为path添加TreeCache类型的监听器
    public void addDataListener(String path, DataListener listener, Executor executor) {
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap == null) {
            listeners.putIfAbsent(path, new ConcurrentHashMap<DataListener, TargetDataListener>());
            dataListenerMap = listeners.get(path);
        }
        // 创建DataListener对应的cruator监听器
        TargetDataListener targetListener = dataListenerMap.get(listener);
        if (targetListener == null) {
            // 若还没有对应的cruator监听器，则新建一个
            // createTargetDataListener会对DataListener监听器进行转换，转成cruator监听器
            dataListenerMap.putIfAbsent(listener, createTargetDataListener(path, listener));
            // 取出转后的cruator监听器
            targetListener = dataListenerMap.get(listener);
        }
        // 为path添加TreeCache类型的监听器。targetListener就是TreeCache对象使用的监听器
        addTargetDataListener(path, targetListener, executor);
    }

    @Override
    // 移除path对应的数据监听器listener， 并将path对应的cruator监听器与数据监听器的联系断开。
    public void removeDataListener(String path, DataListener listener ){
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetListener = dataListenerMap.remove(listener);
            if(targetListener != null){
                // 将Cruator监听器(targetListener)和它对应的数据监听器的联系断开
                removeTargetDataListener(path, targetListener);
            }
        }
    }

    @Override
    // 移除path对应的子节点监听器listener， 并将path对应的cruator监听器与该子节点监听器的联系断开。
    public void removeChildListener(String path, ChildListener listener) {
        // 取path对应的子节点监听器 和 cruator监听器
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            // 移除子节点监听器listener，返回cruator监听器
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                // 将Cruator监听器(targetListener)和它对应的子节点监听器的联系断开
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    // 这个create和前面的create函数功能一样，
    // 区别是，若path节点已存在，则会删除，再重新创建。
    public void create(String path, String content, boolean ephemeral) {
        if (checkExists(path)) {
            delete(path);
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path, content);
        } else {
            createPersistent(path, content);
        }
    }

    @Override
    // 取path节点的内容，若path不存在则返回null
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    protected abstract boolean checkExists(String path);

    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

    protected abstract String doGetContent(String path);

}
