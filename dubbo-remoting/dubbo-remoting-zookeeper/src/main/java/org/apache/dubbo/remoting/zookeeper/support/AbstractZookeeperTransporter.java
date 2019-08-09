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

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AbstractZookeeperTransporter is abstract implements of ZookeeperTransporter.
 * <p>
 * If you want to extends this, implements createZookeeperClient.
 */
public abstract class AbstractZookeeperTransporter implements ZookeeperTransporter {
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperTransporter.class);
    // key是zk的地址+端口, value是该zk地址对应的已连接的客户端, 例如： key="127.0.0.1:2181"
    private final Map<String, ZookeeperClient> zookeeperClientMap = new ConcurrentHashMap<>();

    /**
     * share connnect for registry, metadata, etc..
     * <p>
     * Make sure the connection is connected.
     *
     * @param url
     * @return
     */
    @Override
    // 获取一个zk客户端 ZookeeperClient对象
    public ZookeeperClient connect(URL url) {
        ZookeeperClient zookeeperClient;
        // 返回zk的主要和备用地址串集合
        List<String> addressList = getURLBackupAddress(url);
        // The field define the zookeeper server , including protocol, host, port, username, password
        // 取一个zkClient对象
        if ((zookeeperClient = fetchAndUpdateZookeeperClientCache(addressList)) != null && zookeeperClient.isConnected()) {
            logger.info("find valid zookeeper client from the cache for address: " + url);
            // 返回一个能连接上addressList中地址的zkClient对象
            return zookeeperClient;
        }
        // 没有已经连接上zk的zkClient对象
        // avoid creating too many connections， so add lock
        synchronized (zookeeperClientMap) {
            if ((zookeeperClient = fetchAndUpdateZookeeperClientCache(addressList)) != null && zookeeperClient.isConnected()) {
                logger.info("find valid zookeeper client from the cache for address: " + url);
                return zookeeperClient;
            }

            // 修改入参url的属性值, 进而新建一个zkClient对象
            zookeeperClient = createZookeeperClient(toClientURL(url));
            logger.info("No valid zookeeper client found from cache, therefore create a new client for url. " + url);
            // 将zk地址和该地址对应的zookeeperClient对象 添加到缓存zookeeperClientMap中
            writeToClientMap(addressList, zookeeperClient);
        }
        return zookeeperClient;
    }

    /**
     * @param url the url that will create zookeeper connection .
     *            The url in AbstractZookeeperTransporter#connect parameter is rewritten by this one.
     *            such as: zookeeper://127.0.0.1:2181/org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter
     * @return
     */
    protected abstract ZookeeperClient createZookeeperClient(URL url);

    /**
     * get the ZookeeperClient from cache, the ZookeeperClient must be connected.
     * <p>
     * It is not private method for unit test.
     *
     * @param addressList
     * @return
     */
    // 从缓存zookeeperClientMap寻找入参zk地址对应的zookeeperClient对象,
    // 找到则返回该zookeeperClient对象, 找不到返回null
    ZookeeperClient fetchAndUpdateZookeeperClientCache(List<String> addressList) {

        ZookeeperClient zookeeperClient = null;
        for (String address : addressList) {
            if ((zookeeperClient = zookeeperClientMap.get(address)) != null && zookeeperClient.isConnected()) {
                break;
            }
        }
        // zookeeperClient是已连接的状态时, 更新缓存zookeeperClientMap中的值，更新后还是原来的value值
        if (zookeeperClient != null && zookeeperClient.isConnected()) {
            writeToClientMap(addressList, zookeeperClient);
        }
        return zookeeperClient;
    }

    /**
     * get all zookeeper urls (such as :zookeeper://127.0.0.1:2181?127.0.0.1:8989,127.0.0.1:9999)
     *
     * @param url such as:zookeeper://127.0.0.1:2181?127.0.0.1:8989,127.0.0.1:9999
     * @return such as 127.0.0.1:2181,127.0.0.1:8989,127.0.0.1:9999
     */
    // 返回zk的主要和备用地址串的集合
    List<String> getURLBackupAddress(URL url) {
        List<String> addressList = new ArrayList<String>();
        addressList.add(url.getAddress());

        // 把备用地址加进来
        addressList.addAll(url.getParameter(Constants.BACKUP_KEY, Collections.EMPTY_LIST));
        return addressList;
    }

    /**
     * write address-ZookeeperClient relationship to Map
     *
     * @param addressList
     * @param zookeeperClient
     */
    // 将入参中的每个zk地址和该地址对应的zookeeperClient对象 添加到缓存zookeeperClientMap中
    // key是zk的地址+端口, value是zookeeperClient对象
    // 参数addressList中的所有地址, 对应同一个client
    void writeToClientMap(List<String> addressList, ZookeeperClient zookeeperClient) {
        for (String address : addressList) {
            zookeeperClientMap.put(address, zookeeperClient);
        }
    }

    /**
     * redefine the url for zookeeper. just keep protocol, username, password, host, port, and individual parameter.
     *
     * @param url
     * @return
     */
    // 更新url的成员变量path的值为 "org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter",
    // 给url的成员变量parameters赋值, url中其余属性值不变
    // 例如: URL类似 zookeeper://127.0.0.1:2181/org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter
    URL toClientURL(URL url) {
        Map<String, String> parameterMap = new HashMap<>();
        // for CuratorZookeeperClient
        // parameterMap中只有参数timeout和backup
        if (url.getParameter(Constants.TIMEOUT_KEY) != null) {
            parameterMap.put(Constants.TIMEOUT_KEY, url.getParameter(Constants.TIMEOUT_KEY));
        }
        if (url.getParameter(Constants.BACKUP_KEY) != null) {
            parameterMap.put(Constants.BACKUP_KEY, url.getParameter(Constants.BACKUP_KEY));
        }
        // 更新url中的path="org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter",
        // 给url的parameters赋值
        return new URL(url.getProtocol(), url.getUsername(), url.getPassword(), url.getHost(), url.getPort(),
                ZookeeperTransporter.class.getName(), parameterMap);
    }

    /**
     * for unit test
     *
     * @return
     */
    Map<String, ZookeeperClient> getZookeeperClientMap() {
        return zookeeperClientMap;
    }
}
