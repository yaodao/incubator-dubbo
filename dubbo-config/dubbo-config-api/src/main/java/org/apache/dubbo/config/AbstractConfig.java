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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.CompositeConfiguration;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.InmemoryConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ConsumerMethodModel;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility methods and public methods for parsing configuration
 *
 * @export
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;

    /**
     * The maximum length of a <b>parameter's value</b>
     */
    private static final int MAX_LENGTH = 200;

    /**
     * The maximum length of a <b>path</b>
     */
    private static final int MAX_PATH_LENGTH = 200;

    /**
     * The rule qualification for <b>name</b>
     */
    // 匹配 减号，句号，下划线，字母和数字 所组成的字符串， 例如可以匹配： "-aa_bb00"
    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    /**
     * The rule qualification for <b>multiply name</b>
     */
    // 匹配 逗号，减号，句号，下划线，字母和数字 所组成的字符串， 例如： "aabb,count.000"
    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    /**
     * The rule qualification for <b>method names</b>
     */
    // 匹配 单个字母 后面跟由字母或数字 组成的字符串， 例如： "getName2"
    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    /**
     * The rule qualification for <b>path</b>
     */
    // 匹配由 /，减号，$，句号，下划线，字母和数组 组成的字符串， 例如： "/aa$A.proxy/b-b/cc/dd"
    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    /**
     * The pattern matches a value who has a symbol
     */
    // 匹配由 冒号，星号，逗号，空格，减号，句号，字符和数字 组成的字符串， 例如： "aa,bb:aa_bb:00*bb:aa bb:aa-bb"
    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,\\s/\\-._0-9a-zA-Z]+");

    /**
     * The pattern matches a property key
     */
    // 匹配 星号，逗号，减号，句号，下划线，字母和数字 组成的字符串， 例如： "-aa_bb,00*bb"
    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");

    /**
     * The legacy properties container
     */
    private static final Map<String, String> legacyProperties = new HashMap<String, String>();

    /**
     * The suffix container
     */
    private static final String[] SUFFIXES = new String[]{"Config", "Bean"};

    static {
        legacyProperties.put("dubbo.protocol.name", "dubbo.service.protocol");
        legacyProperties.put("dubbo.protocol.host", "dubbo.service.server.host");
        legacyProperties.put("dubbo.protocol.port", "dubbo.service.server.port");
        legacyProperties.put("dubbo.protocol.threads", "dubbo.service.max.thread.pool.size");
        legacyProperties.put("dubbo.consumer.timeout", "dubbo.service.invoke.timeout");
        legacyProperties.put("dubbo.consumer.retries", "dubbo.service.max.retry.providers");
        legacyProperties.put("dubbo.consumer.check", "dubbo.service.allow.no.provider");
        legacyProperties.put("dubbo.service.url", "dubbo.service.address");

        // this is only for compatibility
        DubboShutdownHook.getDubboShutdownHook().register();
    }

    /**
     * The config id
     */
    protected String id;
    protected String prefix;

    private static String convertLegacyValue(String key, String value) {
        if (value != null && value.length() > 0) {
            if ("dubbo.service.max.retry.providers".equals(key)) {
                return String.valueOf(Integer.parseInt(value) - 1);
            } else if ("dubbo.service.allow.no.provider".equals(key)) {
                return String.valueOf(!Boolean.parseBoolean(value));
            }
        }
        return value;
    }

    // 举例: AbstractServiceConfig.class 返回  "abstract-service"
    // StringBuilder.class 返回  "string-builder"
    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) {
            if (tag.endsWith(suffix)) {
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        return StringUtils.camelToSplitName(tag, "-");
    }

    protected static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    @SuppressWarnings("unchecked")
    // 将参数config对象中的成员变量名字和值, 添加到参数parameters中
    // (其实就是通过get/is方法获取config对象中的属性值, 再把属性名和属性值放到parameters中, 期间要通过方法上的@Parameter注解对属性值做处理)
    // 参数parameters中, key是方法对应的属性名, value是该属性的值
    protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        // 参数config的所有方法
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 处理get/is函数, 且返回值是基础的类型
                if (ClassHelper.isGetter(method)) {
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // 方法的返回值为Object.class 或者 方法上Parameter注解中的excluded()为true 则跳过该方法
                    // 这就是注解加在get方法上, 但实际处理的是成员变量, 这里注解表示不用处理该成员变量
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                        continue;
                    }
                    // key是自定义值 或者属性名
                    String key;
                    // 注解的 key()有值, 则赋给key
                    if (parameter != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        // 取get方法名中的属性名
                        key = calculatePropertyFromGetter(name);
                    }
                    // 得到参数config对象调用method后的value值
                    Object value = method.invoke(config);
                    String str = String.valueOf(value).trim();
                    // 若属性值不空
                    if (value != null && str.length() > 0) {
                        // 方法上Parameter注解中的escaped()为true, 则encode一下str
                        if (parameter != null && parameter.escaped()) {
                            str = URL.encode(str);
                        }
                        // 方法上Parameter注解中的append()为true, 则在原来的值后面追加str
                        if (parameter != null && parameter.append()) {
                            // default.key 在map中是否有值
                            String pre = parameters.get(Constants.DEFAULT_KEY + "." + key);
                            if (pre != null && pre.length() > 0) {
                                // 给原来的value追加值str, 用逗号隔开
                                str = pre + "," + str;
                            }
                            // key 在map中是否有值
                            pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                // 给原来的value追加值str, 用逗号隔开
                                str = pre + "," + str;
                            }
                        }
                        // 若prefix有值, 需要给key加上前缀
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        // put
                        parameters.put(key, str);
                    } else if (parameter != null && parameter.required()) {
                        // 若调用method后的value值为空, 但方法上Parameter注解中的required()为true, 则抛出异常
                        // 这个就是属性值要求不能为空, 但你没有赋值, 就抛出异常
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                }// 单独处理方法名字是"getParameters", 返回值为map的情况
                else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    // method名字是"getParameters", public, 无参数, 返回值是Map类型
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        // 将map中entry的key 由 "ab-cd" 替换成 "ab.cd"形式, 再加上前缀prefix,  value值不变, 添加到parameters
                        // parameters的key是属性名, value是属性值
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static void appendAttributes(Map<String, Object> parameters, Object config) {
        appendAttributes(parameters, config, null);
    }

    protected static void appendAttributes(Map<String, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                Parameter parameter = method.getAnnotation(Parameter.class);
                if (parameter == null || !parameter.attribute()) {
                    continue;
                }
                String name = method.getName();
                if (ClassHelper.isGetter(method)) {
                    String key;
                    if (parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = calculateAttributeFromGetter(name);
                    }
                    Object value = method.invoke(config);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static ConsumerMethodModel.AsyncMethodInfo convertMethodConfig2AyncInfo(MethodConfig methodConfig) {
        if (methodConfig == null || (methodConfig.getOninvoke() == null && methodConfig.getOnreturn() == null && methodConfig.getOnthrow() == null)) {
            return null;
        }

        //check config conflict
        if (Boolean.FALSE.equals(methodConfig.isReturn()) && (methodConfig.getOnreturn() != null || methodConfig.getOnthrow() != null)) {
            throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been set.");
        }

        ConsumerMethodModel.AsyncMethodInfo asyncMethodInfo = new ConsumerMethodModel.AsyncMethodInfo();

        asyncMethodInfo.setOninvokeInstance(methodConfig.getOninvoke());
        asyncMethodInfo.setOnreturnInstance(methodConfig.getOnreturn());
        asyncMethodInfo.setOnthrowInstance(methodConfig.getOnthrow());

        try {
            String oninvokeMethod = methodConfig.getOninvokeMethod();
            if (StringUtils.isNotEmpty(oninvokeMethod)) {
                asyncMethodInfo.setOninvokeMethod(getMethodByName(methodConfig.getOninvoke().getClass(), oninvokeMethod));
            }

            String onreturnMethod = methodConfig.getOnreturnMethod();
            if (StringUtils.isNotEmpty(onreturnMethod)) {
                asyncMethodInfo.setOnreturnMethod(getMethodByName(methodConfig.getOnreturn().getClass(), onreturnMethod));
            }

            String onthrowMethod = methodConfig.getOnthrowMethod();
            if (StringUtils.isNotEmpty(onthrowMethod)) {
                asyncMethodInfo.setOnthrowMethod(getMethodByName(methodConfig.getOnthrow().getClass(), onthrowMethod));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }

        return asyncMethodInfo;
    }

    private static Method getMethodByName(Class<?> clazz, String methodName) {
        try {
            return ReflectUtils.findMethodByMethodName(clazz, methodName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    protected static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (StringUtils.isNotEmpty(value)
                && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    /**
     * Check whether there is a <code>Extension</code> who's name (property) is <code>value</code> (special treatment is
     * required)
     *
     * @param type     The Extension type
     * @param property The extension key
     * @param value    The Extension name
     */
    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (StringUtils.isNotEmpty(value)) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(Constants.REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (Constants.DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException("No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    protected static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    protected static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    protected static void checkName(String property, String value) {
        // 验证参数value的长度和内容是否合法, 不合法直接抛出异常
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    protected static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    protected static void checkParameterName(Map<String, String> parameters) {
        if (CollectionUtils.isEmptyMap(parameters)) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 验证参数value的长度和内容是否合法, 不合法直接抛出异常
     * @param property log用
     * @param value 要验证的值
     * @param maxlength 最大长度
     * @param pattern 内容的正则
     */
    protected static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (StringUtils.isEmpty(value)) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            // value值是否完全匹配
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" contains illegal " +
                        "character, only digit, letter, '-', '_' or '.' is legal.");
            }
        }
    }

    protected static Set<String> getSubProperties(Map<String, String> properties, String prefix) {
        return properties.keySet().stream().filter(k -> k.contains(prefix)).map(k -> {
            k = k.substring(prefix.length());
            return k.substring(0, k.indexOf("."));
        }).collect(Collectors.toSet());
    }

    // 根据setter方法, 取getter方法上@Parameter注解里的属性名
    // （getter方法上有@Parameter注解，可以为属性取别名，所以最后返回的属性名可以是实际的属性名，也可以是属性的别名, 取决于@Parameter注解里的配置）
    private static String extractPropertyName(Class<?> clazz, Method setter) throws Exception {
        String propertyName = setter.getName().substring("set".length());
        Method getter = null;
        try {
            getter = clazz.getMethod("get" + propertyName);
        } catch (NoSuchMethodException e) {
            getter = clazz.getMethod("is" + propertyName);
        }
        Parameter parameter = getter.getAnnotation(Parameter.class);
        if (parameter != null && StringUtils.isNotEmpty(parameter.key()) && parameter.useKeyAsProperty()) {
            propertyName = parameter.key();
        } else {
            propertyName = propertyName.substring(0, 1).toLowerCase() + propertyName.substring(1);
        }
        return propertyName;
    }

    // 取get方法名中的属性名
    // 举例: "getRandomPort" 返回 random.port
    private static String calculatePropertyFromGetter(String name) {
        int i = name.startsWith("get") ? 3 : 2;
        // "randomPort" -> "random.port"
        return StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
    }

    // 返回参数串 "get***/is***"后面的属性名
    // 举例: "getRandomPort" 返回 randomPort
    private static String calculateAttributeFromGetter(String getter) {
        int i = getter.startsWith("get") ? 3 : 2;
        return getter.substring(i, i + 1).toLowerCase() + getter.substring(i + 1);
    }

    @Parameter(excluded = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    // 若id为空, 则把value赋值给它
    public void updateIdIfAbsent(String value) {
        if (StringUtils.isNotEmpty(value) && StringUtils.isEmpty(id)) {
            this.id = value;
        }
    }

    /**
     * 取注解中的值, 设置本类对象的成员变量
     * @param annotationClass 注解类的clazz对象
     * @param annotation 注解类的对象（可以通过类似 Test.class.getAnnotation(TestStatus.class); 获取）
     */
    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class
                    && method.getReturnType() != void.class
                    && method.getParameterTypes().length == 0
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                // 不是Object类的方法 且 有返回值,没参数, 且 是public,非static 的方法
                try {
                    // 获取该method所代表的注解类成员的名字
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    // 得到一个方法名 "set***"
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);

                    // (认识到 Method对象 和 类的对象是两个独立的东西;
                    // Method对象可以直接由类的clazz取到, 而不要求必须是通过类的对象实例取到, 但这个取到的Method对象却可以作用于任意的该类的对象实例)
                    Object value = method.invoke(annotation);// 在annotation对象上,调用method方法, 得到值
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        // 根据method的返回值类型, 找到set函数
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, parameterType);
                            // 调用本类对象的set方法, 将value值设置进去
                            setterMethod.invoke(this, value);
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Should be called after Config was fully initialized.
     * // FIXME: this method should be completely replaced by appendParameters
     *
     * @return
     * @see AbstractConfig#appendParameters(Map, Object, String)
     * <p>
     * Notice! This method should include all properties in the returning map, treat @Parameter differently compared to appendParameters.
     */
    // 返回的map, key是方法对应的属性名, value是该属性的值 (处理的都是当前对象的成员变量的get方法)
    public Map<String, String> getMetaData() {
        Map<String, String> metaData = new HashMap<>();
        // 取clazz的所有public方法
        Method[] methods = this.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 处理返回值为基础类型的方法,  且是取成员变量的get/is方法
                if (isMetaMethod(method)) {
                    // 得到属性名
                    String prop = calculateAttributeFromGetter(name);
                    String key;
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // 若方法上Parameter注解中的key()有值 且 useKeyAsProperty()值为true, 则key赋值为key()
                    if (parameter != null && parameter.key().length() > 0 && parameter.useKeyAsProperty()) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }
                    // treat url and configuration differently, the value should always present in configuration though it may not need to present in url.
                    //if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                    if (method.getReturnType() == Object.class) {
                        metaData.put(key, null);
                        continue;
                    }
                    // 得到当前对象调用method后的value值, 并将key和value放入map
                    Object value = method.invoke(this);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        metaData.put(key, str);
                    } else {
                        metaData.put(key, null);
                    }
                }// 单独处理方法名字是"getParameters", 返回值为map的情况
                else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    // method名字是"getParameters", public, 无参数, 返回值是Map类型
                    Map<String, String> map = (Map<String, String>) method.invoke(this, new Object[0]);
                    if (map != null && map.size() > 0) {
//                            String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            // 将map中entry的key 由 "ab-cd" 替换成 "ab.cd"形式, value值不变, 添加到metaData
                            // metaData的key是属性名, value是属性值
                            metaData.put(entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        return metaData;
    }

    @Parameter(excluded = true)
    public String getPrefix() {
        // prefix不空则返回prefix, 否则返回"dubbo.service"
        return StringUtils.isNotEmpty(prefix) ? prefix : (Constants.DUBBO + "." + getTagName(this.getClass()));
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /**
     * TODO: Currently, only support overriding of properties explicitly defined in Config class, doesn't support
     * overriding of customized parameters stored in 'parameters'.
     */
    public void refresh() {
        try {
            CompositeConfiguration compositeConfiguration = Environment.getInstance().getConfiguration(getPrefix(), getId());
            InmemoryConfiguration config = new InmemoryConfiguration(getPrefix(), getId());
            config.addProperties(getMetaData());
            if (Environment.getInstance().isConfigCenterFirst()) {
                // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
                compositeConfiguration.addConfiguration(3, config);
            } else {
                // The sequence would be: SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
                compositeConfiguration.addConfiguration(1, config);
            }

            // loop methods, get override value and set the new value back to method
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                if (ClassHelper.isSetter(method)) {
                    try {
                        String value = StringUtils.trim(compositeConfiguration.getString(extractPropertyName(getClass(), method)));
                        // isTypeMatch() is called to avoid duplicate and incorrect update, for example, we have two 'setGeneric' methods in ReferenceConfig.
                        if (StringUtils.isNotEmpty(value) && ClassHelper.isTypeMatch(method.getParameterTypes()[0], value)) {
                            method.invoke(this, ClassHelper.convertPrimitive(method.getParameterTypes()[0], value));
                        }
                    } catch (NoSuchMethodException e) {
                        logger.info("Failed to override the property " + method.getName() + " in " +
                                this.getClass().getSimpleName() +
                                ", please make sure every property has getter/setter method provided.");
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to override ", e);
        }
    }

    @Override
    // 返回字符串 "<dubbo:service randomPort="8080" exported="true" />"
    public String toString() {
        try {
            // 举例: 当this=ServiceConfig对象时
            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            // "service"
            buf.append(getTagName(getClass()));
            // 处理当前类中的所有方法
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                try {
                    // 是否是get/is函数
                    if (ClassHelper.isGetter(method)) {
                        // "getRandomPort"
                        String name = method.getName();
                        // "randomPort"
                        String key = calculateAttributeFromGetter(name);
                        Object value = method.invoke(this);
                        // randomPort="8080"
                        if (value != null) {
                            buf.append(" ");
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(value);
                            buf.append("\"");
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

    /**
     * FIXME check @Parameter(required=true) and any conditions that need to match.
     */
    @Parameter(excluded = true)
    public boolean isValid() {
        return true;
    }

    // 参数method是否是取成员变量的函数 (get/is )
    private boolean isMetaMethod(Method method) {
        String name = method.getName();
        // method名字不以 get/is开头, 返回false
        if (!(name.startsWith("get") || name.startsWith("is"))) {
            return false;
        }
        // method名字只是"get", 返回false
        if ("get".equals(name)) {
            return false;
        }
        // method名字只是"getClass", 返回false
        if ("getClass".equals(name)) {
            return false;
        }
        // method不是public, 返回false
        if (!Modifier.isPublic(method.getModifiers())) {
            return false;
        }
        // method有参数, 返回false
        if (method.getParameterTypes().length != 0) {
            return false;
        }
        // method返回值不是基本类型, 返回false
        if (!ClassHelper.isPrimitive(method.getReturnType())) {
            return false;
        }
        return true;
    }

    @Override
    // 比较参数obj和this, 若get方法返回值相同, 则返回true
    // 其实比较的是两个同类的对象的成员变量值是否相同
    public boolean equals(Object obj) {
        // obj和this是同一个类的对象, 否则返回false
        if (obj == null || !(obj.getClass().getName().equals(this.getClass().getName()))) {
            return false;
        }

        // 取public方法
        Method[] methods = this.getClass().getMethods();
        for (Method method1 : methods) {
            // method1是get方法 且 返回类型是基础的类型
            // (只比较obj和this对象 在调用get方法后, 得到的值是否相同, 其实比较的是两个对象的成员变量值是否相同)
            if (ClassHelper.isGetter(method1) && ClassHelper.isPrimitive(method1.getReturnType())) {
                Parameter parameter = method1.getAnnotation(Parameter.class);
                // 若方法上的parameter注解的excluded()值为true, 则不进行比较
                if (parameter != null && parameter.excluded()) {
                    continue;
                }
                try {
                    // 对同一个方法, obj和this执行的结果是否相同, 若不同则返回false
                    Method method2 = obj.getClass().getMethod(method1.getName(), method1.getParameterTypes());
                    // 等同于调用 method1.invoke(this); 因为get方法没有参数
                    Object value1 = method1.invoke(this, new Object[]{});
                    Object value2 = method2.invoke(obj, new Object[]{});
                    if ((value1 != null && value2 != null) && !value1.equals(value2)) {
                        return false;
                    }
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return true;
    }
}
