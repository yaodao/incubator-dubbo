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
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Wrapper.
 */
public abstract class Wrapper {
    // key是一个clazz， value是该clazz的包装类对象
    private static final Map<Class<?>, Wrapper> WRAPPER_MAP = new ConcurrentHashMap<Class<?>, Wrapper>(); //class wrapper map
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String[] OBJECT_METHODS = new String[]{"getClass", "hashCode", "toString", "equals"};
    // 这个OBJECT_WRAPPER就是一个Object类的包装对象，只能调用Object类的几个方法
    private static final Wrapper OBJECT_WRAPPER = new Wrapper() {
        @Override
        public String[] getMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getDeclaredMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getPropertyNames() {
            return EMPTY_STRING_ARRAY;
        }

        @Override
        public Class<?> getPropertyType(String pn) {
            return null;
        }

        @Override
        public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public boolean hasProperty(String name) {
            return false;
        }


        @Override
        /**
         * 根据入参mn来调用instance的相应方法
         *
         *
         * @param instance instance. 实际使用对象（被包装的对象）
         * @param mn       method name. 方法名字符串
         * @param types
         * @param args     argument array. 被调用的方法的参数
         * @return
         * @throws NoSuchMethodException
         */
        public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException {
            if ("getClass".equals(mn)) {
                return instance.getClass();
            }
            if ("hashCode".equals(mn)) {
                return instance.hashCode();
            }
            if ("toString".equals(mn)) {
                return instance.toString();
            }
            if ("equals".equals(mn)) {
                if (args.length == 1) {
                    return instance.equals(args[0]);
                }
                throw new IllegalArgumentException("Invoke method [" + mn + "] argument number error.");
            }
            throw new NoSuchMethodException("Method [" + mn + "] not found.");
        }
    };
    private static AtomicLong WRAPPER_CLASS_COUNTER = new AtomicLong(0);

    /**
     * get wrapper.
     * 返回入参c的包装类，没有则创建一个包装类，再返回该包装类
     *
     * @param c Class instance.
     * @return Wrapper instance(not null).
     */
    public static Wrapper getWrapper(Class<?> c) {
        // 忽略动态生成的类
        while (ClassGenerator.isDynamicClass(c)) // can not wrapper on dynamic class.
        {
            c = c.getSuperclass();
        }

        if (c == Object.class) {
            return OBJECT_WRAPPER;
        }

        Wrapper ret = WRAPPER_MAP.get(c);
        if (ret == null) {
            ret = makeWrapper(c);
            WRAPPER_MAP.put(c, ret);
        }
        return ret;
    }

    // 创建入参c的一个包装类，并返回该包装类
    private static Wrapper makeWrapper(Class<?> c) {
        if (c.isPrimitive()) {
            // 基本类型没有包装类
            throw new IllegalArgumentException("Can not create wrapper for primitive type: " + c);
        }

        String name = c.getName();
        ClassLoader cl = ClassHelper.getClassLoader(c);


        /**
         * c1最终串 举例：
         *
         *    public void setPropertyValue(Object o, String n, Object v) {
         *         org.apache.dubbo.common.utils.Stu w;
         *         try {
         *             w = ((org.apache.dubbo.common.utils.Stu) $1);
         *         } catch (Throwable e) {
         *             throw new IllegalArgumentException(e);
         *         }
         *         if ($2.equals("rich")) {
         *             w.setRich(((Boolean) $3).booleanValue());
         *             return;
         *         }
         *         if ($2.equals("stuName")) {
         *             w.setStuName((java.lang.String) $3);
         *             return;
         *         }
         *         if ($2.equals("age")) {
         *             w.setAge((java.lang.Integer) $3);
         *             return;
         *         }
         *         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.common.utils.Stu.");
         *     }
         *
         */

        /**
         *  c2最终串 举例：
         *
         *    public Object getPropertyValue(Object o, String n) {
         *         org.apache.dubbo.common.utils.Stu w;
         *         try {
         *             w = ((org.apache.dubbo.common.utils.Stu) $1);
         *         } catch (Throwable e) {
         *             throw new IllegalArgumentException(e);
         *         }
         *         if ($2.equals("rich")) {
         *             return ($w) w.isRich();
         *         }
         *         if ($2.equals("stuName")) {
         *             return ($w) w.getStuName();
         *         }
         *         if ($2.equals("age")) {
         *             return ($w) w.getAge();
         *         }
         *         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.common.utils.Stu.");
         *     }
         */

        /**
         * c3最终串 举例：
         *
         *   public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException {
         *         org.apache.dubbo.common.utils.Stu w;
         *         try {
         *             w = ((org.apache.dubbo.common.utils.Stu) $1);
         *         } catch (Throwable e) {
         *             throw new IllegalArgumentException(e);
         *         }
         *         try {
         *             if ("isRich".equals($2) && $3.length == 0) {
         *                 return ($w) w.isRich();
         *             }
         *             if ("show".equals($2) && $3.length == 1 && $3[0].getName().equals("int")) {
         *                 w.show(((Number) $4[0]).intValue());
         *                 return null;
         *             }
         *             if ("show".equals($2) && $3.length == 0) {
         *                 w.show();
         *                 return null;
         *             }
         *             if ("setRich".equals($2) && $3.length == 1) {
         *                 w.setRich(((Boolean) $4[0]).booleanValue());
         *                 return null;
         *             }
         *             if ("getStuName".equals($2) && $3.length == 0) {
         *                 return ($w) w.getStuName();
         *             }
         *             if ("setStuName".equals($2) && $3.length == 1) {
         *                 w.setStuName((java.lang.String) $4[0]);
         *                 return null;
         *             }
         *             if ("getAge".equals($2) && $3.length == 0) {
         *                 return ($w) w.getAge();
         *             }
         *             if ("setAge".equals($2) && $3.length == 1) {
         *                 w.setAge((java.lang.Integer) $4[0]);
         *                 return null;
         *             }
         *         } catch (Throwable e) {
         *             throw new java.lang.reflect.InvocationTargetException(e);
         *         }
         *         throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \"" + $2 + "\" in class org.apache.dubbo.common.utils.Stu.");
         *     }
         *
         */


        StringBuilder c1 = new StringBuilder("public void setPropertyValue(Object o, String n, Object v){ ");
        StringBuilder c2 = new StringBuilder("public Object getPropertyValue(Object o, String n){ ");
        StringBuilder c3 = new StringBuilder("public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws " + InvocationTargetException.class.getName() + "{ ");

        c1.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        c2.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        c3.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");

        // key是属性名，vlaue是属性的clazz
        Map<String, Class<?>> pts = new HashMap<>(); // <property name, property types>
        // key是方法签名，value是方法对象
        Map<String, Method> ms = new LinkedHashMap<>(); // <method desc, Method instance>
        // c中方法名集合
        List<String> mns = new ArrayList<>(); // method names.
        // c中声明方法的名字
        List<String> dmns = new ArrayList<>(); // declaring method names.

        // get all public field.
        // 若c有public的成员变量，这里才会给c1和c2添加字符串，且将public成员变量添加到pts中
        for (Field f : c.getFields()) {
            String fn = f.getName();
            Class<?> ft = f.getType();
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                // 跳过static和transient修饰的成员变量
                continue;
            }
            // c1在这里增加的串是 if( $2.equals("stuName") ){ w.stuName=(java.lang.String)$3; return; }
            c1.append(" if( $2.equals(\"").append(fn).append("\") ){ w.").append(fn).append("=").append(arg(ft, "$3")).append("; return; }");
            c2.append(" if( $2.equals(\"").append(fn).append("\") ){ return ($w)w.").append(fn).append("; }");
            // (字段名，字段类型) 新增到pts
            pts.put(fn, ft);
        }

        Method[] methods = c.getMethods();
        // get all public method.
        boolean hasMethod = hasMethods(methods);
        if (hasMethod) {
            // 有自定义的方法
            c3.append(" try{");
            for (Method m : methods) {
                //ignore Object's method.
                if (m.getDeclaringClass() == Object.class) {
                    // 忽略Object类的方法
                    continue;
                }

                String mn = m.getName();
                c3.append(" if( \"").append(mn).append("\".equals( $2 ) ");
                int len = m.getParameterTypes().length;
                c3.append(" && ").append(" $3.length == ").append(len);

                // 可以处理方法重载的情况 （方法名相同，但参数不同）
                boolean override = false;
                for (Method m2 : methods) {
                    if (m != m2 && m.getName().equals(m2.getName())) {
                        override = true;
                        break;
                    }
                }
                if (override) {
                    if (len > 0) {
                        for (int l = 0; l < len; l++) {
                            // 新增串为 &&  $3[0].getName().equals("int") )
                            c3.append(" && ").append(" $3[").append(l).append("].getName().equals(\"")
                                    .append(m.getParameterTypes()[l].getName()).append("\")");
                        }
                    }
                }

                c3.append(" ) { ");

                if (m.getReturnType() == Void.TYPE) {
                    c3.append(" w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");").append(" return null;");
                } else {
                    c3.append(" return ($w)w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");");
                }

                c3.append(" }");

                mns.add(mn);
                if (m.getDeclaringClass() == c) {
                    // dmns中添加c自己声明的方法
                    dmns.add(mn);
                }
                // ms中添加（方法描述， 方法对象）
                ms.put(ReflectUtils.getDesc(m), m);
            }
            c3.append(" } catch(Throwable e) { ");
            c3.append("     throw new java.lang.reflect.InvocationTargetException(e); ");
            c3.append(" }");
        }

        c3.append(" throw new " + NoSuchMethodException.class.getName() + "(\"Not found method \\\"\"+$2+\"\\\" in class " + c.getName() + ".\"); }");

        // deal with get/set method.
        // 因为类一般都含有private成员变量，private变量都有get/set方法，这里就通过处理get/set方法，给c1和c2添加字符串
        Matcher matcher;
        // key是方法签名，value是方法对象
        for (Map.Entry<String, Method> entry : ms.entrySet()) {
            // md="getStuName()Ljava/lang/String;"
            String md = entry.getKey();
            Method method = entry.getValue();
            // 匹配get方法
            if ((matcher = ReflectUtils.GETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                // pn="stuName"
                String pn = propertyName(matcher.group(1));
                // 增加串 if( $2.equals("stuName") ){ return ($w)w.getStuName(); }
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                // key是属性名，vlaue是属性的clazz
                pts.put(pn, method.getReturnType());
            } // 匹配is/has/can方法
            else if ((matcher = ReflectUtils.IS_HAS_CAN_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                // pn="rich"
                String pn = propertyName(matcher.group(1));
                // 增加串 if( $2.equals("rich") ){ return ($w)w.isRich(); }
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
            } // 匹配set方法
            else if ((matcher = ReflectUtils.SETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                Class<?> pt = method.getParameterTypes()[0];
                // pn="stuName"
                String pn = propertyName(matcher.group(1));
                // 增加串 if( $2.equals("stuName") ){ w.setStuName((java.lang.String)$3); return; }
                c1.append(" if( $2.equals(\"").append(pn).append("\") ){ w.").append(method.getName()).append("(").append(arg(pt, "$3")).append("); return; }");
                pts.put(pn, pt);
            }
        }
        c1.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class " + c.getName() + ".\"); }");
        c2.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class " + c.getName() + ".\"); }");
        // 上面给c1，c2，c3赋值完毕了
        // make class
        // 下面就开始动态生成字节码了
        long id = WRAPPER_CLASS_COUNTER.getAndIncrement();
        ClassGenerator cc = ClassGenerator.newInstance(cl);
        // 若c是public修饰的类，则它的新建的包装类，类名="org.apache.dubbo.common.bytecode.Wrapper{id}"
        // 否则，类名=c.getName() + "$sw"{id}
        cc.setClassName((Modifier.isPublic(c.getModifiers()) ? Wrapper.class.getName() : c.getName() + "$sw") + id);
        // "extends Wrapper"
        cc.setSuperClass(Wrapper.class);

        cc.addDefaultConstructor();
        cc.addField("public static String[] pns;"); // property name array.
        cc.addField("public static " + Map.class.getName() + " pts;"); // property type map.
        cc.addField("public static String[] mns;"); // all method name array.
        cc.addField("public static String[] dmns;"); // declared method name array.
        for (int i = 0, len = ms.size(); i < len; i++) {
            cc.addField("public static Class[] mts" + i + ";");
        }

        cc.addMethod("public String[] getPropertyNames(){ return pns; }");
        cc.addMethod("public boolean hasProperty(String n){ return pts.containsKey($1); }");
        cc.addMethod("public Class getPropertyType(String n){ return (Class)pts.get($1); }");
        cc.addMethod("public String[] getMethodNames(){ return mns; }");
        cc.addMethod("public String[] getDeclaredMethodNames(){ return dmns; }");
        cc.addMethod(c1.toString());
        cc.addMethod(c2.toString());
        cc.addMethod(c3.toString());

        /**
         * 生成代码如下：
         *
         * public class Wrapper0 extends Wrapper {
         *     // 字段名列表
         *     public static String[] pns;
         *
         *     // 字段名与字段类型的映射关系
         *     public static java.util.Map<String, Class<?>> pts;
         *
         *     // 方法名列表
         *     public static String[] mns;
         *
         *     // 声明的方法名列表
         *     public static String[] dmns;
         *
         *     // 每个public方法的参数类型
         *     public static Class[] mts0;
         *     public static Class[] mts1;
         *     public static Class[] mts2;
         *
         *     public String[] getPropertyNames() {
         *         return pns;
         *     }
         *
         *     public boolean hasProperty(String n) {
         *         return pts.containsKey($1);
         *     }
         *
         *     public Class getPropertyType(String n) {
         *         return (Class) pts.get($1);
         *     }
         *
         *     public String[] getMethodNames() {
         *         return mns;
         *     }
         *
         *     public String[] getDeclaredMethodNames() {
         *         return dmns;
         *     }
         *
         *
         *      .......
         *     // 这里还需要加上c1，c2，c3的代码
         *     参考
         *     https://www.jianshu.com/p/57d53ff17062
         * }
         */

        try {
            Class<?> wc = cc.toClass();
            // setup static field.
            // 给静态字段设置值
            wc.getField("pts").set(null, pts);
            wc.getField("pns").set(null, pts.keySet().toArray(new String[0]));
            wc.getField("mns").set(null, mns.toArray(new String[0]));
            wc.getField("dmns").set(null, dmns.toArray(new String[0]));
            int ix = 0;
            for (Method m : ms.values()) {
                wc.getField("mts" + ix++).set(null, m.getParameterTypes());
            }
            return (Wrapper) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            cc.release();
            ms.clear();
            mns.clear();
            dmns.clear();
        }
    }

    /**
     * 将name串所代表的变量 转成cl类型的值
     * @param cl clazz
     * @param name 表示变量名字的字符串
     * @return
     */
    private static String arg(Class<?> cl, String name) {
        // 若cl是基本类型
        if (cl.isPrimitive()) {
            if (cl == Boolean.TYPE) {
                // 返回 "((Boolean){name}).booleanValue()"
                return "((Boolean)" + name + ").booleanValue()";
            }
            if (cl == Byte.TYPE) {
                return "((Byte)" + name + ").byteValue()";
            }
            if (cl == Character.TYPE) {
                return "((Character)" + name + ").charValue()";
            }
            if (cl == Double.TYPE) {
                return "((Number)" + name + ").doubleValue()";
            }
            if (cl == Float.TYPE) {
                return "((Number)" + name + ").floatValue()";
            }
            if (cl == Integer.TYPE) {
                return "((Number)" + name + ").intValue()";
            }
            if (cl == Long.TYPE) {
                return "((Number)" + name + ").longValue()";
            }
            if (cl == Short.TYPE) {
                return "((Number)" + name + ").shortValue()";
            }
            throw new RuntimeException("Unknown primitive type: " + cl.getName());
        }
        // 返回 "(类全名){name}"
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    private static String args(Class<?>[] cs, String name) {
        int len = cs.length;
        if (len == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arg(cs[i], name + "[" + i + "]"));
        }
        return sb.toString();
    }

    // 将入参pn的首字母小写，之后返回首字符小写的pn
    // （也不是所有入参的首字母都需要小写，这个函数考虑到了一些常用术语，这些术语还是保持原样，例如：pn="URL",返回 "URL" ）
    // 因为函数是通过判断第二个字符是否为小写，进而断定pn是否为常用术语的。 第二个字符为大写，则pn是常用术语， 原样返回pn
    private static String propertyName(String pn) {

        return pn.length() == 1 || Character.isLowerCase(pn.charAt(1)) ? Character.toLowerCase(pn.charAt(0)) + pn.substring(1) : pn;
    }

    // 若入参methods数组中有不属于Object类的方法，返回true。
    // 否则返回false
    private static boolean hasMethods(Method[] methods) {
        if (methods == null || methods.length == 0) {
            return false;
        }
        for (Method m : methods) {
            if (m.getDeclaringClass() != Object.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * get property name array.
     *
     * @return property name array.
     */
    abstract public String[] getPropertyNames();

    /**
     * get property type.
     *
     * @param pn property name.
     * @return Property type or nul.
     */
    abstract public Class<?> getPropertyType(String pn);

    /**
     * has property.
     *
     * @param name property name.
     * @return has or has not.
     */
    abstract public boolean hasProperty(String name);

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @return value.
     */
    abstract public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @param pv       property value.
     */
    abstract public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @return value array.
     */
    public Object[] getPropertyValues(Object instance, String[] pns) throws NoSuchPropertyException, IllegalArgumentException {
        Object[] ret = new Object[pns.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = getPropertyValue(instance, pns[i]);
        }
        return ret;
    }

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @param pvs      property value array.
     */
    public void setPropertyValues(Object instance, String[] pns, Object[] pvs) throws NoSuchPropertyException, IllegalArgumentException {
        if (pns.length != pvs.length) {
            throw new IllegalArgumentException("pns.length != pvs.length");
        }

        for (int i = 0; i < pns.length; i++) {
            setPropertyValue(instance, pns[i], pvs[i]);
        }
    }

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getMethodNames();

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getDeclaredMethodNames();

    /**
     * has method.
     *
     * @param name method name.
     * @return has or has not.
     */
    public boolean hasMethod(String name) {
        for (String mn : getMethodNames()) {
            if (mn.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * invoke method.
     *
     * @param instance instance.
     * @param mn       method name.
     * @param types
     * @param args     argument array.
     * @return return value.
     */
    abstract public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException, InvocationTargetException;
}
