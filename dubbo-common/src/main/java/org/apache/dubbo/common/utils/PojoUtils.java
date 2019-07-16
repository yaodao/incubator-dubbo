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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * PojoUtils. Travel object deeply, and convert complex type to simple type.
 * <p/>
 * Simple type below will be remained:
 * <ul>
 * <li> Primitive Type, also include <b>String</b>, <b>Number</b>(Integer, Long), <b>Date</b>
 * <li> Array of Primitive Type
 * <li> Collection, eg: List, Map, Set etc.
 * </ul>
 * <p/>
 * Other type will be covert to a map which contains the attributes and value pair of object.
 */
public class PojoUtils {

    private static final Logger logger = LoggerFactory.getLogger(PojoUtils.class);
    private static final ConcurrentMap<String, Method> NAME_METHODS_CACHE = new ConcurrentHashMap<String, Method>();
    private static final ConcurrentMap<Class<?>, ConcurrentMap<String, Field>> CLASS_FIELD_CACHE = new ConcurrentHashMap<Class<?>, ConcurrentMap<String, Field>>();

    // 若objs的元素是自定义的对象, 则返回Map对象(其中entry对应obj对象的属性名-属性值)
    public static Object[] generalize(Object[] objs) {
        Object[] dests = new Object[objs.length];
        for (int i = 0; i < objs.length; i++) {
            dests[i] = generalize(objs[i]);
        }
        return dests;
    }

    // 将objs转换成type类型返回
    public static Object[] realize(Object[] objs, Class<?>[] types) {
        if (objs.length != types.length) {
            throw new IllegalArgumentException("args.length != types.length");
        }

        Object[] dests = new Object[objs.length];
        for (int i = 0; i < objs.length; i++) {
            dests[i] = realize(objs[i], types[i]);
        }

        return dests;
    }

    public static Object[] realize(Object[] objs, Class<?>[] types, Type[] gtypes) {
        if (objs.length != types.length || objs.length != gtypes.length) {
            throw new IllegalArgumentException("args.length != types.length");
        }
        Object[] dests = new Object[objs.length];
        for (int i = 0; i < objs.length; i++) {
            dests[i] = realize(objs[i], types[i], gtypes[i]);
        }
        return dests;
    }

    public static Object generalize(Object pojo) {
        return generalize(pojo, new IdentityHashMap<Object, Object>());
    }

    @SuppressWarnings("unchecked")
    /**
     * 当pojo是数组、集合、Map、自定义类时 返回值分别是 数组、集合、Map、pojo的属性和属性值组成的map
     * @param pojo
     * @param history key是pojo, value是解析pojo后的值. history类似于一个缓存
     * @return
     */
    private static Object generalize(Object pojo, Map<Object, Object> history) {
        if (pojo == null) {
            return null;
        }

        // 若是枚举类型, 则返回枚举变量字面值
        if (pojo instanceof Enum<?>) {
            // 返回枚举元素的字面值
            return ((Enum<?>) pojo).name();
        }
        // 若是枚举类型的数组, 返回字面值数组
        if (pojo.getClass().isArray() && Enum.class.isAssignableFrom(pojo.getClass().getComponentType())) {
            int len = Array.getLength(pojo);
            String[] values = new String[len];
            for (int i = 0; i < len; i++) {
                // 把枚举元素的字面值收集到values里
                values[i] = ((Enum<?>) Array.get(pojo, i)).name();
            }
            return values;
        }

        // 基本类型则直接返回
        if (ReflectUtils.isPrimitives(pojo.getClass())) {
            return pojo;
        }

        // 类类型则返回类全名
        if (pojo instanceof Class) {
            return ((Class) pojo).getName();
        }

        // 感觉能到这 pojo就是个自定义类的对象实例 或者 自定义类的对象的数组
        // 在history中有, 就直接返回
        Object o = history.get(pojo);
        if (o != null) {
            return o;
        }
        // key是pojo, value的最初值也设置成pojo
        history.put(pojo, pojo);

        // 若是数组, 返回对每个元素标准化后的数组dest, history中加入(pojo, dest)
        if (pojo.getClass().isArray()) {
            int len = Array.getLength(pojo);
            Object[] dest = new Object[len];
            // 重新设置history中pojo对应的value值
            history.put(pojo, dest);
            for (int i = 0; i < len; i++) {
                Object obj = Array.get(pojo, i);
                dest[i] = generalize(obj, history);
            }
            return dest;
        }
        // 若是list, 返回对每个元素标准化后的集合dest, history中加入(pojo, dest)
        if (pojo instanceof Collection<?>) {
            Collection<Object> src = (Collection<Object>) pojo;
            int len = src.size();
            Collection<Object> dest = (pojo instanceof List<?>) ? new ArrayList<Object>(len) : new HashSet<Object>(len);
            history.put(pojo, dest);
            for (Object obj : src) {
                dest.add(generalize(obj, history));
            }
            return dest;
        }
        // 若是map, 则把key和value都标准化后再放到map中,  history中加入(pojo, map)
        if (pojo instanceof Map<?, ?>) {
            Map<Object, Object> src = (Map<Object, Object>) pojo;
            // 获取一个map实例
            Map<Object, Object> dest = createMap(src);
            history.put(pojo, dest);
            for (Map.Entry<Object, Object> obj : src.entrySet()) {
                dest.put(generalize(obj.getKey(), history), generalize(obj.getValue(), history));
            }
            return dest;
        }

        // history中加入(pojo, map), 能到这 pojo只是个自定义类的对象, map中存放pojo的(属性名-属性值)

        // 通过方法来组成属性名和值的entry
        // map的key是pojo的属性名, value是pojo的属性值
        Map<String, Object> map = new HashMap<String, Object>();
        history.put(pojo, map);
        // 固定加入key="class", value是类全名
        map.put("class", pojo.getClass().getName());
        for (Method method : pojo.getClass().getMethods()) {
            // method是否是get/is方法
            if (ReflectUtils.isBeanPropertyReadMethod(method)) {
                try {
                    // key是get方法对应的属性名, value是标准化的pojo对应的属性值
                    map.put(ReflectUtils.getPropertyNameFromBeanReadMethod(method), generalize(method.invoke(pojo), history));
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
        }
        // 通过属性来组成属性名和值的entry
        // public field
        for (Field field : pojo.getClass().getFields()) {
            if (ReflectUtils.isPublicInstanceField(field)) {
                try {
                    Object fieldValue = field.get(pojo);
                    if (history.containsKey(pojo)) {
                        // 字段名已经在map中存在, 则直接到下次循环
                        Object pojoGeneralizedValue = history.get(pojo);
                        if (pojoGeneralizedValue instanceof Map
                                && ((Map) pojoGeneralizedValue).containsKey(field.getName())) {
                            continue;
                        }
                    }
                    if (fieldValue != null) {
                        // key是属性名(不带包名)
                        map.put(field.getName(), generalize(fieldValue, history));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
        }
        return map;
    }

    public static Object realize(Object pojo, Class<?> type) {
        return realize0(pojo, type, null, new IdentityHashMap<Object, Object>());
    }

    public static Object realize(Object pojo, Class<?> type, Type genericType) {
        return realize0(pojo, type, genericType, new IdentityHashMap<Object, Object>());
    }

    // 为Map做了一个代理类
    private static class PojoInvocationHandler implements InvocationHandler {

        private Map<Object, Object> map;

        public PojoInvocationHandler(Map<Object, Object> map) {
            this.map = map;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                // 如果方法是从Object继承的, 则在map对象上直接调用
                return method.invoke(map, args);
            }
            // 参数method的方法名
            String methodName = method.getName();
            Object value = null;
            // 查找该方法名对应的value值
            if (methodName.length() > 3 && methodName.startsWith("get")) {
                value = map.get(methodName.substring(3, 4).toLowerCase() + methodName.substring(4));
            } else if (methodName.length() > 2 && methodName.startsWith("is")) {
                value = map.get(methodName.substring(2, 3).toLowerCase() + methodName.substring(3));
            } else {
                value = map.get(methodName.substring(0, 1).toLowerCase() + methodName.substring(1));
            }
            // 把value转化成method对象的返回值
            if (value instanceof Map<?, ?> && !Map.class.isAssignableFrom(method.getReturnType())) {
                value = realize0((Map<String, Object>) value, method.getReturnType(), null, new IdentityHashMap<Object, Object>());
            }
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    // 返回type类型的集合实例
    private static Collection<Object> createCollection(Class<?> type, int len) {
        if (type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<Object>(len);
        }
        if (type.isAssignableFrom(HashSet.class)) {
            return new HashSet<Object>(len);
        }
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            try {
                // type不是接口和抽象类, 则反射生成一个实例
                return (Collection<Object>) type.newInstance();
            } catch (Exception e) {
                // ignore
            }
        }
        return new ArrayList<Object>();
    }

    // 通过判断src的具体类型, 从而创建一个该类型的map实例
    private static Map createMap(Map src) {
        Class<? extends Map> cl = src.getClass();
        Map result = null;
        if (HashMap.class == cl) {
            result = new HashMap();
        } else if (Hashtable.class == cl) {
            result = new Hashtable();
        } else if (IdentityHashMap.class == cl) {
            result = new IdentityHashMap();
        } else if (LinkedHashMap.class == cl) {
            result = new LinkedHashMap();
        } else if (Properties.class == cl) {
            result = new Properties();
        } else if (TreeMap.class == cl) {
            result = new TreeMap();
        } else if (WeakHashMap.class == cl) {
            return new WeakHashMap();
        } else if (ConcurrentHashMap.class == cl) {
            result = new ConcurrentHashMap();
        } else if (ConcurrentSkipListMap.class == cl) {
            result = new ConcurrentSkipListMap();
        } else {
            try {
                result = cl.newInstance();
            } catch (Exception e) { /* ignore */ }

            if (result == null) {
                try {
                    // 获取带参的构造函数
                    Constructor<?> constructor = cl.getConstructor(Map.class);
                    result = (Map) constructor.newInstance(Collections.EMPTY_MAP);
                } catch (Exception e) { /* ignore */ }
            }
        }

        if (result == null) {
            result = new HashMap<Object, Object>();
        }

        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * 感觉这个函数就是将pojo转换成type类型
     *
     * @param pojo 是个对象, type是类型 (不一定是pojo的类型)
     * @param type 类型 (不一定是pojo的类型)
     * @param genericType 是代表type类的ParameterizedType对象, 可以从这个ParameterizedType对象中得到type类的泛型信息
     *                    之所有要有这个参数, 因为类型中可能有多个泛型, 例如 Map<T, U>, 这种的用一个参数表示不了, 所以要有这个genericType参数
     * @param history history的key是pojo, value是将pojo转换成type类型后的值, 算是缓存
     * @return
     */
    private static Object realize0(Object pojo, Class<?> type, Type genericType, final Map<Object, Object> history) {
        if (pojo == null) {
            return null;
        }

        // type是枚举类型, 返回pojo字符串对应的枚举元素
        if (type != null && type.isEnum() && pojo.getClass() == String.class) {
            return Enum.valueOf((Class<Enum>) type, (String) pojo);
        }

        // 当pojo是基本类型的对象时(String数组也返回true),
        // 若pojo是String数组, 且type是枚举类型数组, 跳出if往下执行, 否则直接将pojo转成type类型返回. 这意思是枚举类型的数组在后面代码里进行处理
        // 也就是如果pojo是java自带的类型, 那在这里就转成type类型返回了(除了枚举、自定义类)
        if (ReflectUtils.isPrimitives(pojo.getClass())
                && !(type != null && type.isArray()
                && type.getComponentType().isEnum()
                && pojo.getClass() == String[].class)) {
            return CompatibleTypeUtils.compatibleTypeConvert(pojo, type);
        }

        Object o = history.get(pojo);

        if (o != null) {
            return o;
        }
        // history中不存在该pojo, 则存入, value默认值是pojo本身
        history.put(pojo, pojo);

        // pojo是数组
        if (pojo.getClass().isArray()) {
            if (Collection.class.isAssignableFrom(type)) {
                // type是集合类的子类, 则用pojo的元素填充集合dest, (pojo,dest)入history
                Class<?> ctype = pojo.getClass().getComponentType();
                int len = Array.getLength(pojo);
                Collection dest = createCollection(type, len);
                // key是pojo, value是集合dest
                history.put(pojo, dest);
                for (int i = 0; i < len; i++) {
                    Object obj = Array.get(pojo, i);
                    Object value = realize0(obj, ctype, null, history);
                    dest.add(value);
                }
                return dest;
            } else {
                // type是数组类型, 则用pojo的元素填充数组dest, (pojo,dest)入history
                Class<?> ctype = (type != null && type.isArray() ? type.getComponentType() : pojo.getClass().getComponentType());
                int len = Array.getLength(pojo);
                Object dest = Array.newInstance(ctype, len);
                // key是pojo, value是数组dest
                history.put(pojo, dest);
                for (int i = 0; i < len; i++) {
                    Object obj = Array.get(pojo, i);
                    Object value = realize0(obj, ctype, null, history);
                    Array.set(dest, i, value);
                }
                return dest;
            }
        }

        // pojo是集合
        if (pojo instanceof Collection<?>) {
            if (type.isArray()) {
                // type是数组类型, 则用pojo的元素填充数组dest, (pojo,dest)入history
                Class<?> ctype = type.getComponentType();
                Collection<Object> src = (Collection<Object>) pojo;
                int len = src.size();
                Object dest = Array.newInstance(ctype, len);
                history.put(pojo, dest);
                int i = 0;
                for (Object obj : src) {
                    Object value = realize0(obj, ctype, null, history);
                    Array.set(dest, i, value);
                    i++;
                }
                return dest;
            } else {
                // type是集合类型, 则用pojo的元素填充集合dest, (pojo,dest)入history
                Collection<Object> src = (Collection<Object>) pojo;
                int len = src.size();
                Collection<Object> dest = createCollection(type, len);
                history.put(pojo, dest);
                for (Object obj : src) {
                    // 当type是集合类型的时候, 需要知道集合类型的泛型参数具体是什么类型, 这里的作用就是获取集合类中泛型的具体类型
                    Type keyType = getGenericClassByIndex(genericType, 0);
                    Class<?> keyClazz = obj.getClass();
                    if (keyType instanceof Class) {
                        keyClazz = (Class<?>) keyType;
                    }
                    Object value = realize0(obj, keyClazz, keyType, history);
                    dest.add(value);
                }
                return dest;
            }
        }

        // pojo是map, 需要再根据type的类型来对pojo进行转化
        if (pojo instanceof Map<?, ?> && type != null) {
            // 若pojo里面有key="class" , 则使用它对应的value值来解析pojo里面key="name"对应的值
            // map里有key="class" 的entry
            Object className = ((Map<Object, Object>) pojo).get("class");
            if (className instanceof String) {
                try {
                    // 加载className对应的类类型对象
                    type = ClassHelper.forName((String) className);
                } catch (ClassNotFoundException e) {
                    // ignore
                }
            }

            // special logic for enum
            if (type.isEnum()) {
                // 若type是枚举类型, map里有key="name" 的entry
                Object name = ((Map<Object, Object>) pojo).get("name");
                if (name != null) {
                    return Enum.valueOf((Class<Enum>) type, name.toString());
                }
            }

            // 将pojo中的entry 填充到type类型的map中(移除key="class"这项)
            Map<Object, Object> map;
            // when return type is not the subclass of return type from the signature and not an interface
            if (!type.isInterface() && !type.isAssignableFrom(pojo.getClass())) {
                // type不是接口 且 不是pojo的父类(其实就是type和pojo是两种不同类型的map), 则new一个type对象, 将pojo中的entry都填到type中
                try {
                    map = (Map<Object, Object>) type.newInstance();
                    Map<Object, Object> mapPojo = (Map<Object, Object>) pojo;
                    map.putAll(mapPojo);
                    map.remove("class");
                } catch (Exception e) {
                    //ignore error
                    map = (Map<Object, Object>) pojo;
                }
            } else {
                map = (Map<Object, Object>) pojo;
            }

            // type是Map的子类
            // 将type类型map中的entry, 转化并填充到result中, 将(pojo, result)放到history
            if (Map.class.isAssignableFrom(type) || type == Object.class) {
                final Map<Object, Object> result = createMap(map);
                history.put(pojo, result);
                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                    // 若参数genericType带有泛型信息, 则通过它获取map的key的泛型的具体类型 (这里将genericType直接看做代表Map类的ParameterizedType对象)
                    Type keyType = getGenericClassByIndex(genericType, 0);
                    // 若参数genericType带有泛型信息, 则通过它获取map的value的泛型的具体类型
                    Type valueType = getGenericClassByIndex(genericType, 1);
                    // key的具体类型
                    Class<?> keyClazz;
                    if (keyType instanceof Class) {
                        keyClazz = (Class<?>) keyType;
                    } else if (keyType instanceof ParameterizedType) {
                        keyClazz = (Class<?>) ((ParameterizedType) keyType).getRawType();
                    } else {
                        // 若没传参数genericType, 就使用key自己的类型
                        keyClazz = entry.getKey() == null ? null : entry.getKey().getClass();
                    }
                    // value的具体类型
                    Class<?> valueClazz;
                    if (valueType instanceof Class) {
                        valueClazz = (Class<?>) valueType;
                    } else if (valueType instanceof ParameterizedType) {
                        valueClazz = (Class<?>) ((ParameterizedType) valueType).getRawType();
                    } else {
                        valueClazz = entry.getValue() == null ? null : entry.getValue().getClass();
                    }

                    // 按keyClazz解析entry.getKey()
                    Object key = keyClazz == null ? entry.getKey() : realize0(entry.getKey(), keyClazz, keyType, history);
                    // 按valueClazz解析entry.getValue()
                    Object value = valueClazz == null ? entry.getValue() : realize0(entry.getValue(), valueClazz, valueType, history);
                    result.put(key, value);
                }
                return result;
            } else if (type.isInterface()) {
                Object dest = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[]{type}, new PojoInvocationHandler(map));
                history.put(pojo, dest);
                return dest;
            } else {
                // type是个自定义的类型, 这里使用map中的entry设置dest的字段值 (dest就是type类型对象),  (pojo, desc)放到history
                Object dest = newInstance(type);
                history.put(pojo, dest);
                // 遍历pojo中的entry
                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (key instanceof String) {
                        String name = (String) key;
                        Object value = entry.getValue();
                        if (value != null) {
                            Method method = getSetterMethod(dest.getClass(), name, value.getClass());
                            Field field = getField(dest.getClass(), name);
                            if (method != null) {
                                if (!method.isAccessible()) {
                                    method.setAccessible(true);
                                }
                                Type ptype = method.getGenericParameterTypes()[0];
                                value = realize0(value, method.getParameterTypes()[0], ptype, history);
                                try {
                                    method.invoke(dest, value);
                                } catch (Exception e) {
                                    String exceptionDescription = "Failed to set pojo " + dest.getClass().getSimpleName() + " property " + name
                                            + " value " + value + "(" + value.getClass() + "), cause: " + e.getMessage();
                                    logger.error(exceptionDescription, e);
                                    throw new RuntimeException(exceptionDescription, e);
                                }
                            } else if (field != null) {
                                value = realize0(value, field.getType(), field.getGenericType(), history);
                                try {
                                    field.set(dest, value);
                                } catch (IllegalAccessException e) {
                                    throw new RuntimeException("Failed to set field " + name + " of pojo " + dest.getClass().getName() + " : " + e.getMessage(), e);
                                }
                            }
                        }
                    }
                }
                // 由于上面在使用newInstance(type); 来new一个type类型对象, 如果有错, 会返回一个Throwable给dest对象
                if (dest instanceof Throwable) {
                    // dest是Throwable类型, 则设置dest的detailMessage成员变量值
                    Object message = map.get("message");
                    if (message instanceof String) {
                        try {
                            Field field = Throwable.class.getDeclaredField("detailMessage");
                            if (!field.isAccessible()) {
                                field.setAccessible(true);
                            }
                            field.set(dest, message);
                        } catch (Exception e) {
                        }
                    }
                }
                return dest;
            }
        }
        return pojo;
    }

    /**
     * Get parameterized type
     *
     * @param genericType generic type
     * @param index       index of the target parameterized type
     * @return Return Person.class for List<Person>, return Person.class for Map<String, Person> when index=0
     */
    // 获取泛型的具体类型, 参数genericType是ParameterizedType类型, index是第几个泛型类型
    // 例如 Map<String, Integer> map; 第一个泛型类型是String, 第二个泛型类型是Integer
    private static Type getGenericClassByIndex(Type genericType, int index) {
        Type clazz = null;
        // find parameterized type
        if (genericType instanceof ParameterizedType) {
            ParameterizedType t = (ParameterizedType) genericType;
            Type[] types = t.getActualTypeArguments();
            clazz = types[index];
        }
        return clazz;
    }

    // 构造一个cls类型的对象, 并返回
    private static Object newInstance(Class<?> cls) {
        try {
            return cls.newInstance();
        } catch (Throwable t) {
            try {
                Constructor<?>[] constructors = cls.getDeclaredConstructors();
                /**
                 * From Javadoc java.lang.Class#getDeclaredConstructors
                 * This method returns an array of Constructor objects reflecting all the constructors
                 * declared by the class represented by this Class object.
                 * This method returns an array of length 0,
                 * if this Class object represents an interface, a primitive type, an array class, or void.
                 */
                if (constructors.length == 0) {
                    throw new RuntimeException("Illegal constructor: " + cls.getName());
                }
                Constructor<?> constructor = constructors[0];
                // 找一个参数最少的构造函数
                if (constructor.getParameterTypes().length > 0) {
                    for (Constructor<?> c : constructors) {
                        if (c.getParameterTypes().length < constructor.getParameterTypes().length) {
                            constructor = c;
                            if (constructor.getParameterTypes().length == 0) {
                                break;
                            }
                        }
                    }
                }
                constructor.setAccessible(true);
                // 构造对象
                return constructor.newInstance(new Object[constructor.getParameterTypes().length]);
            } catch (InstantiationException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    // 函数作用是获取set方法 (参数属性名对应的set方法), 并放入缓存
    // 举例: 参数cls=Student.class, property="name", valueCls是参数类型
    private static Method getSetterMethod(Class<?> cls, String property, Class<?> valueCls) {
        // set方法名
        String name = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
        // key类似 "com.its.test.Student.setName(java.lang.String)"  即 类名.方法名(参数类型) , value是key这个方法名对应的方法对象
        Method method = NAME_METHODS_CACHE.get(cls.getName() + "." + name + "(" + valueCls.getName() + ")");
        if (method == null) {
            try {
                method = cls.getMethod(name, valueCls);
            } catch (NoSuchMethodException e) {
                for (Method m : cls.getMethods()) {
                    if (ReflectUtils.isBeanPropertyWriteMethod(m) && m.getName().equals(name)) {
                        method = m;
                    }
                }
            }
            if (method != null) {
                // key是"com.its.test.Student.setName(java.lang.String)" , value是key这个方法名对应的方法对象
                NAME_METHODS_CACHE.put(cls.getName() + "." + name + "(" + valueCls.getName() + ")", method);
            }
        }
        return method;
    }
    // 函数作用是根据字段名获取Field对象, 并放入缓存
    private static Field getField(Class<?> cls, String fieldName) {
        Field result = null;
        if (CLASS_FIELD_CACHE.containsKey(cls) && CLASS_FIELD_CACHE.get(cls).containsKey(fieldName)) {
            return CLASS_FIELD_CACHE.get(cls).get(fieldName);
        }
        try {
            result = cls.getDeclaredField(fieldName);
            result.setAccessible(true);
        } catch (NoSuchFieldException e) {
            for (Field field : cls.getFields()) {
                if (fieldName.equals(field.getName()) && ReflectUtils.isPublicInstanceField(field)) {
                    result = field;
                    break;
                }
            }
        }
        if (result != null) {
            ConcurrentMap<String, Field> fields = CLASS_FIELD_CACHE.get(cls);
            if (fields == null) {
                fields = new ConcurrentHashMap<String, Field>();
                CLASS_FIELD_CACHE.putIfAbsent(cls, fields);
            }
            fields = CLASS_FIELD_CACHE.get(cls);
            fields.putIfAbsent(fieldName, result);
        }
        return result;
    }

    // 从这个函数可以看出, 若cls不是java自带的类型, 才是Pojo
    public static boolean isPojo(Class<?> cls) {
        return !ReflectUtils.isPrimitives(cls)
                && !Collection.class.isAssignableFrom(cls)
                && !Map.class.isAssignableFrom(cls);
    }

}
