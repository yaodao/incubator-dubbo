package org.apache.dubbo.common.utils;

import javassist.bytecode.stackmap.TypeData;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.logging.log4j.core.net.Protocol;
import sun.reflect.annotation.AnnotationType;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
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
//        System.out.println(pkg.getName());
//        String version = pkg.getImplementationVersion();
//        System.out.println(version);
//        String version2 = pkg.getSpecificationVersion();
//        System.out.println(version2);
//        System.out.println(System.getProperty("java.class.path"));
//        String tem = Test.class.getProtectionDomain().getCodeSource().getLocation().getPath();
////        URL url =  Test.class.getProtectionDomain().getCodeSource().getLocation();
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

//    public static void main(String[] args) {
//        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
//        map.put("aa","aa11");
//        map.put("bb","bb11");
//        System.out.println(map);
//
////        String res = map.putIfAbsent("cc","cc");
////        String res = map.computeIfAbsent("cc",k-> {return "cc";});
//        String res = map.computeIfAbsent("bb",k-> {return "cc";});
////        map.put("dd",null);
//        System.out.println(map);
//        System.out.println(res);
//
//        System.out.println("\\aabb".lastIndexOf("\\"));
//    }

//    public static void main(String[] args) {

//            // 正则
////            final Pattern INTEGER_PATTERN = Pattern.compile("(-?[0-9]+)"); // 是否数字串
////            final Pattern INTEGER_PATTERN = Pattern.compile("aa(?:[_$a-zA-Z][_$a-zA-Z0-9]*)");
//            final Pattern INTEGER_PATTERN = Pattern.compile("(?:is|has|can)([A-Z][_a-zA-Z0-9]*)\\(\\)");
////            String value = "asa-123aa";
////            String value = "a:hasaa11()Z";// isRich()Z
//            String value = "isRich()";// isRich()Z
//            boolean tem = INTEGER_PATTERN.matcher(value).matches();
//
//            Matcher matcher2 = INTEGER_PATTERN.matcher(value);
//            boolean tem2 = matcher2.find();
//
//
//            System.out.println(tem);
//            System.out.println(tem2);
//            System.out.println(matcher2.group());
//            System.out.println(matcher2.group(0));
//            System.out.println(matcher2.group(1));


//            final Pattern INTEGER_PATTERN = Pattern.compile("(?:is|has|can)([A-Z][_a-zA-Z0-9]*)\\(\\)");
//            String value = "aaaRich()bb";
//            Matcher matcher2 = INTEGER_PATTERN.matcher(value);
//            System.out.println(matcher2.find());
//            if (matcher2.find()){
//                System.out.println(matcher2.group(1));
//            }
//    }
//    public static void main(String[] args) {
//
//        Wrapper.getWrapper(Stu.class);
//    }

//    class SS{
//
//    }
//    public static void main(String[] args) {
//        String tem = UUID.randomUUID().toString();
//        System.out.println(tem);
//
//        Test obj= new Test();
//        Test.SS inner = obj.new SS();
//        System.out.println(inner.getClass());
//    }

//    private static String message = "hello";
//    private static AtomicReference<String> atomicReference;
//
//    public static void main(final String[] arguments) throws InterruptedException {
////        public static AtomicInteger count = new AtomicInteger(0);
//
//
//        atomicReference = new AtomicReference<String>(message);
//        new Thread("Thread 1") {
//            public void run() {
//                atomicReference.compareAndSet(message, "Thread 1");
//                message = message.concat("-Thread 1!");
//            };
//        }.start();
//
//        Thread.sleep(1000);
//        System.out.println("Message is: " + message);
//        System.out.println("Atomic Reference of Message is: " + atomicReference.get());
//    }

//    public static void main(String[] args) {
//        Thread thread = new Thread(() -> {
//            System.out.println(Thread.currentThread().getName() + "等待");
//            LockSupport.park();
//            System.out.println(Thread.currentThread().getName() + "被唤醒");
//            try {
//                Thread.sleep(5000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//            System.out.println(Thread.currentThread().getName() + "等待2");
//            LockSupport.park();
//            System.out.println(Thread.currentThread().getName() + "被唤醒");
//        });
//        thread.start();
//        try {
//            Thread.sleep(3000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        LockSupport.unpark(thread);
//        try {
//            Thread.sleep(10);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        LockSupport.unpark(thread);
//        System.out.println("end main");
//    }

//    public static void main(String[] args) {
//        Thread thread = Thread.currentThread();
//        LockSupport.unpark(thread);
//        LockSupport.unpark(thread); //可以多次释放一个许可证，多次执行也只会释放一个许可证。
//        LockSupport.park(); //但是一次只能获取一个许可证（不可重入）
//        System.out.println("park1 get permit");
//        LockSupport.park(); //线程在这里等待
//        System.out.println("--");
//    }

//    public static void main(String[] args) throws InterruptedException {
//        Thread t = new Thread(new Runnable() {
//            public void run() {
//                System.out.println("thread...");
//                LockSupport.park(this);
//                System.out.println("thread done.");
//            }
//        });
//
//        t.start();
//        Thread.sleep(2000);
//
//        t.interrupt(); //如果因为park而被阻塞，可以响应中断请求，并且不会抛出InterruptedException。
//        System.out.println("main thread done.");
//    }

//    public static void main(String[] args) throws InterruptedException {
//        Thread t = new Thread(new Runnable() {
//            public void run() {
//                synchronized (Test2.class){
//                    try {
//                        wait();
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                    System.out.println("heheh");
//                }
//            }
//        });
//
//        t.start();
//        Thread.sleep(2000);
//
//        t.interrupt();
//        System.out.println("main thread done.");
//    }

//    public String name;
//    public class Task{
//
//        public void run() {
//            name = "aaa";
//        }
//    }
//
//    public static void main(String[] args) {
//        Test obj = new Test();
//        Task task = obj.new Task();
//        task.run();
//        System.out.println(obj.name);
//    }

//    class AA {
//        public void show(String str) {
//            System.out.println(str);
//        }
//    }
//
//    // 11111
//    // 100011
//    public static void main(String[] args) throws NoSuchMethodException {
//        long tick = 35L;
//        int mask = 31;
//        int idx = (int) (tick & mask);
//        System.out.println(idx);
//
//        System.out.println(String.class.getSimpleName());
//
////        Method tem = Protocol.class.getMethod("refer",int.class,URL.class);
//        Method tem = AA.class.getMethod("show", String.class);
//        String str = String.format("method is:%s", tem);
//        System.out.println(str);
//        Parameter[] arr = tem.getParameters();
//        System.out.println(arr[0].getName());
//
//        String methodReturnType = tem.getReturnType().getCanonicalName();
//        System.out.println(methodReturnType);
//    }

//    public static void main(String[] args) {
////        int  arr[] = new int[3];
//        String  arr[] = new String[3];
//        Object tem = Array.newInstance(arr.getClass().getComponentType(), 2);
////        System.out.println(((int [])tem).length);
//        System.out.println(((String [])tem).length);
//        if (((String [])tem)[0] == null){
//            System.out.println("hehe");
//        }
//        Arrays.stream((String [])tem).forEach(elem-> System.out.println("aa"+elem+"bb"));
//
//        System.out.println(arr.getClass().getSuperclass());
//
//        Object[] array = new Object[32];
//
//        Stu obj = new Stu();
//        Arrays.fill(array, obj);
//        obj.setStuName("hehe");
//        System.out.println(Arrays.asList(array));
//    }

//    public static int aa = new Random().nextInt(10);
//    public int bb;
//    public Test(int param){
//        bb = param;
//    }
//    public static void main(String[] args) {
//        Test obj = new Test(11);
//        System.out.println(obj.aa);
//        System.out.println(obj.bb);
//        Test obj2 = new Test(22);
//        System.out.println(obj2.aa);
//        System.out.println(obj2.bb);
//    }

//    public void fun1(){
//        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "Hello");
//        future.completeExceptionally(new Exception());
//        try {
//            System.out.println(future.get());
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        } catch (ExecutionException e) {
//            System.out.println("hehe");
//            e.printStackTrace();
//        }
//    }
//
//    public void fun2() throws InterruptedException {
//        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "Hello");
////        Thread.sleep(1000);
//        future.complete("World");
//        Thread.sleep(2000);
//        try {
//            System.out.println(future.get());
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        } catch (ExecutionException e) {
//            e.printStackTrace();
//        }
//    }
//
//
//    public static void main(String[] args) throws InterruptedException {
//        Test obj = new Test();
//        obj.fun2();
//
//        Method [] arr = Test.class.getMethods();
//        Arrays.stream(arr).forEach(elem-> System.out.println(elem));
//    }

//    static class BB{
//
//    }
//
//    public static void fun(){
//
//    }
//    public static void main(String[] args) throws NoSuchMethodException {
//        Object bb = new BB();
//        Object value = "aa";
//        if (bb instanceof Reference<?>){
//            System.out.println("heheh");
//        }else{
//            System.out.println("aa");
//        }
////        Class tem = Integer.TYPE;
//        Class tem = int.class;
//        System.out.println(Integer.TYPE.isPrimitive());
//        System.out.println(tem.isPrimitive());
//
//        System.out.println("========================");
//        Method mm = Test.class.getMethod("fun",null);
//        System.out.println(Modifier.isPublic(mm.getModifiers()));
//        System.out.println(Modifier.isStatic(mm.getModifiers()));
//    }

//    public static void main(String[] args) {
////        List<String> arr = new ArrayList<>();
////        arr.add("aa");
////        arr.add("bb");
////        System.out.println(arr);
////        List<String> arr2 = new ArrayList<>();
////        arr2.add("cc");
////        arr2.add("dd");
////        arr.addAll(0,arr2);
////        System.out.println(arr);
////        arr2.add("ee");
////        arr2.add("ff");
////        arr.addAll(0,arr2);
////        System.out.println(arr);
////    }

    public static void main(String[] args) {
        String a = new String("hhh");
        String b = new String("hhh");
        System.out.println(System.identityHashCode(a));
        System.out.println(System.identityHashCode(b));
        System.out.println(a.hashCode());
        System.out.println(b.hashCode());
    }
}
