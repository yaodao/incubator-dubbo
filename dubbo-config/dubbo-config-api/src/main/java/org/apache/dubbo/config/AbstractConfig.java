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
    // 暂时理解，标识当前config对象，
    // 例如： <dubbo:reference id="demoService" interface="com.unj.dubbotest.provider.DemoService" /> 引用服务
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

    // 举例:
    // AbstractServiceConfig.class 返回  "abstract-service"
    // StringBuilder.class 返回  "string-builder"
    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) { //  SUFFIXES {"Config", "Bean"};
            if (tag.endsWith(suffix)) {
                // 去掉后缀
                // "AbstractServiceConfig" 变成  "AbstractService"
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        // "AbstractService" 变成 "abstract-service"
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

    // 将参数config对象中的 get方法所对应的属性名和属性值 添加到参数parameters中
    // get方法是config对象中的所有带@Parameter注解的get方法,
    protected static void appendAttributes(Map<String, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        // 参数config对象的所有public方法
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                Parameter parameter = method.getAnnotation(Parameter.class);
                if (parameter == null || !parameter.attribute()) {
                    // 方法上不带@Parameter注解 或者 注解的attribute()值为false, 则直接跳过
                    continue;
                }
                String name = method.getName();
                // 是get方法
                if (ClassHelper.isGetter(method)) {
                    String key;
                    // 若@Parameter注解key()有值, 则key赋值为key();
                    if (parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = calculateAttributeFromGetter(name);
                    }
                    Object value = method.invoke(config);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            // 若参数prefix不空, 则key赋值为 prefix + "." + key
                            key = prefix + "." + key;
                        }
                        // 将属性名和属性值 添加到参数parameters中
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    // 由入参methodConfig对象生成一个AsyncMethodInfo对象
    protected static ConsumerMethodModel.AsyncMethodInfo convertMethodConfig2AyncInfo(MethodConfig methodConfig) {
        // 校验methodConfig的字段是否为空
        if (methodConfig == null || (methodConfig.getOninvoke() == null && methodConfig.getOnreturn() == null && methodConfig.getOnthrow() == null)) {
            return null;
        }

        //check config conflict
        if (Boolean.FALSE.equals(methodConfig.isReturn()) && (methodConfig.getOnreturn() != null || methodConfig.getOnthrow() != null)) {
            throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been set.");
        }

        // 生成AsyncMethodInfo对象，并设置其字段值
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

    // 根据方法签名 在clazz中取该签名对应的方法对象
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
    // 查看是否有value对应的clazz对象, value是以逗号分开多个名字的串, 没有会抛出异常
    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        // 检验value值是否合理
        checkMultiName(property, value);
        if (StringUtils.isNotEmpty(value)) {
            // 逗号分开
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(Constants.REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (Constants.DEFAULT_KEY.equals(v)) {
                    continue;
                }
                // 是否有v这个名字对应的clazz对象, 没有就抛出异常
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
        // 验证参数value的长度<=MAX_LENGTH 内容是否符合正则PATTERN_NAME_HAS_SYMBOL,
        // 不合法直接抛出异常
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        // 验证参数value的长度<=MAX_LENGTH 内容是否符合正则PATTERN_MULTI_NAME
        // 不合法直接抛出异常
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    // 检验map中的key和value是否满足条件, 不满足条件直接抛出异常
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
     * @param property 记录log用
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

    // 过滤得到参数map中 包含prefix的那些key
    // 将这些key去掉前缀prefix，之后放到set中返回
    protected static Set<String> getSubProperties(Map<String, String> properties, String prefix) {
        return properties.keySet().stream().filter(k -> k.contains(prefix)).map(k -> {
            k = k.substring(prefix.length());
            return k.substring(0, k.indexOf("."));
        }).collect(Collectors.toSet());
    }

    // 返回@Parameter注解的key()值 或者是入参setter方法的属性名
    // 若@Parameter注解中的key()有值且useKeyAsProperty()为true, 则返回key()值, 否则返回setter方法的属性名
    // （因为@Parameter注解是在getter方法上， 所以通过入参setter方法，找到对应的getter方法，再取getter方法上的@Parameter注解）
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
            // 若@Parameter注解中的key()有值且useKeyAsProperty()为true, 则返回key()值
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
     * 例如: appendAnnotation(Service.class, service); 其中Service是注解类
     * clazz.getMethods可以取到注解类中的每一项, 这个用法之前没见过, 包括api说明中也没有说clazz可以是注解类
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
                    // 获取该method所代表的注解类的成员的名字 (其实就是一个属性名)
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    // 得到一个方法名, 形如"set***"
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);

                    // (认识到 Method对象 和 类的实例对象是两个独立的东西;
                    // Method对象可以直接由类的clazz取到, 而不要求必须是通过类的对象实例取到, 但这个取到的Method对象却可以作用于任意的该类的对象实例上)

                    // 在annotation对象上,调用method方法, 得到值value. 若value值不是注解的默认值, 则将该value值设置到当前对象的属性中
                    Object value = method.invoke(annotation);
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        // 得到method对象的返回值类型
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            // 根据method的返回值类型, 找到本类的set函数 (因为是通过注解里的值来设置本类对象的属性, 所以注解的值的类型就是本类对象的set方法的参数类型)
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
    // 返回的map, key是当前对象的属性名, value是当前对象的属性值
    // (因为处理的都是当前对象的get/is方法，所以实际上是收集当前对象的属性信息）
    public Map<String, String> getMetaData() {
        // metaData的key是属性名, value是属性值 （是当前对象属性）
        Map<String, String> metaData = new HashMap<>();
        // 取当前对象的所有public方法
        Method[] methods = this.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 只处理以get/is开头 且 get/is的返回值是基本类型的方法
                if (isMetaMethod(method)) {
                    // 从方法名中得到属性名
                    String prop = calculateAttributeFromGetter(name);
                    String key;
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // 若method的@Parameter注解的key()有值 且 useKeyAsProperty()值为true, 则key赋值为key()
                    if (parameter != null && parameter.key().length() > 0 && parameter.useKeyAsProperty()) {
                        key = parameter.key();
                    } else {
                        // key赋值为属性名
                        key = prop;
                    }
                    // treat url and configuration differently, the value should always present in configuration though it may not need to present in url.
                    //if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                    if (method.getReturnType() == Object.class) {
                        // 若method的返回值是Object，则将（key，null）添加到map
                        metaData.put(key, null);
                        continue;
                    }
                    // 得到当前对象调用method后的value值, 并将key和value添加到map（key是属性名，value是属性值）
                    Object value = method.invoke(this);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        metaData.put(key, str);
                    } else {
                        // 若value转成的String为空，则将（key，null）添加到map
                        metaData.put(key, null);
                    }
                }// 单独处理方法名字是"getParameters" 且 返回值为map 且无参数 且是public 的情况
                else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    // 取当前对象的parameters成员变量值
                    Map<String, String> map = (Map<String, String>) method.invoke(this, new Object[0]);
                    if (map != null && map.size() > 0) {
//                            String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            // 将map中entry的key 由 "ab-cd" 替换成 "ab.cd"形式, value值不变, 添加到metaData
                            // 若key中无"-"，则原样放入map
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
    // prefix不空则返回prefix,
    // 否则返回"dubbo.***"，
    // 举例： 若this.getClass()=ServiceConfig.class， 则返回 "dubbo.service"
    public String getPrefix() {
        return StringUtils.isNotEmpty(prefix) ? prefix : (Constants.DUBBO + "." + getTagName(this.getClass()));
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /**
     * TODO: Currently, only support overriding of properties explicitly defined in Config class, doesn't support
     * overriding of customized parameters stored in 'parameters'.
     */
    // 使用从项目或环境变量中取到的配置值， 更新当前对象的属性值
    // todo compositeConfiguration这个局部变量的configList集合并没有在这里用到，为什么还需要往里添加元素？？冗余代码？？
    public void refresh() {
        try {
            // 使用Environment对象，生成并返回一个compositeConfiguration对象
            // 给compositeConfiguration对象的configList列表添加一个config对象
            CompositeConfiguration compositeConfiguration = Environment.getInstance().getConfiguration(getPrefix(), getId());
            InmemoryConfiguration config = new InmemoryConfiguration(getPrefix(), getId()); // getPrefix()返回 "dubbo.service"，若当前对象为ServiceConfig.class
            // 添加当前对象的成员变量的(名, 值)对 到InmemoryConfiguration对象的成员变量store中
            config.addProperties(getMetaData());
            if (Environment.getInstance().isConfigCenterFirst()) {
                // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
                // 上条注释中的AbstractConfig就是这里的config对象
                compositeConfiguration.addConfiguration(3, config);
            } else {
                // The sequence would be: SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
                compositeConfiguration.addConfiguration(1, config);
            }

            // loop methods, get override value and set the new value back to method
            // 使用项目或环境变量中的值，设置当前对象的属性值。
            // 通过当前对象中set方法获得属性名, 使用属性名从项目或环境变量中取它对应的配置值, 使用该配置值重新设置当前对象的属性值
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                // 只处理set方法
                if (ClassHelper.isSetter(method)) {
                    try {
                        // 取method的属性名 对应的配置值
                        String value = StringUtils.trim(compositeConfiguration.getString(extractPropertyName(getClass(), method)));
                        // isTypeMatch() is called to avoid duplicate and incorrect update, for example, we have two 'setGeneric' methods in ReferenceConfig.
                        // 若type类型和value值能对应上, 则设置当前对象的属性值（因为method是set方法，所以只有一个参数）
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

    // 入参method的名字以get/is开头 且 get/is的返回值是基本类型，则返回true
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
    // 比较参数obj和this, 若两个对象内部的get方法返回值相同, 则返回true
    // 本质上，比较的是两个同类的对象的成员变量值是否相同
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
