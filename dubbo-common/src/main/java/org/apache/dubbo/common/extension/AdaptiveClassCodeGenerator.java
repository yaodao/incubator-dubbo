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
    // 生成一个类的字符串
    public String generate() {
        // no need to generate adaptive class since there's no adaptive method found.
        // 需要有@Adaptive标注的方法
        if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        StringBuilder code = new StringBuilder();
        code.append(generatePackageInfo());
        code.append(generateImports());
        code.append(generateClassDeclaration());
        
        Method[] methods = type.getMethods();
        for (Method method : methods) {
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
    // 返回字符串 "public class %s$Adaptive implements %s {\n"
    private String generateClassDeclaration() {
        // getSimpleName()只返回类名, getCanonicalName返回类全名 (是可读性更好的字符串)
        return String.format(CODE_CLASS_DECLARATION, type.getSimpleName(), type.getCanonicalName());
    }
    
    /**
     * generate method not annotated with Adaptive with throwing unsupported exception 
     */
    // 返回字符串 "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n"
    private String generateUnsupported(Method method) {
        // method的字符串 "public static void com.its.dev55.Test15.fun(java.util.Map)"
        return String.format(CODE_UNSUPPORTED, method, type.getName());
    }
    
    /**
     * get index of parameter with type URL
     */
    // 获取URL类型的参数在method的所有参数中的位置, 没有返回-1
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
    // 返回一个函数字符串(包括返回值, 函数名, 参数, 函数体)
    private String generateMethod(Method method) {
        String methodReturnType = method.getReturnType().getCanonicalName();
        String methodName = method.getName();
        String methodContent = generateMethodContent(method);
        String methodArgs = generateMethodArguments(method);
        String methodThrows = generateMethodThrows(method);
        return String.format(CODE_METHOD_DECLARATION, methodReturnType, methodName, methodArgs, methodThrows, methodContent);
    }

    /**
     * generate method arguments
     */
    /**
     * 对method对象的参数生成字符串, 例如函数形如: public void fun(String name, int age)
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
    private String generateMethodContent(Method method) {
        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        if (adaptiveAnnotation == null) {
            // 方法上没有@Adaptive注解, 返回一个字符串(内容是"throws new ***Exception()")
            return generateUnsupported(method);
        } else {
            // 获取URL类型的参数在method的所有参数中的位置, 没有返回-1
            int urlTypeIndex = getUrlTypeIndex(method);
            
            // found parameter in URL type
            if (urlTypeIndex != -1) {
                // Null Point check
                code.append(generateUrlNullCheck(urlTypeIndex));
            } else {
                // did not find parameter in URL type
                code.append(generateUrlAssignmentIndirectly(method));
            }

            String[] value = getMethodAdaptiveValue(adaptiveAnnotation);

            // method是否含有org.apache.dubbo.rpc.Invocation类型的参数
            boolean hasInvocation = hasInvocationArgument(method);
            
            code.append(generateInvocationArgumentNullCheck(method));
            
            code.append(generateExtNameAssignment(value, hasInvocation));
            // check extName == null?
            code.append(generateExtNameNullCheck(value));
            
            code.append(generateExtensionAssignment());

            // return statement
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
     * 有Invocation时生成的代码例如:      String extName = url.getMethodParameter(methodName, "loadbalance", "random");
     * 没有Invocation时生成的代码例如:    String extName = url.getParameter("dispatcher", url.getParameter("dispather", url.getParameter("channel.handler", "all")));
     * @param value
     * @param hasInvocation
     * @return
     */

    // value数组是Adaptive注解的value值, hasInvocation表示调用处的method有Invocation类型的参数
    private String generateExtNameAssignment(String[] value, boolean hasInvocation) {
        // TODO: refactor it
        String getNameCode = null;
        for (int i = value.length - 1; i >= 0; --i) {
            if (i == value.length - 1) {
                if (null != defaultExtName) {
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
            } else {
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
     * org.apache.dubbo.common.threadpool.ThreadPool extension =(org.apache.dubbo.common.threadpool.ThreadPool)ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.threadpool.ThreadPool.class).getExtension(extName);
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
     * 若method有org.apache.dubbo.rpc.Invocation类型的参数, 则生成检验该Invocation类型的参数是否为空的代码, 若无则返回空串
     *
     * 返回字符串如下:
     * if (argi == null)
     *    throw new IllegalArgumentException("invocation == null");
     * String methodName = argi.getMethodName();
     *
     * 返回非空串时, 表示argi是Invocation类型的对象, 这点从异常信息也可以看出
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
     * 获取参数adaptiveAnnotation这个注解中的value值, 若没有值, 则返回类似hello.world的串 (假如type所属的类是HelloWorld)
     * @param adaptiveAnnotation
     * @return
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
     * 查看method的参数, 看每个参数所属的类的方法里, 是否有getXXX且返回值类型是URL的方法
     * 有就返回一个字符串 (字符串表示了一段代码, 给url赋值的代码)
     * 没有抛出异常
     */
    private String generateUrlAssignmentIndirectly(Method method) {
        // 该method的所有参数的clazz
        Class<?>[] pts = method.getParameterTypes();
        
        // 对每个参数的clazz, 查看该clazz下的方法, 看是否有名字为getXXX且返回值类型是URL的方法
        for (int i = 0; i < pts.length; ++i) {
            for (Method m : pts[i].getMethods()) {
                String name = m.getName();
                if ((name.startsWith("get") || name.length() > 3)
                        && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() == URL.class) {
                    // i是method的第i个参数, pts[i]是第i个参数的clazz对象, name是第i个参数所属类型内的某个方法名
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
