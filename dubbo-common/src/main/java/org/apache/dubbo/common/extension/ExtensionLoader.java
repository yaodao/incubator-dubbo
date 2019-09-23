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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    // "META-INF/dubbo/internal/"
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    // 匹配 "空格,空格", 其中空格可以没有或者有多个, 逗号必须有
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    // key是成员变量type, value是该type对应的ExtensionLoader对象
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    // key是Class对象, value是该Class对应的实例对象
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>();

    // ==============================

    // 带@SPI注解的接口
    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    // key是clazz， value是该类的别名
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    // 存放着一个map, key是文件中的类别名, value是类对应的Class对象 (这个map是加载文件中的记录生成的)
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    //  key是name (文件中的类别名), value是clazz的@Activate注解对象 (这个map中只存放带@Activate注解的类)
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    // key是文件中的类别名, value中存放的是类实例 (key所对应的类的实例)
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    // 存储type接口下 @Adaptive标注的实现类
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    // type接口对应的实现类 (带@Adaptive注解的实现类)
    // type=ExtensionFactory.class时, 值为AdaptiveExtensionFactory.class (在执行getExtensionClasses()时赋值)
    private volatile Class<?> cachedAdaptiveClass = null;
    // 存放@SPI注解的value值, 例如: 对ThreadPool来说是"fix"
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    // type接口对应的包装类 (这里的包装类是指, 该类有参数为type类型的构造函数)
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    // 该构造函数被调用时
    // 当type=ExtensionFactory.class时, 得到ExtensionLoader对象 (type=ExtensionFactory.class,objectFactory=null),
    // 其他成员变量暂时没有被赋值, 等到getExtensionClasses()被调用时, 才会被赋值
    // 当type!=ExtensionFactory.class时, 例如: type=Compile.class时
    // 得到ExtensionLoader对象 (type=Compile.class, objectFactory=AdaptiveExtensionFactory的对象), 其他成员变量也被赋值
    private ExtensionLoader(Class<?> type) {
        this.type = type;

                                                                // ExtensionLoader.getExtensionLoader(ExtensionFactory.class)这句
                                                                // 就是简单的生成一个ExtensionLoader对象， obj(type=ExtensionFactory.class,objectFactory=null)，并将（type，obj）添加到成员变量EXTENSION_LOADERS中
                                                                // 再调用getAdaptiveExtension() 返回AdaptiveExtensionFactory对象 (这个过程会给loader对象中的其他成员变量赋值)
                                                                // 这里调用getAdaptiveExtension()，用到了调用者的type属性值，因为调用者是由前半句生成的loader对象，所以type=ExtensionFactory.class
                                                                // 从代码来看，这句的返回值一直是AdaptiveExtensionFactory对象，因为getExtensionLoader的参数写死了为ExtensionFactory.class
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    // 是否带@SPI注解, true 带
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    // 从成员变量EXTENSION_LOADERS中取参数type对应的ExtensionLoader对象，并返回该loader对象
    // （若没有，则生成一个该type对应的loader对象并添加EXTENSION_LOADERS中）

    // 当type=ExtensionFactory.class, 返回ExtensionLoader类型的对象 (type=ExtensionFactory.class, objectFactory=null)
    // 当type!=ExtensionFactory.class时,
    // 例如:
    // type=Compile.class，则返回ExtensionLoader对象 (type=Compile.class, objectFactory=AdaptiveExtensionFactory的对象),期间ExtensionLoader对象的其他成员变量也被赋值
    // 上面说的 （type， loader对象）会添加到成员变量EXTENSION_LOADERS中

    // 获取或者生成一个ExtensionLoader对象。
    // 返回值举例：
    // 当入参type=Compile.class时，返回loader对象 (type=Compile.class, objectFactory=AdaptiveExtensionFactory对象)
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        // 带@SPI注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }
        // 能到这 说明type是接口 且上面有@SPI注解
        // 取该type对应的ExtensionLoader对象
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            // 当参数type=ExtensionFactory.class,
            // EXTENSION_LOADERS新增 entry (ExtensionFactory.class, (ExtensionFactory.class, null))
            // key是扩展点, value是该扩展点对应的loader (每个扩展只会被添加一次)
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        // 返回ExtensionLoader类型的对象
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    /**
     * 获取类加载器 (该类加载器加载了当前线程所需要的那些类,如果为空,则获取加载了ExtensionLoader类的类加载器)
     * @return
     */
    private static ClassLoader findClassLoader() {
        return ClassHelper.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    // 在所有的使用@Activate标注的类中，要使用key指定的扩展
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    // 在所有的使用@Activate标注的类中，使用 values指定的扩展
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */

    // 在所有的使用@Activate标注的类中，返回value名字对应的扩展
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 先用key从 url对象中得到value值
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */


    /**
     * 获取当前type接口的所有可自动激活的实现类的对象 (可自动激活的实现类就是带@Activate注解的那些实现类)，
     * （注意： 并不是把type类型的所有自动激活的实现类都返回。
     * 入参values中指定的类的对象肯定会返回，
     * 其他的type的实现类，就看该类的@Activate注解内的值和入参url的属性名是否匹配，匹配则返回）
     *
     * 当入参values和group都为空时，会返回那些和入参url匹配的实现类的对象的集合
     * （匹配规则是： 实现类的@Activate注解的value值 与url对象的parameters属性中的某个key相等，就算匹配）
     *
     * @param url  服务提供者url （暂时不能确定是服务提供者url还是消费者url）
     * @param values 要加载的类的别名(文件中的名字)
     * @param group 举例: group="group1", group="consumer"
     * @return
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        // 返回结果，元素是type的带@Activate注解的实现类的对象
        List<T> exts = new ArrayList<>();
        // 若入参values是空，则给names赋一个空数组
        // 别名集合
        List<String> names = values == null ? new ArrayList<>(0) : Arrays.asList(values);

        /**
         *  这段if 处理的是names数组中没有的那些别名（names数组中有的别名在下段代码处理）
         *  具体做法是：比较入参url的属性名 和 别名所代表的类的@Activate注解的value值，若两者相等，则将类对象存入exts
         */
        // 如果names中没有"-default",则加载所有的Activates扩展
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            // 加载type接口的所有带@Activate注解的实现类 (type是带@SPI的任意接口)
            // 这个过程中会填充当前对象的成员变量cachedActivates, key是name（类的别名）, value是@Activate注解自身
            getExtensionClasses();
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                // name就是文件中的类别名
                String name = entry.getKey();
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                // 获取@Activate注解中的group和value值,
                // 例如: @Activate("cache, validation"), value()值就是"cache, validation"
                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    // activateValue赋值为@Activate注解的value值
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                // 若group为空，则进if
                // 若group不为空，若activateGroup数组中有入参group的值，则进if
                if (isMatchGroup(group, activateGroup)) {
                    // 这里是使用别名取的实现类对象，而不是使用@Activate注解的value值
                    T ext = getExtension(name);

                    /**
                     * names数组中没有该name元素  且  names数组中没有指定移除该扩展(-name)  且  当前url匹配结果显示可激活才能add到exts中
                     * 例如 activateValue=["cache, validation"], 当url的parameters中存在key="cache" 或者key="validation"时,
                     * 才能把该接口的实现类的对象ext添加到exts
                     */
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activateValue, url)) {

                        // ext添加到exts
                        exts.add(ext);
                    }
                }
            }
            exts.sort(ActivateComparator.COMPARATOR);
        }


        /**
         * names数组中已有的别名，在这里处理
         *
         * 将names集合中的元素（类的别名）对应的类的对象, 添加到exts中（会过滤掉names集合中以"-"开头的元素）
         */
        List<T> usrs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            // names中的"-name"元素, 不添加到exts中, 其余都添加到exts中

            // 若name不以"-"开头，进if
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {

                // 将names集合中的元素分成两部分，以值="default"的那个元素分开。
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        // 配置在default之前的Activate扩展, 放到前面
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    //  获取name对应的类的对象，将该对象添加到usrs
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            // 配置在default之后的放到后面
            exts.addAll(usrs);
        }
        return exts;
    }

    // 数组groups是否包含group值, true 包含, 若group为空 直接返回true
    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 入参keys数组中的元素，只要有一个和url对象的parameters属性中的key相等, 就返回true,（parameters中key对应的value值不能为空）
    // 入参keys为空直接返回true
    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                // k是 url中的属性名（具体是url对象的parameters集合中的key值）
                String k = entry.getKey();
                String v = entry.getValue();
                // （k等于key 或者 k以".{key}"结尾 ） 且 v不为空, 则返回true
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    // 取name对应的Holder对象(从cachedInstances中取), 没有会新增一个
    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    // 返回name对应的类的一个对象, name是文件中的类别名
    public T getExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            // name="true"表示取默认值
            return getDefaultExtension();
        }
        // 获取name对应的Holder对象, 从成员变量cachedInstances中取
        Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        // 若holder中没有值, 则set一个值
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 根据name，找到对应的clazz， 再创建一个对象返回
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    // 根据成员变量cachedDefaultName的值取对应的对象实例 (@SPI注解的value值就是cachedDefaultName)
    public T getDefaultExtension() {
        // 加载文件中的类，给当前对象的成员变量赋值
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            // cachedDefaultName为空  或者  cachedDefaultName="true"，则返回null
            return null;
        }
        // cachedDefaultName的默认值是 @SPI注解的value值
        return getExtension(cachedDefaultName);
    }

    // 参数name是否有对应的clazz
    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        // 从成员变量cachedClasses获取参数name对应的Class对象
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    // 返回type接口的所有实现类的类别名集合
    // （这句api说明写的很到位，返回的只是type接口的实现类的名字，不包括其它接口的实现类的名字）
    public Set<String> getSupportedExtensions() {
        // 将文件中记录解析map (key是文件中的类别名, value是类对应的Class对象)
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    // 返回type接口所对应的 带有@Adaptive注解的类的对象, 并设置到cachedAdaptiveInstance中 ，若type下没有带有@Adaptive注解的类，则会动态生成一个返回
    // (type接口下@Adaptive标注的实现类只能有一个)
    // 这个函数被调用的时候, 成员变量type已经被赋值了
    public T getAdaptiveExtension() {
        Object instance = cachedAdaptiveInstance.get();
        // 缓存中没有，才会生成一个
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 得到type接口所对应的Adapter类的对象 (这个函数主要就这一句)
                            instance = createAdaptiveExtension();
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    // 入参name是类的别名，type是类的接口类型
    // 先根据name取到对应的clazz, 再使用clazz new一个对象 (并给该对象的属性注入值) 返回该对象
    // 若type有包装类, 则返回包装类的对象
    // 就是返回name对应的实例对象。
    private T createExtension(String name) {
        // 从成员变量cachedClasses中取name对应的value (cachedClasses存的是从文件中读的内容)
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            // 将取到的clazz放到EXTENSION_INSTANCES中 (clazz是type的具体实现类的clazz)
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                // add
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            // 若type有包装类
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    // 用上面得到的instance做参数 实例化一个包装类 (包装类的构造函数的参数是type类型) 赋值给instance
                    // 若wrapperClasses中有多个元素, 这里就是层层包装 (外层的包装会把里面的包装一起包住) 参见https://blog.csdn.net/sinat_23067771/article/details/79716552
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    // 将入参instance的属性 注入值
    private T injectExtension(T instance) {
        try {
            // 当前对象的type=ExtensionFactory.class时 objectFactory为空, 所以进不去if, 直接返回AdaptiveExtensionFactory对象
            // （因为type=ExtensionFactory.class时，这里的入参instance的值就是AdaptiveExtensionFactory对象）

            // 当前对象的type是其他类型时, 当该injectExtension方法被调用时，这时的objectFactory已经有值了，进入if。
            /**
             * 这个objectFactory值是在调用： ExtensionLoader.getExtensionLoader(Protocol.class) 这类的代码时，被赋上值的，
             * 调用ExtensionLoader.getExtensionLoader(Protocol.class) 会得到一个loader对象，而且，该loader对象的objectFactory属性值是AdaptiveExtensionFactory对象
             * 完整代码是：Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
             * 也就是先得到一个loader对象，而且loader对象中的objectFactory属性已经有值了， 再调用getAdaptiveExtension()。
             */
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    // 只处理set方法
                    if (isSetter(method)) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         */
                        // 跳过不需要注入的属性
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        // method中第一个参数的clazz对象
                        Class<?> pt = method.getParameterTypes()[0];
                        if (ReflectUtils.isPrimitives(pt)) {
                            // 基本类型不需要注入, 换下一个method
                            continue;
                        }
                        try {
                            // method操作的属性名
                            String property = getSetterProperty(method);
                            // 这里可以从SpiExtensionFactory和SpringExtensionFactory中取出property对应的bean
                            // 例如: 可以根据名字(property)从spring的容器中取出bean, 参考 https://www.cnblogs.com/GrimMjx/p/10970643.html
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                // 将bean注入instance的属性中
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    // 返回method对应的属性名, 例如: setVersion方法 返回 "version"
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    // 是 public set** 方法返回true
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    // 从成员变量cachedClasses获取参数name对应的Class对象
    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    // 加载文件中的类到map, 并将map设置到成员变量cachedClasses中 (map中的 key是文件中的类别名, value是类对应的Class对象)
    // 这个过程中, 会使用文件中的类的信息 给当前对象的成员变量赋值
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    // 个人感觉，当前类的成员变量值，都是通过解析文件的内容得到。
    //（当前类的type值改变，就可以读取并解析其他的对应文件的内容，也就是说，当用到这些文件中的内容，才加到内存中）

    // 本函数中，根据当前类的type属性值，来读取并解析对应的文件，最后返回一个map
    // 例如： 当type=ExtensionFactory.class时， 读取的文件是META-INF\dubbo\internal\org.apache.dubbo.common.extension.ExtensionFactory
    // 文件内容: "spi=org.apache.dubbo.common.extension.factory.SpiExtensionFactory"
    // 返回值map中，key是spi, value是SpiExtensionFactory.class
    private Map<String, Class<?>> loadExtensionClasses() {
        // 设置成员变量cachedDefaultName的值
        cacheDefaultExtensionName();

        // 刚开始extensionClasses是空的，最终key是文件中的类别名, value是类对应的Class对象
        Map<String, Class<?>> extensionClasses = new HashMap<>();

        /**
         * 加载文件中每条记录对应的类 （在调用本函数的地方进行了线程同步），
         * 依次处理每条记录对应的类， 根据得到的类信息设置成员变量的值 或者 为成员变量添加值。
         *
         * 例如：
         * 若该类带@Adaptive注解，则设置当前对象的成员变量cachedAdaptiveClass=该类的clazz
         * 若该类有参数为type的构造函数, 则认为该类是type的包装类，则为成员变量cachedWrapperClasses集合添加该类的clazz
         * 若该类带@Activate注解, 则为当前对象的成员变量cachedActivates 添加值(key是类名 , value是clazz的@Activate注解对象)
         * 填充extensionClasses, key是记录中类的别名, value是该类的clazz对象
         */

        // 两个函数一对, 只是传入的type路径不一样 (这两个路径,只有一个路径下有文件, 如果都有的话, 文件内容如果有重复, 后面会抛出异常, 正常只有一个路径下有文件)
        // 注意: 这里根据type的类名可以唯一确定一个文件, 也就是这里只是读取一个文件的内容
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    /**
     * 将成员变量cachedDefaultName的值, 设置为type的注解@SPI的value值， 若value没有值，则不设置
     * (type可以是任意的类, 可以用type=ThreadPool来走一遍看下)
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }
    }

    // 按参数 dir+type来确定某一个文件, 并读取该文件内容
    // 加载文件中每条记录对应的类 ，并设置当前对象的成员变量的值 或者 为当前对象的成员变量添加值。
    // 注意: 这里根据type的类名可以唯一确定一个文件, 也就是这里只是读取一个文件的内容

    // 使用文件内容填充extensionClasses, 最终key是name, value是类对应的Class对象
    // 例如文件内容: "accesslog=com.alibaba.dubbo.rpc.filter.AccessLogFilter"
    // 则 key是accesslog, value是AccessLogFilter.class
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls;
            // 获取类加载器, 一般是app
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 读取resourceURL指定文件的内容 用于填充extensionClasses， 并给当前对象的成员变量赋值
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }


    // 读取resourceURL指定文件的内容 用于填充入参extensionClasses， 并给当前对象的成员变量赋值
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                // line内容示例 "accesslog=com.alibaba.dubbo.rpc.filter.AccessLogFilter"
                // 参考 https://www.cnblogs.com/kindevil-zx/p/5603643.html#spi
                String line;
                // 读取文件中的每一行记录，做处理
                while ((line = reader.readLine()) != null) {
                    // 这段作用是过滤掉#注释所在的行
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                // 类别名
                                name = line.substring(0, i).trim();
                                // 类的全路径（包名和类名）
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                // 处理由单条文件记录得到的clazz，进一步设置当前对象的成员变量值，并填充extensionClasses
                                // （extensionClasses中 key是name, value是这里Class.forName加载的类）
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 从参数clazz获取信息，为本类的一些成员变量设置或者添加值， 也为入参extensionClasses添加值。
     * （本函数就处理一个clazz， 外层在调用该函数时，会依次传入文件中的每条记录对应的clazz）
     *
     * 设置值的成员变量有: cachedAdaptiveClass
     *
     * 填充值的成员变量有：
     * cachedWrapperClasses，cachedActivates，cachedNames
     *
     * 填充入参extensionClasses, extensionClasses的内容 key是参数name, value是参数clazz
     *
     * @param extensionClasses 要填充的map
     * @param resourceURL 打log用的文件地址
     * @param clazz 根据文件中的单条记录, 加载的clazz
     * @param name 别名
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 参数clazz必须是type的子类, 不是则抛异常
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            // 若类上带@Adaptive注解, 则设置成员变量cachedAdaptiveClass为clazz, 只能有一个实现类带@Adaptive注解, 否则抛出异常
            cacheAdaptiveClass(clazz);
        } else if (isWrapperClass(clazz)) {
            // clazz是否是type的包装类, 若是则将clazz添加到set中
            cacheWrapperClass(clazz);
        } else {
            // 这个else判断clazz是否带@Activate注解, 带就填充成员变量cachedActivates

            clazz.getConstructor();// 多余的一句
            // 若参数name没有传值, 则取类名的前缀
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 若clazz上有@Activate注解，
                // 则填充成员变量cachedActivates, key是name, value是@Activate注解对象
                cacheActivateClass(clazz, names[0]);
                // 若有多个别名，只让一个有效
                for (String n : names) {
                    // 存入成员变量cachedNames (是个map), key是clazz, value是name
                    cacheName(clazz, n);
                    // 存入extensionClasses (map), key是name, value是clazz
                    saveInExtensionClass(extensionClasses, clazz, name);
                }
            }
        }
    }

    /**
     * cache name
     */
    // key是clazz， value是该clazz的别名
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name) {
        Class<?> c = extensionClasses.get(name);
        if (c == null) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName());
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    /**
     * 存入map, key是name (文件中的类别名), value是clazz的@Activate注解对象， 若没有@Activate注解，则不添加
     * @param clazz 由文件中类的全路径加载得到的clazz对象
     * @param name 文件中的类别名
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz) {
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getClass().getName()
                    + ", " + clazz.getClass().getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    // 判断clazz是否是type的包装类 (若clazz有参数为type的构造函数, 则认为clazz是type的包装类)
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    // 参数clazz上, 若有extention注解 则返回注解的value值,
    // 若没有则返回clazz类的名字的前缀 (这里的参数clazz是具体实现类的Class对象)
    // 举例： 入参clazz=SpiExtensionFactory.class， 入参上面没有@Extension注解， 则返回"spi" （type=ExtensionFactory.class）
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                // 取clazz类名的前缀
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    // 返回type接口下的Adapter类的对象
    private T createAdaptiveExtension() {
        try {
            // 先获取或者生成type接口对应的一个Adapter类, 再给该Adapter对象所含有的成员变量注入值
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    // 获取或者生成type的一个Adapter类 (若type接口有 @Adapter标注的实现类, 则返回该实现类; 若没有, 则会动态生成一个实现类并返回)
    private Class<?> getAdaptiveExtensionClass() {
        //  加载文件中的类，并给当前对象的成员变量赋值
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            // 当type=ExtensionFactory.class时,
            // 上面执行getExtensionClasses()就会给cachedAdaptiveClass赋值为AdaptiveExtensionFactory.class, 直接返回
            return cachedAdaptiveClass;
        }
        // 如果在执行getExtensionClasses() 没有给cachedAdaptiveClass赋值, 这里会使用动态字节码技术生成一个类给它赋值
        /**
         * 当type=ExtensionFactory.class时，不会执行到这里，因为type下有@Adaptive注解的类，所以不需要动态生成一个。
         * 当type!=ExtensionFactory.class时，
         *      若type下有@Adaptive注解的类，则不会执行到这里。
         *      若type下没有@Adaptive注解的类，则会执行这里动态生成一个类。
         * 其中的type就是当前对象的成员变量
         */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    private Class<?> createAdaptiveExtensionClass() {
        // 生成一个代理类的字符串
        // cachedDefaultName 源于 SPI 注解值，默认情况下，SPI 注解值为空串，此时 cachedDefaultName = null
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        ClassLoader classLoader = findClassLoader();
        // 执行ExtensionLoader.getExtensionLoader(Compiler.class), 就把成员变量type赋值为Compiler.class,
        // 再getAdaptiveExtension会获取一个Compile接口的实现类
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
