package org.apache.dubbo.common.utils;

import java.util.concurrent.*;

class TemPolicy implements RejectedExecutionHandler {

    public TemPolicy() {
    }

    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        System.out.println("in TemPolicy: " + Thread.currentThread().getName());
        System.out.println("in TemPolicy: " + Thread.currentThread().toString());
        throw new RejectedExecutionException("Task " + r.toString() +
                " rejected from " +
                e.toString());
    }
}

public class Test2 {
//    public void setPropertyValue(Object o, String n, Object v) {
//        org.apache.dubbo.common.utils.Stu w;
//        try {
//            w = ((org.apache.dubbo.common.utils.Stu) $1);
//        } catch (Throwable e) {
//            throw new IllegalArgumentException(e);
//        }
//    }
//
//    public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException {
//        org.apache.dubbo.common.utils.Stu w;
//        try {
//            w = ((org.apache.dubbo.common.utils.Stu) $1);
//        } catch (Throwable e) {
//            throw new IllegalArgumentException(e);
//        }
//        try {
//            if ("getStuName".equals($2) && $3.length == 0) {
//                return ($w) w.getStuName();
//            }
//            if ("setStuName".equals($2) && $3.length == 1) {
//                w.setStuName((java.lang.String) $4[0]);
//                return null;
//            }
//            if ("getAge".equals($2) && $3.length == 0) {
//                return ($w) w.getAge();
//            }
//            if ("setAge".equals($2) && $3.length == 1) {
//                w.setAge((java.lang.Integer) $4[0]);
//                return null;
//            }
//        } catch (Throwable e) {
//            throw new java.lang.reflect.InvocationTargetException(e);
//        }
//        throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \"" + $2 + "\" in class org.apache.dubbo.common.utils.Stu.");
//    }


    public static void main(String[] args) throws ExecutionException, InterruptedException {
        //线程池单个线程，线程池队列元素个数为1
        ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1,
                1L, TimeUnit.MINUTES,
                new ArrayBlockingQueue<>(1),
//                new ThreadPoolExecutor.DiscardPolicy());
//                new ThreadPoolExecutor.AbortPolicy());
                new TemPolicy());

        //(1)添加任务one
        Future futureOne = executorService.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println("start runable one");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        //(2)添加任务two
        Future futureTwo = executorService.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println("start runable two");
            }
        });

        //(3)添加任务three
        Future futureThree = null;
        try {
            futureThree = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    System.out.println("start runable three");
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("add task 3=====");
//            System.out.println(e.getLocalizedMessage());
        }

        System.out.println("task one finish " + futureOne.get());//(5)等待任务one执行完毕
        System.out.println("task two finish " + futureTwo.get());//(6)等待任务two执行完毕
        System.out.println("task three finish " + (futureThree == null ? null : futureThree.get()));// (7)等待任务three执行完毕

        executorService.shutdown();//(8)关闭线程池，阻塞直到所有任务执行完毕
    }
}
