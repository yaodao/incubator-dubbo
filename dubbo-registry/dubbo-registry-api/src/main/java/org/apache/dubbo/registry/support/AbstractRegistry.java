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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 */
public abstract class AbstractRegistry implements Registry {

    // URL address separator, used in file cache, service provider URL separation
    private static final char URL_SEPARATOR = ' ';
    // URL address separated regular expression for parsing the service provider URL list in the file cache
    // 用于分隔properties中的value值，这个value值是由服务者url组成的字符串
    private static final String URL_SPLIT = "\\s+";
    // Max times to retry to save properties to local cache file
    // 保存properties到本地文件的最大重试次数
    private static final int MAX_RETRY_TIMES_SAVE_PROPERTIES = 3;
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    // Local disk cache, where the special key value.registries records the list of registry centers, and the others are the list of notified service providers
    /**
     * properties的数据跟本地文件的数据同步，当启动时，会从文件中读取数据到properties，而当properties中数据变化时，会写入到file。
     * properties是一个key对应一个列表，比如说key就是消费者的url，而值就是服务提供者列表、路由规则列表、配置规则列表。
     * 就是类似属性notified的含义。需要注意的是properties有一个特殊的key为registies，记录的是注册中心列表。
     */
    // 本地文件的缓存，有一个特殊的key值为registies，记录的是注册中心列表，其他记录的都是服务提供者列表
    private final Properties properties = new Properties();
    // File cache timing writing
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
    // Is it synchronized to save the file
    // 是否同步保存文件到本地
    private final boolean syncSaveFile;
    // 标识成员变量properties内容的是否有变化， 数值越大properties值越新。
    // 因为每次写入file都是全部覆盖的写入，不是增量的去写入到文件，所以需要有这个版本号来避免老版本覆盖新版本。
    private final AtomicLong lastCacheChanged = new AtomicLong();
    // 保存properties到本地文件这个操作的 已重试次数
    private final AtomicInteger savePropertiesRetryTimes = new AtomicInteger();
    // 记录已经注册服务的URL集合，注册的URL不仅仅可以是服务提供者的，也可以是服务消费者的。
    // 已注册的服务的URL集合
    private final Set<URL> registered = new ConcurrentHashSet<>();
    // key是消费者url, value是该url对应的监听器集合
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<>();
    // 最外部URL的key是消费者的URL,value是一个map，map的key为服务的分类名，value是该分类下的服务url集合。分类名就是 url的"category"属性值
    private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<>();
    // 注册中心URL
    private URL registryUrl;
    // Local disk cache file
    // 本地缓存文件, 缓存注册中心的数据
    private File file;

    /**
     *
     * 构造AbstractRegistry对象，
     * 这个过程中，会把本地文件里面的数据写入到properties，以及通知监听器 服务提供者url的变化信息。
     *
     *
     * 注册中心url示例:
     * URL url = URL.valueOf("dubbo://192.168.0.2:2233");
     * //sync update cache file
     * url = url.addParameter("save.file", true);
     *
     * @param url
     */
    public AbstractRegistry(URL url) {
        // 设置注册中心的地址
        setUrl(url);
        // Start file save timer
        // 注册中心的URL中是否配置了同步保存文件属性，若没有配置则默认为false
        syncSaveFile = url.getParameter(Constants.REGISTRY_FILESAVE_SYNC_KEY, false);
        // 创建一个本地的文件，用来缓存注册中心的数据 (其实没有创建文件，只是把目录建了)
        // 从url的成员变量parameters中, 取key="file"对应的的value值
        // 若没有就取默认值 "C:/Users/lee/.dubbo/dubbo-registry-{application}-{host:port}.cache"
        // 举例： 在windows中，默认生成的缓存文件为：C:\Users\lee/.dubbo/dubbo-registry-127.0.0.1.cache
        String filename = url.getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(Constants.APPLICATION_KEY) + "-" + url.getAddress() + ".cache");
        File file = null;
        // 逐层创建文件目录
        if (ConfigUtils.isNotEmpty(filename)) {
            file = new File(filename);
            if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException("Invalid registry cache file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                }
            }
        }
        this.file = file; // 本地文件对象
        // When starting the subscription center,
        // we need to read the local cache file for future Registry fault tolerance processing.
        // 读取file到properties
        loadProperties();
        //  将最新的服务提供者的url信息推送给订阅该服务的消费者
        notify(url.getBackupUrls());
    }

    // 若urls为空， 则构造一个非空集合返回，并且集合中只有一个protocol="empty"的url对象
    // 若urls不空， 则直接返回
    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            List<URL> result = new ArrayList<>(1);
            // 构造一个protocol="empty"的url对象， 添加到集合中返回
            result.add(url.setProtocol(Constants.EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    // 获取注册中心URL
    public URL getUrl() {
        return registryUrl;
    }

    // 设置成员变量registryUrl
    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return Collections.unmodifiableSet(registered);
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return Collections.unmodifiableMap(subscribed);
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return Collections.unmodifiableMap(notified);
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    /**
     * 将成员变量properties内容存储到文件，
     * 为了解决同步写入问题，增加了一个.lock文件作为锁。
     * 并且在里面做了版本号的控制，防止老的版本号对应的数据覆盖了新版本号对应的数据，通过版本号乐观锁控制取到的properties是最新的值，再写到文件
     * @param version 乐观锁
     */
    public void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {
            // 如果版本号比当前版本老，则不保存
            // 说明成员变量properties刚刚又被更改了，则当前这次的不保存了
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        // 将成员变量properties中的内容保存到成员变量file指定的文件中
        try {
            // 新建一个.lock的文件， 充当锁的作用，用于控制多个JVM对file变量所代表的文件的写入。
            // 若是在同一个JVM中，当多个线程一起执行到这里时，如果一个线程获取到锁，其他线程在调用channel.tryLock时就会抛出异常（试过）
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            try (RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
                 FileChannel channel = raf.getChannel()) {
                // 获取文件的独占锁
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                }
                // Save
                try {
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    try (FileOutputStream outputFile = new FileOutputStream(file)) {
                        properties.store(outputFile, "Dubbo Registry Cache");
                    }
                } finally {
                    lock.release();
                }
            }
        } catch (Throwable e) {
            // 保存properties到文件的已重试次数+1
            savePropertiesRetryTimes.incrementAndGet();
            if (savePropertiesRetryTimes.get() >= MAX_RETRY_TIMES_SAVE_PROPERTIES) {
                // 已重试次数若大于3， 则重置它为0，不再重试，直接返回
                logger.warn("Failed to save registry cache file after retrying " + MAX_RETRY_TIMES_SAVE_PROPERTIES + " times, cause: " + e.getMessage(), e);
                savePropertiesRetryTimes.set(0);
                return;
            }
            // 版本号比当前版本老, 停止重试，返回
            if (version < lastCacheChanged.get()) {
                savePropertiesRetryTimes.set(0);
                return;
            } else {
                // 再次执行doSaveProperties()函数，感觉要是没有最大重试次数限制，这个函数他么就是个死循环
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry cache file, will retry, cause: " + e.getMessage(), e);
        }
    }

    // 若本地缓存文件存在, 则读本地缓存文件内容到成员变量properties中
    // 和上面的方法doSaveProperties()的数据流向相反。
    private void loadProperties() {
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry cache file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry cache file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * 将参数url对应的 多个服务者url串以集合形式返回
     * （该方法是获得内存缓存properties中相关value，并且以集合形式返回value）
     *
     * @param url 消费者url
     * @return
     */
    // 获取缓存properties中，参数url对应的 服务提供者url的集合
    public List<URL> getCacheUrls(URL url) {
        // 由入参url得到一个字符串 "{group}/{interfaceName}:{version}"
        // 从properties取出该字符串对应的value值， 这个值是以空格分隔的多个服务url字符串。(多个服务url串是在saveProperties()函数中添加到properties中)
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            if (key != null && key.length() > 0 && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && value != null && value.length() > 0) {
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }

    @Override
    // 获取参数url对应的服务者url集合
    public List<URL> lookup(URL url) {
        // 服务url集合
        List<URL> result = new ArrayList<>();
        // 获取消费者url订阅的服务提供者url集合
        // notifiedUrls的key为服务的分类名，value是该分类下的服务url集合。 分类名就是 url的"category"属性值
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        // 该消费者是否订阅了服务
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    // 将protocl="empty"的服务者url过滤掉，其余的加入result
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        } else {
            final AtomicReference<List<URL>> reference = new AtomicReference<>();
            /**
             * NotifyListener listener = reference::set;这句等价于
             * NotifyListener listener = new NotifyListener() {
             *                 @Override
             *                 public void notify(List<URL> urls) {
             *                     reference.set(urls);
             *                 }
             *             };
             */
            // todo 这个监听器是针对所有的 服务提供者url的监听器么？？ 应该是
            NotifyListener listener = reference::set;
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (CollectionUtils.isNotEmpty(urls)) {
                for (URL u : urls) {
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    @Override
    // registered中新增一个url， 这个函数已经被子类覆盖（具体怎样实现注册，需要看具体子类实现）
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    @Override
    // registered中移除一个url
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    @Override
    /**
     * 为参数url，新增加一个监听器listener（其实就是url又订阅了一个服务，针对这个服务的监听器是listener）
     *
     * 将（url，url所拥有的新的监听器listener） 添加到成员变量subscribed中
     * 当前对象的成员变量subscribed中增加一个监听器listener， 该监听器是属于参数url的
     *
     * @param url      消费者url, e.g. consumer://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     * @param listener A listener of the change event, not allowed to be empty
     */
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }
        // 获得该消费者url 已经订阅的服务的监听器集合
        Set<NotifyListener> listeners = subscribed.computeIfAbsent(url, n -> new ConcurrentHashSet<>());
        // 新增监听器
        listeners.add(listener);
    }

    @Override
    // 从成员变量subscribed中 移除消费者url所拥有的某个监听器listener
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        // 获得该消费者url 已经订阅的服务 的监听器集合
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    protected void recover() throws Exception {
        // register
        // 把缓存registered中的元素取出来, 遍历进行注册 （就是重新加到缓存registered中）
        Set<URL> recoverRegistered = new HashSet<>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                register(url);
            }
        }
        // subscribe
        // 把缓存subscribed中的元素取出来, 遍历进行订阅 （就是重新加到缓存subscribed中）
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    subscribe(url, listener);
                }
            }
        }
    }

    /**
     * 遍历监听器，通知监听器,服务提供者的URL有变化，
     * （通过入参服务提供者url找到订阅该服务的消费者url和监听器，并将最新的服务提供者的url信息推送给订阅该服务的消费者）
     *
     * @param urls 服务提供者url集合
     */
    protected void notify(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }

        // 遍历消费者url订阅的监听器集合，通知他们
        // entry中 key是消费者url, value是该url对应的监听器集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            // 消费者url
            URL url = entry.getKey();

            // 找到一个消费者url 能和参数urls匹配
            // 判断消费者url 和服务者的url是否匹配， 若不匹配则判断下一个消费者url
            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }

            // 和参数urls匹配的消费者url找到了

            // 该消费者url的监听器集合
            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                // 将服务者最新的urls， 推送给消费者url的每个监听器 (感觉这里是把所有的服务url都推给每个监听器，甭管该监听器是否监听该服务url，应该是监听器自己过滤需要的服务url信息)
                for (NotifyListener listener : listeners) {
                    try {
                        // 将服务者最新的urls， 推送给消费者url的每个监听器 (这样消费者就知道了服务者url中的改动信息)
                        notify(url, listener, filterEmpty(url, urls));
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * Notify changes from the Provider side.
     * 通知监听器listener，服务提供者URL的变化信息
     * 主要完成以下功能：
     * 1、将参数urls中 与消费者url匹配的服务提供者url进行分类，分类到map中，map的key是服务url中的"category"值， value是相同"category"的服务url的集合
     * 2、根据参数urls的分类结果，更新成员变量notified的内容
     * 3、将分组的服务提供者URL推送给监听器listener
     * 4、更新缓存properties和本地文件内容
     *
     * @param url      consumer side url 消费者url
     * @param listener listener 监听器
     * @param urls     provider latest urls (最新的服务提供者url集合)
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((CollectionUtils.isEmpty(urls))
                && !Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }
        // keep every provider's category.
        // 将参数urls进行分类， 分类到result，result的key是"{category}",value是该种类的url集合（将urls中 与消费者url匹配的服务提供者url进行分类）
        // 注意： result是服务提供者的分类
        Map<String, List<URL>> result = new HashMap<>();
        for (URL u : urls) {
            // 将与消费者url匹配的服务提供者u进行分类
            if (UrlUtils.isMatch(url, u)) {
                // category= url中的"category"属性值 或者 category= "providers" （默认值）
                String category = u.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
                List<URL> categoryList = result.computeIfAbsent(category, k -> new ArrayList<>());
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }

        // 获取消费者url对应的 服务提供者url集合 （得到的map中，key是服务url中的"category"值， value是相同"category"的服务url的集合）
        Map<String, List<URL>> categoryNotified = notified.computeIfAbsent(url, u -> new ConcurrentHashMap<>());

        // 因为服务提供者url的按cateogry分组的， 这里就按组推送服务提供者URL的变化给服务订阅者
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            String category = entry.getKey();
            List<URL> categoryList = entry.getValue();
            // 更新该category对应的服务提供者url集合值
            categoryNotified.put(category, categoryList);
            // 将一组服务提供者URL推送给订阅了该URL的监听器。这里已经是按cateogry分组推送了
            listener.notify(categoryList);
            // We will update our cache file after each notification.
            // When our Registry has a subscribe failure due to network jitter, we can return at least the existing cache URL.
            // 更新缓存properties和本地文件内容（每个分组调用一次，为什么这么频繁每个分组调一次，参见上面英文）
            saveProperties(url);
        }
    }

    /**
     * 将入参url对应的服务者url信息更新到properties中， 并保存properties到本地文件
     * key是"{group}/{interfaceName}:{version}"， 其中值由入参url提供
     * @param url 消费者url
     */
    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            StringBuilder buf = new StringBuilder();
            // 获得消费者url对应的map，key为分类名，value是该分类下的服务者url集合
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                // 不分类别的把所有服务提供者的url转成串，连接起来，用空格隔开
                // 将该消费url对应的 不分类别的所有的服务者url转成服务者的串，不同url转成的串之间用空格隔开
                for (List<URL> us : categoryNotified.values()) {
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        // 不同url转成的串之间用空格隔开
                        buf.append(u.toFullString());
                    }
                }
            }
            // key是"{group}/{interfaceName}:{version}" ，value是上面得到的串， 新增到properties中
            properties.setProperty(url.getServiceKey(), buf.toString());
            // 成员变量properties有新的变动
            long version = lastCacheChanged.incrementAndGet();
            // 若需要同步保存服务者的变化到本地文件
            if (syncSaveFile) {
                // 同步将properties保存到文件
                doSaveProperties(version);
            } else {
                // 异步保存properties到本地文件
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    // 该方法在JVM关闭时调用，进行取消注册和取消订阅的操作。
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }
        // 从缓存registered中移除所有 "dynamic"值为true的服务提供者的url对象
        Set<URL> destroyRegistered = new HashSet<>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<>(getRegistered())) {
                // 若url中"dynamic"值为true， 则从缓存registered中移除该url
                if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                    try {
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            // 从缓存subscribed中移除所有的监听器，就是把缓存subscribed清了
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}
