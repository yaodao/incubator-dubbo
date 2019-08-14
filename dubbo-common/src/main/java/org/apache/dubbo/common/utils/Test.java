package org.apache.dubbo.common.utils;

import javassist.bytecode.stackmap.TypeData;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import sun.reflect.annotation.AnnotationType;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@TestStatus(retries = 22)
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

//    public static void main(String[] args) throws Exception{
////        Pattern pat = Pattern.compile("[\\-._0-9a-zA-Z]+");
////        Pattern pat =  Pattern.compile("[,\\-._0-9a-zA-Z]+");
////        Pattern pat =   Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");
////        Pattern pat =    Pattern.compile("[/\\-$._0-9a-zA-Z]+");
////        Pattern pat =  Pattern.compile("[*,\\-._0-9a-zA-Z]+");
//        Pattern pat =  Pattern.compile("[:*,\\s/\\-._0-9a-zA-Z]+");
////        String s = "aa-bb,count.000";
//        String s = "aa,bb:aa_bb:00*bb:aa  bb:aa-bb";
////        String s = "/aa$A.proxy/b-b/cc/dd";
//        Matcher mat = pat.matcher(s);
//        if(mat.find()){
//            System.out.println("match");
//            System.out.println(mat.group(0));
//        } else{
//            System.out.println("not find");
//        }
//
//        List<String> arr = new ArrayList<>();
//        arr.add("dd");
//        arr.add("aa");
//        arr.add("bb");
//
//        System.out.println(arr.iterator().next());
//    }

//    public static void main(String[] args) {
////        Class tem = boolean.class;
////        if (Boolean.class.equals(tem)){
////            System.out.println("hehe1");
////        }
////        if (Boolean.TYPE.equals(tem)){
////            System.out.println("hehe2");
////        }
////
//////        Class tt = TestImpl.class.asSubclass(Enum.class);
////        Class tt = TestImpl.class.asSubclass(TestInterface.class);
////        System.out.println(tt);
////
////        tt.getName();
////    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        System.out.println(normalizedTicksPerWheel);
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        System.out.println(normalizedTicksPerWheel);
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        System.out.println(normalizedTicksPerWheel);
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        System.out.println(normalizedTicksPerWheel);
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;

        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }


//    public static void main(String[] args) {
//        HashMap<String, String> m1 = new HashMap<>();
//        HashMap<String, String> m2 = new HashMap<>();
//
//        m1.put("aaa", "a");
//        m1.put("bbb", "b");
//        m1.put("ccc", "c");
//
//
//        m2.put("bbb", "b");
//        m2.put("aaa", "a");
//        m2.put("ccc", "c");
//        m2.put("ddd", "c");
//        System.out.println(m1.equals(m2));
//
//        System.out.println(normalizeTicksPerWheel(129));
////        System.out.println(normalizeTicksPerWheel(7));
//
//        System.out.println(System.getProperty("os.name", "").toLowerCase(Locale.US));
//        System.out.println(System.getProperty("os.name", "").contains("win"));
//    }

//    private long startTime;
//    public static void main(String[] args) throws InterruptedException {
////        long sleepTimeMs = (-210+9)/10;
////        System.out.println(sleepTimeMs);
////        System.out.println(sleepTimeMs/10 *10);
//
//        Test obj = new Test();
//        System.out.println(obj.startTime);
////        Thread.sleep(100);
////        final long currentTime = System.nanoTime() - startTime;
////        long sleepTimeMs = (currentTime + 999999) / 1000000;
////        System.out.println(sleepTimeMs);
//
//        String os = System.getProperty("os.name").toLowerCase();
//        System.out.println(os);
//    }

//    public static void main(String[] args) {
//        System.out.println(ExecutorService.class.getName());
//
//        Package pkg = String.class.getPackage();
//        String version = pkg.getImplementationVersion();
//        System.out.println(version);
//        String version2 = pkg.getSpecificationVersion();
//        System.out.println(version2);
//        System.out.println(System.getProperty("java.class.path"));
//        String tem = Test.class.getProtectionDomain().getCodeSource().getLocation().getPath();
//        URL url =  Test.class.getProtectionDomain().getCodeSource().getLocation();
//        System.out.println(tem);
//
//        System.out.println(System.getProperty("user.home"));
//    }

//    public static void main(String[] args) {
//        String filename = "D:\\testimg\\aa\\bb\\cc\\aabbcc.txt";
//        File file = new File(filename);
////        if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
////            if (!file.getParentFile().mkdirs()) {
////                throw new IllegalArgumentException("Invalid registry cache file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
////            }
////        }
//        System.out.println(file.getParentFile().exists());
//        System.out.println(file.exists());
//        System.out.println("over");
//    }

    public static void main(String[] args) {
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        map.put("aa","aa11");
        map.put("bb","bb11");
        System.out.println(map);

//        String res = map.putIfAbsent("cc","cc");
//        String res = map.computeIfAbsent("cc",k-> {return "cc";});
        String res = map.computeIfAbsent("bb",k-> {return "cc";});
//        map.put("dd",null);
        System.out.println(map);
        System.out.println(res);

        System.out.println("\\aabb".lastIndexOf("\\"));
    }
}
