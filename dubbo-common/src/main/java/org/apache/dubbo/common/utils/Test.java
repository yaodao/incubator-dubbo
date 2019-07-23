package org.apache.dubbo.common.utils;

import sun.reflect.annotation.AnnotationType;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@TestStatus(retries=22)
public class Test {
//    public static void main(String[] args) {
//        String tem = "file:/path/to/file.txt";
//        int i = tem.indexOf(":/");
//        System.out.println(i);
//        String tem1 = tem.substring(i+1);
//        System.out.println(tem1);
//
//
//        final Pattern INTEGER_PATTERN = Pattern.compile("([_.a-zA-Z0-9][-_.a-zA-Z0-9]*)[=](.*)");
////        final Pattern INTEGER_PATTERN = Pattern.compile("[_.a-zA-Z0-9]*");
//        String value = ".a=123aa";
////        String value = "";
//        boolean tem3 = INTEGER_PATTERN.matcher(value).matches();
////        boolean tem2 = INTEGER_PATTERN.matcher(value).find();
//        System.out.println(tem3);
////        System.out.println(tem2);
//
//        String aa = "abc";
//        String bb = "abcdef";
//        System.out.println(bb.contains(aa));
//
//        String pattern = "*#06*abd";
//        int cc = pattern.lastIndexOf('*');
//        System.out.println(cc);
//    }

//    public static void main(String[] args) throws Exception {
//        URL[] urls =
//                new URL[] {new URL("file:\\D:\\javagitcode\\incubator-dubbo\\dubbo-common\\target\\classes\\")};
////        ClassLoader cl = new URLClassLoader(urls);
//        ClassLoader cl = new URLClassLoader(urls, null);
//        Class clz = cl.loadClass("org.apache.dubbo.common.utils.TestImpl");
//
//        System.out.println("1:" + TestInterface.class.getClassLoader());
//        System.out.println("2:" + clz.getClassLoader());
//        System.out.println(Thread.currentThread().getContextClassLoader());
//        System.out.println(clz.newInstance());
//
//    }

//    public static void main(String[] args) throws InvocationTargetException, IllegalAccessException {
//        Method[] methods = TestStatus.class.getMethods();
//        Annotation temAnno = Test.class.getAnnotation(TestStatus.class);
//        for(int i=0;i<methods.length;i++)
//        {
//            String str = methods[i].getDeclaringClass().getName() + "：" + methods[i].getName();
//            System.out.println(str);
//            if (methods[i].getName().equals("retries")){
//                Method tem = methods[i];
//                System.out.println("==============" + tem.getDefaultValue());
////                System.out.println("==============" + tem.getDefaultValue().getClass());
//
//                Object res = tem.invoke(temAnno);
//                System.out.println(res);
//            }
//            if (methods[i].getName().equals("parameters")){
//                Method tem = methods[i];
//                System.out.println("==============" + tem.getDefaultValue());
//            }
//        }
//    }

//    public static void main(String[] args) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
//        Method tem = null;
//        Method[] methods = TestImpl.class.getMethods();
//        for(int i=0;i<methods.length;i++){
//            if (methods[i].getName().equals("show")){
//                tem = methods[i];
//                System.out.println("==============" + tem.getDefaultValue());
//            }
//        }
//
//        TestImpl obj = new TestImpl();
//
//        Method method2 = obj.getClass().getMethod(tem.getName(), tem.getParameterTypes());
//        Object value1 = tem.invoke(obj, new Object[]{"aa","bb",11});
//        Object value2 = method2.invoke(obj, new Object[]{});
//
//        System.out.println("value1=" +value1);
//        System.out.println("value2=" +value2);
//    }

//    public static void main(String[] args) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
//        Method tem = null;
//        Method[] methods = TestImpl.class.getMethods();
//        for(int i=0;i<methods.length;i++){
//            if (methods[i].getName().equals("showb")){
//                tem = methods[i];
//            }
//        }
//
//        TestImpl obj = new TestImpl();
//
//        Method method2 = obj.getClass().getMethod(tem.getName());
////        Object value1 = tem.invoke(obj, new Object[]{});
//        Object value1 = tem.invoke(obj);
//        Object value2 = method2.invoke(obj);
//
//        System.out.println("value1=" +value1);
//        System.out.println("value2=" +value2);
//    }

    public static void main(String[] args) throws Exception{
//        Pattern pat = Pattern.compile("[\\-._0-9a-zA-Z]+");
//        Pattern pat =  Pattern.compile("[,\\-._0-9a-zA-Z]+");
//        Pattern pat =   Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");
//        Pattern pat =    Pattern.compile("[/\\-$._0-9a-zA-Z]+");
//        Pattern pat =  Pattern.compile("[*,\\-._0-9a-zA-Z]+");
        Pattern pat =  Pattern.compile("[:*,\\s/\\-._0-9a-zA-Z]+");
//        String s = "aa-bb,count.000";
        String s = "aa,bb:aa_bb:00*bb:aa  bb:aa-bb";
//        String s = "/aa$A.proxy/b-b/cc/dd";
        Matcher mat = pat.matcher(s);
        if(mat.find()){
            System.out.println("match");
            System.out.println(mat.group(0));
        } else{
            System.out.println("not find");
        }

        List<String> arr = new ArrayList<>();
        arr.add("dd");
        arr.add("aa");
        arr.add("bb");

        System.out.println(arr.iterator().next());
    }
}
