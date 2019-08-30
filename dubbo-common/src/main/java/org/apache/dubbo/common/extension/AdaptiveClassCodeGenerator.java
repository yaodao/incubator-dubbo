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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;

/**
 * Code generator for Adaptive class
 */
public class AdaptiveClassCodeGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveClassCodeGenerator.class);

    private static final String CLASSNAME_INVOCATION = "org.apache.dubbo.rpc.Invocation";
    
    private static final String CODE_PACKAGE = "package %s;\n";
    
    private static final String CODE_IMPORTS = "import %s;\n";
    
    private static final String CODE_CLASS_DECLARATION = "public class %s$Adaptive implements %s {\n";
    
    private static final String CODE_METHOD_DECLARATION = "public %s %s(%s) %s {\n%s}\n";
    
    private static final String CODE_METHOD_ARGUMENT = "%s arg%d";
    
    private static final String CODE_METHOD_THROWS = "throws %s";
    
    private static final String CODE_UNSUPPORTED = "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n";
    
    private static final String CODE_URL_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"url == null\");\n%s url = arg%d;\n";
    
    private static final String CODE_EXT_NAME_ASSIGNMENT = "String extName = %s;\n";
    
    private static final String CODE_EXT_NAME_NULL_CHECK = "if(extName == null) "
                    + "throw new IllegalStateException(\"Failed to get extension (%s) name from url (\" + url.toString() + \") use keys(%s)\");\n";

    // getMethodName是Invocation类的一个方法
    private static final String CODE_INVOCATION_ARGUMENT_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"invocation == null\"); "
                    + "String methodName = arg%d.getMethodName();\n";
    

    private static final String CODE_EXTENSION_ASSIGNMENT = "%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n";
    
    private final Class<?> type;

    // @SPI的value值
    private String defaultExtName;
    
    public AdaptiveClassCodeGenerator(Class<?> type, String defaultExtName) {
        this.type = type;
        this.defaultExtName = defaultExtName;
    }
    
    /**
     * test if given type has at least one method annotated with <code>SPI</code>
     */
    // type是否有带@Adaptive注解的方法, true 有
    private boolean hasAdaptiveMethod() {
        return Arrays.stream(type.getMethods()).anyMatch(m -> m.isAnnotationPresent(Adaptive.class));
    }
    
    /**
     * generate and return class code
     */
    // 生成一个代理类的字符串(包括类中的每个方法)
    // 返回的字符串示例文档：http://note.youdao.com/noteshare?id=1a572d5f5954e55f096c83e1cdc0c3c8&sub=E04D444A2134414590778A58F5F6C70C
    public String generate() {
        // no need to generate adaptive class since there's no adaptive method found.
        /**
         * 对于要自动生成拓展类的接口type，Dubbo 要求该接口至少有一个方法被 Adaptive 注解修饰。
         * 若不满足此条件，就会抛出运行时异常。
         */
        // 成员变量type需要有@Adaptive标注的方法
        if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        /**
         * 举例： type=Protocol.class时
         * 这三行代码生成的字符串，
         * package com.alibaba.dubbo.rpc;
         * import com.alibaba.dubbo.common.extension.ExtensionLoader;
         * public class Protocol$Adaptive implements com.alibaba.dubbo.rpc.Protocol {
         *
         */
        StringBuilder code = new StringBuilder();
        code.append(generatePackageInfo());
        code.append(generateImports());
        code.append(generateClassDeclaration());
        
        Method[] methods = type.getMethods();
        for (Method method : methods) {
            // 对每个method，生成一个方法字符串
            code.append(generateMethod(method));
        }
        code.append("}");
        
        if (logger.isDebugEnabled()) {
            logger.debug(code.toString());
        }
        return code.toString();
    }

    /**
     * generate package info
     */
    // 返回字符串 "package com.its.dev55;\n"
    private String generatePackageInfo() {
        return String.format(CODE_PACKAGE, type.getPackage().getName());
    }

    /**
     * generate imports
     */
    // 返回字符串 "import org.apache.dubbo.common.extension.ExtensionLoader;\n"
    private String generateImports() {
        return String.format(CODE_IMPORTS, ExtensionLoader.class.getName());
    }

    /**
     * generate class declaration
     */
    // 返回字符串 "public class type类名$Adaptive implements type类全名 {\n"
    // 例如： type=Protocol.class，
    // 则返回的串为  "public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {\n"
    private String generateClassDeclaration() {
        // getSimpleName()只返回类名, getCanonicalName返回类全名 (是可读性更好的字符串)
        return String.format(CODE_CLASS_DECLARATION, type.getSimpleName(), type.getCanonicalName());
    }

    /**
     * generate method not annotated with Adaptive with throwing unsupported exception
     */
    // 返回字符串 "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n"

    // 举例： type=Protocol.class时， Protocol接口的destroy方法不带@Adaptive注解， 则返回字符串如下：
    // throw new UnsupportedOperationException(
    //            "method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
    private String generateUnsupported(Method method) {
        return String.format(CODE_UNSUPPORTED, method, type.getName());
        // 其中，method的字符串，举例： "public static void com.its.dev55.Test15.fun(java.util.Map)"
    }
    
    /**
     * get index of parameter with type URL
     */
    // 在method的参数列表中寻找URL类型的参数， 有 则返回该类型的参数的位置， 没有返回-1
    private int getUrlTypeIndex(Method method) {            
        int urlTypeIndex = -1;
        Class<?>[] pts = method.getParameterTypes();
        for (int i = 0; i < pts.length; ++i) {
            if (pts[i].equals(URL.class)) {
                urlTypeIndex = i;
                break;
            }
        }
        return urlTypeIndex;
    }
    
    /**
     * generate method declaration
     */
    // 根据method的参数信息, 生成一个方法的字符串(包括返回值, 函数名, 参数, 抛出的异常, 方法体),
    // 最后返回的是一个完整的方法的字符串，
    // 该字符串描述的功能，分两种情况，method带@Adaptive注解 和 method不带@Adaptive注解。
    /**
     * 1）若入参method中带@Adaptive注解， 以Protocol接口的refer方法举例
     * 返回的字符串为：
     *
     *  public com.alibaba.dubbo.rpc.Invoker refer(java.lang.Class arg0, com.alibaba.dubbo.common.URL arg1) throws com.alibaba.dubbo.rpc.RpcException {
     *     if (arg1 == null) throw new IllegalArgumentException("url == null");
     *     com.alibaba.dubbo.common.URL url = arg1;
     *     String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
     *     if (extName == null)
     *         throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.Protocol) name from url(" + url.toString() + ") use keys([protocol])");
     *     com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);
     *
     *    return extension.refer(arg0, arg1);// 这里就是代理调用method方法
     * }
     *
     *
     *
     * 2）若入参method中不带@Adaptive注解， 以Protocol接口的destroy方法举例
     * 返回的字符串为：
     *
     * public void destroy() {
     *      throw new UnsupportedOperationException(
     *             "method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
     * }
     *
     * Protocol接口中，其他方法的生成代码参考 https://blog.csdn.net/xiao_jun_0820/article/details/82257623
     */
    private String generateMethod(Method method) {
        String methodReturnType = method.getReturnType().getCanonicalName();
        String methodName = method.getName();
        String methodContent = generateMethodContent(method);
        String methodArgs = generateMethodArguments(method);
        String methodThrows = generateMethodThrows(method);
        // 返回字符串为 "public %s %s(%s) %s {\n%s}\n"
        return String.format(CODE_METHOD_DECLARATION, methodReturnType, methodName, methodArgs, methodThrows, methodContent);
    }

    /**
     * generate method arguments
     */
    /**
     * 对method对象的参数列表生成字符串, 字符串只包括参数列表
     * 例如函数形如: public void fun(String name, int age)
     * 返回的字符串为 "java.lang.String arg0, int arg1"
     * @param method
     * @return
     */
    private String generateMethodArguments(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length)
                        .mapToObj(i -> String.format(CODE_METHOD_ARGUMENT, pts[i].getCanonicalName(), i))
                        .collect(Collectors.joining(", "));
    }
    
    /**
     * generate method throws 
     */
    /**
     * 对函数签名后面的throws生成字符串, 例如函数签名为 public void fun() throws InterruptedException, IndexOutOfBoundsException
     * 则最后生成字符串为 "throws java.lang.InterruptedException, java.lang.IndexOutOfBoundsException"
     * @param method
     * @return
     */
    private String generateMethodThrows(Method method) {
        Class<?>[] ets = method.getExceptionTypes();
        if (ets.length > 0) {
            String list = Arrays.stream(ets).map(Class::getCanonicalName).collect(Collectors.joining(", "));
            return String.format(CODE_METHOD_THROWS, list);
        } else {
            return "";
        }
    }
    
    /**
     * generate method URL argument null check 
     */
    /**
     * 返回一个字符串, 如下:
     *  if (arg%d == null)
     *      throw new IllegalArgumentException(\"url == null\");
     *  org.apache.dubbo.common.URL url = arg%d;
     *
     *  其中%d 值是 参数index
     */
    private String generateUrlNullCheck(int index) {
        return String.format(CODE_URL_NULL_CHECK, index, URL.class.getName(), index);
    }
    
    /**
     * generate method content
     */

    /**
     * 返回一个方法的方法体字符串，该方法体字符串表达的功能是：
     * 根据方法的url参数, 得到extName，加载该extName对应的实现类，调用该实现类实例的method（类似代理模式）。
     * 如下: 这段串是方法体（方法的参数字符串，在上一层函数中得到）

         if (arg1 == null)
            throw new IllegalArgumentException("url == null");
         com.alibaba.dubbo.common.URL url = arg1;
         String extName = url.getParameter("t", "dubbo");
         if (extName == null)
            throw new IllegalStateException("Fail to get extension(shuqi.dubbotest.spi.adaptive.AdaptiveExt2) name from url(" + url.toString() + ") use keys([t])");
         shuqi.dubbotest.spi.adaptive.AdaptiveExt2 extension =
            (shuqi.dubbotest.spi.adaptive.AdaptiveExt2) ExtensionLoader.getExtensionLoader(shuqi.dubbotest.spi.adaptive.AdaptiveExt2.class).getExtension(extName);
         return extension.echo(arg0, arg1); // 这里就是代理调用

     其中，arg1就是找到的，URL类型的参数
     */
    private String generateMethodContent(Method method) {
        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        if (adaptiveAnnotation == null) {
            // 方法必须有@Adaptive注解,若没有，则函数直接返回抛异常的串(内容是"throws new ***Exception()")
            return generateUnsupported(method);
        } else {
            // 能到这，说明入参method上带有@Adaptive注解
            // 方法上带@Adaptive注解，就需要该方法的参数是URL类型的，这样才能根据URL参数值，动态生成方法体

            // 在method的参数列表中，判断并返回URL类型的参数的位置
            int urlTypeIndex = getUrlTypeIndex(method);

            // 本质上下面这段代码，就是产生给url赋值的字符串（使用入参method的参数列表，给url赋值）
            if (urlTypeIndex != -1) {
                // Null Point check
                // method的参数列表中有URL类型的参数，则返回一个字符串
                code.append(generateUrlNullCheck(urlTypeIndex));
            } else {
                // method的参数列表中没有URL类型的参数，
                // 再看看method的每个参数的clazz中是否有返回URL的get方法
                // 有 则返回一个字符串，没有则抛出异常
                // did not find parameter in URL type
                code.append(generateUrlAssignmentIndirectly(method));
            }


            // 下面这段代码，主要是产生给extName赋值的字符串（ 从url中获取出扩展类的名字，赋值给extName，加载并生成该扩展类的对象）
            // 取@Adaptive注解中的value值
            String[] value = getMethodAdaptiveValue(adaptiveAnnotation);

            // method是否含有org.apache.dubbo.rpc.Invocation类型的参数
            boolean hasInvocation = hasInvocationArgument(method);
            
            code.append(generateInvocationArgumentNullCheck(method));

            // 产生一句 从url的参数中取扩展类的名字 的代码 "String extName = url.getParameter(value, defaultExtName)
            code.append(generateExtNameAssignment(value, hasInvocation));
            // check extName == null?
            // 产生一句 若extName为空，就抛出异常 的代码
            code.append(generateExtNameNullCheck(value));

            // 产生获取extName对应的实例对象的代码串 "extension = ExtensionLoader.getExtensionLoader(type).getExtension(extName);"
            // （从源码来看，getExtension()是从配置文件中加载实现类的对象）
            code.append(generateExtensionAssignment());

            // return statement
            // 使用上句得到的extension对象，调用它的method方法
            code.append(generateReturnAndInovation(method));
        }
        
        return code.toString();
    }

    /**
     * generate code for variable extName null check
     */
    /**
     * 生成如下代码:
     * if(extName == null)
     *      throw new IllegalStateException("Failed to get extension (org.apache.dubbo.common.threadpool.ThreadPool) name from url (" + url.toString() + ") use keys([impl1, impl2])");
     * @param value
     * @return
     */
    private String generateExtNameNullCheck(String[] value) {
        return String.format(CODE_EXT_NAME_NULL_CHECK, type.getName(), Arrays.toString(value));
    }

    /**
     * generate extName assigment code
     */
    /**
     * 本函数会生成但不限于下面的代码：
     * String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
     * String extName = url.getMethodParameter(methodName, "loadbalance", "random");  // 有Invocation时生成的代码
     * String extName = url.getParameter("client", url.getParameter("transporter", "netty"));  //没有Invocation时生成的代码
     * @param value
     * @param hasInvocation
     * @return
     */

    /**
     * 这个函数就是产生一句，从url的参数中取扩展类的名字 的代码，"String extName = url.getParameter(***)"
     *
     * 举例：该函数入参
     * boolean hasInvocation = false;
     * String[] value = ["client", "transporter"]; // value数组长度>1
     * String defaultExtName = "netty";
     * String getNameCode = null;
     *
     * 则最后生成的串是：
     * String extName = url.getParameter("client", url.getParameter("transporter", "netty"));
     *
     * 其中，需要注意，这是@Adaptive注解的value值长度>1 的情况
     * 这段在dubbo官方文档上讲的挺好
     * http://dubbo.apache.org/zh-cn/docs/source_code_guide/adaptive-extension.html      2.2.3.5 生成拓展名获取逻辑
     *
     * @param value 是Adaptive注解的value值, 数组类型， 这个值肯定不空，因为上一级函数肯定会给一个值。
     * @param hasInvocation method是否有Invocation类型的参数 （这个method是上一级函数的入参）
     * @return
     */
    private String generateExtNameAssignment(String[] value, boolean hasInvocation) {
        // TODO: refactor it
        String getNameCode = null;
        // 此处循环目的是 生成从URL中获取拓展类的名字的代码，生成的字符串会赋值给 getNameCode 变量。
        // 注意这个循环的遍历顺序是由后向前遍历的。
        for (int i = value.length - 1; i >= 0; --i) {
            if (i == value.length - 1) {
                if (null != defaultExtName) {
                    // value值不是"protocol"。
                    // "protocol"参数值可通过 url.getProtocol方法获取，其他参数值只能用url.getParameter获取，所以这里判断下是否为"protocol"
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        }
                    } else {
                        getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    }
                } else {
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        }
                    } else {
                        getNameCode = "url.getProtocol()";
                    }
                }
            } else {// value数组长度>1

                if (!"protocol".equals(value[i])) {
                    if (hasInvocation) {
                        getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                    } else {
                        getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    }
                } else {
                    getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
        }
        
        return String.format(CODE_EXT_NAME_ASSIGNMENT, getNameCode);
    }

    /**
     * @return
     */
    /**
     * 生成代码例如:
     * org.apache.dubbo.common.threadpool.ThreadPool extension =(ThreadPool)ExtensionLoader.getExtensionLoader(ThreadPool.class).getExtension(extName);
     * @return
     */
    private String generateExtensionAssignment() {
        return String.format(CODE_EXTENSION_ASSIGNMENT, type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
    }

    /**
     * generate method invocation statement and return it if necessary
     */
    /**
     * method没有返回值时, 生成代码:  extension.fun2(arg0, arg1);
     * method有返回值时, 生成代码:  return extension.fun2(arg0, arg1);
     * @param method
     * @return
     */
    private String generateReturnAndInovation(Method method) {
        String returnStatement = method.getReturnType().equals(void.class) ? "" : "return ";
        
        String args = Arrays.stream(method.getParameters()).map(Parameter::getName).collect(Collectors.joining(", "));

        return returnStatement + String.format("extension.%s(%s);\n", method.getName(), args);
    }
    
    /**
     * test if method has argument of type <code>Invocation</code>
     */
    // method的参数中, 是否有org.apache.dubbo.rpc.Invocation类型的参数, true 有
    private boolean hasInvocationArgument(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return Arrays.stream(pts).anyMatch(p -> CLASSNAME_INVOCATION.equals(p.getName()));
    }
    
    /**
     * generate code to test argument of type <code>Invocation</code> is null
     */
    /**
     * method若有org.apache.dubbo.rpc.Invocation类型的参数, 则生成检验该Invocation类型的参数是否为空的代码,
     *       若无则返回空串
     *
     * 若有Invocation类型的参数argi, 则返回字符串如下:
     * if (argi == null)
     *    throw new IllegalArgumentException("invocation == null");
     * String methodName = argi.getMethodName();
     *
     * 若没有，则返回空串
     *
     */
    private String generateInvocationArgumentNullCheck(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length).filter(i -> CLASSNAME_INVOCATION.equals(pts[i].getName()))
                        .mapToObj(i -> String.format(CODE_INVOCATION_ARGUMENT_NULL_CHECK, i, i))
                        .findFirst().orElse("");
    }

    /**
     * get value of adaptive annotation or if empty return splitted simple name
     */
    /**
     * 获取注解@Adaptive中的value值。
     * 若注解没给value值, 则返回类的名字。
     * 注意：这个函数肯定不会返回空串
     *
     * 例如 ：
     * 若type所属的类是LoadBalance， 则返回串为 "load.balance"
     * 若type所属的类是Compile， 则返回串为 "compile"
     *
     *
     * @param adaptiveAnnotation
     * @return 这个函数肯定不会返回空串
     */
    private String[] getMethodAdaptiveValue(Adaptive adaptiveAnnotation) {
        String[] value = adaptiveAnnotation.value();
        // value is not set, use the value generated from class name as the key
        if (value.length == 0) {
            // 把类名字中的每个单词的首字母小写, 每个单词之间用"."连接
            String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
            value = new String[]{splitName};
        }
        return value;
    }

    /**
     * get parameter with type <code>URL</code> from method parameter:
     * <p>
     * test if parameter has method which returns type <code>URL</code>
     * <p>
     * if not found, throws IllegalStateException
     */

    /**
     * 遍历method的参数列表, 看每个参数的clazz所拥有的方法里, 是否有getXXX且返回值类型是URL的方法
     * 有就返回一个字符串如下，其中argi就是满足上面所述条件的参数
     *      if (argi == null)
     *           throw new IllegalArgumentException(\"%s argument == null\");
     *      if (argi.getXXX() == null)
     *           throw new IllegalArgumentException(\"%s argument getXXX() == null\");
     *      org.apache.dubbo.common.URL url = argi.getXX();
     *
     * 没有则抛出异常
     */
    private String generateUrlAssignmentIndirectly(Method method) {
        // 该method的所有参数的clazz
        Class<?>[] pts = method.getParameterTypes();
        
        // 对每个参数的clazz, 查看该clazz下的方法, 看是否有名字为getXXX且返回值类型是URL的方法
        for (int i = 0; i < pts.length; ++i) {
            for (Method m : pts[i].getMethods()) {
                String name = m.getName();
                // 方法需要 以get开头，public，非static，无参数，返回值是URL类型 才能进if
                if ((name.startsWith("get") || name.length() > 3)
                        && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() == URL.class) {
                    // i是method的第i个参数, pts[i]是第i个参数的clazz, name是第i个参数的clazz内的get***方法名
                    return generateGetUrlNullCheck(i, pts[i], name);
                }
            }
        }
        
        // getter method not found, throw
        throw new IllegalStateException("Failed to create adaptive class for interface " + type.getName()
                        + ": not found url parameter or url attribute in parameters of method " + method.getName());

    }

    /**
     * 1, test if argi is null
     * 2, test if argi.getXX() returns null
     * 3, assign url with argi.getXX()
     * 该函数最后生成的字符串, 可以根据上面3条的意思自己写出来
     * 就是先检查argi是否为空, 再检查argi.getXX()是否为空,  不空则把argi.getXX()赋值给url变量
     */

    /**
     * 返回的字符串如下:
     *  if (argi == null)
     *      throw new IllegalArgumentException(\"%s argument == null\");
     *  if (argi.getXX() == null)
     *      throw new IllegalArgumentException(\"%s argument getXX() == null\");
     *  org.apache.dubbo.common.URL url = argi.getXX();
     */
    // 这里传入的参数是什么意思, 参见调用处
    private String generateGetUrlNullCheck(int index, Class<?> type, String method) {
        // Null point check
        StringBuilder code = new StringBuilder();
        code.append(String.format("if (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");\n",
                index, type.getName()));
        code.append(String.format("if (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");\n",
                index, method, type.getName(), method));

        code.append(String.format("%s url = arg%d.%s();\n", URL.class.getName(), index, method));
        return code.toString();
    }
    
}
