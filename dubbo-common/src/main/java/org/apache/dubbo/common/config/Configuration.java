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
package org.apache.dubbo.common.config;

/**
 * Configuration interface, to fetch the value for the specified key.
 */
public interface Configuration {
    /**
     * Get a string associated with the given configuration key.
     *
     * @param key The configuration key.
     * @return The associated string.
     */
    // 返回key对应的配置值（从项目或环境中取key对应的配置值）
    default String getString(String key) {
        // 取key对应的配置值, 并将该配置值转成String类型返回
        return convert(String.class, key, null);
    }

    /**
     * Get a string associated with the given configuration key.
     * If the key doesn't map to an existing object, the default value
     * is returned.
     *
     * @param key          The configuration key.
     * @param defaultValue The default value.
     * @return The associated string if key is found and has valid
     * format, default value otherwise.
     */
    default String getString(String key, String defaultValue) {
        return convert(String.class, key, defaultValue);
    }

    /**
     * Gets a property from the configuration. This is the most basic get
     * method for retrieving values of properties. In a typical implementation
     * of the {@code Configuration} interface the other get methods (that
     * return specific data types) will internally make use of this method. On
     * this level variable substitution is not yet performed. The returned
     * object is an internal representation of the property value for the passed
     * in key. It is owned by the {@code Configuration} object. So a caller
     * should not modify this object. It cannot be guaranteed that this object
     * will stay constant over time (i.e. further update operations on the
     * configuration may change its internal state).
     *
     * @param key property to retrieve
     * @return the value to which this configuration maps the specified key, or
     * null if the configuration contains no mapping for this key.
     */
    // 从项目或环境中取key对应的值, 若没有则返回默认值defaultValue
    default Object getProperty(String key) {
        return getProperty(key, null);
    }

    /**
     * Gets a property from the configuration. The default value will return if the configuration doesn't contain
     * the mapping for the specified key.
     *
     * @param key property to retrieve
     * @param defaultValue default value
     * @return the value to which this configuration maps the specified key, or default value if the configuration
     * contains no mapping for this key.
     */
    // 从项目或环境中取key对应的值, 若没有则返回默认值defaultValue
    default Object getProperty(String key, Object defaultValue) {
        Object value = getInternalProperty(key);
        return value != null ? value : defaultValue;
    }

    // 从项目内取key对应的值, 这个项目内可能是从配置文件中取, 可能是从环境中取, 例如: System.getProperty(key);
    Object getInternalProperty(String key);

    /**
     * Check if the configuration contains the specified key.
     *
     * @param key the key whose presence in this configuration is to be tested
     * @return {@code true} if the configuration contains a value for this
     * key, {@code false} otherwise
     */
    // 查看项目或环境中是否有key对应的配置值, 没有返回false
    default boolean containsKey(String key) {
        return getProperty(key) != null;
    }


    /**
     * 取key对应的配置值, 并将该配置值转成cls类型的值返回
     *
     * 例如：
     *  convert(String.class, key, null);
     *  取key对应的配置值, 并将取到的配置值转成String类型返回
     *
     *
     * @param cls 目标类型
     * @param key 配置项的key
     * @param defaultValue 默认的配置值
     * @param <T>
     * @return
     */
    default <T> T convert(Class<T> cls, String key, T defaultValue) {
        // we only process String properties for now
        // 从项目或环境中取key对应的配置值 (这一句代码是本方法的重点, 后面的代码都是对这个返回值的处理)
        String value = (String) getProperty(key);

        // 后面的代码都是对这个value返回值的处理
        if (value == null) {
            return defaultValue;
        }

        Object obj = value;
        // 判断cls是不是value的父类或者接口或者相同的类
        if (cls.isInstance(value)) {
            // 把value转为cls类型对象
            return cls.cast(value);
        }

        if (String.class.equals(cls)) {
            // 若参数cls是String类型, 则把value转成String类型返回
            return cls.cast(value);
        }

        // 若参数cls是Boolean类型, 则把value转成Boolean类型返回
        if (Boolean.class.equals(cls) || Boolean.TYPE.equals(cls)) {
            obj = Boolean.valueOf(value);
        } // 若参数cls是Number类型的子类 或者cls是基本类型, 则将value转成对应的基本类型值返回
        else if (Number.class.isAssignableFrom(cls) || cls.isPrimitive()) {
            if (Integer.class.equals(cls) || Integer.TYPE.equals(cls)) {
                obj = Integer.valueOf(value);
            } else if (Long.class.equals(cls) || Long.TYPE.equals(cls)) {
                obj = Long.valueOf(value);
            } else if (Byte.class.equals(cls) || Byte.TYPE.equals(cls)) {
                obj = Byte.valueOf(value);
            } else if (Short.class.equals(cls) || Short.TYPE.equals(cls)) {
                obj = Short.valueOf(value);
            } else if (Float.class.equals(cls) || Float.TYPE.equals(cls)) {
                obj = Float.valueOf(value);
            } else if (Double.class.equals(cls) || Double.TYPE.equals(cls)) {
                obj = Double.valueOf(value);
            }
        }// 若cls是枚举类型
        else if (cls.isEnum()) {
            // 则将value转成枚举类型返回
            obj = Enum.valueOf(cls.asSubclass(Enum.class), value);
        }

        return cls.cast(obj);
    }


}
