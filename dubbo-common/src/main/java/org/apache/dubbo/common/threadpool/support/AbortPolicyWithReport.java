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
package org.apache.dubbo.common.threadpool.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.JVMUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ExecutorService;

/**
 * Abort Policy.
 * Log warn info when abort.
 */
public class AbortPolicyWithReport extends ThreadPoolExecutor.AbortPolicy {

    protected static final Logger logger = LoggerFactory.getLogger(AbortPolicyWithReport.class);

    private final String threadName;

    private final URL url;

    private static volatile long lastPrintTime = 0;

    private static Semaphore guard = new Semaphore(1);

    public AbortPolicyWithReport(String threadName, URL url) {
        this.threadName = threadName;
        this.url = url;
    }

    @Override
    // 线程池拒绝执行任务时, 会先log下当前的线程池情况, 再把JVM中具体的线程信息输出到文件中
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        // 大概意思: 线程池被耗尽, 列出: 当前被拒绝执行的线程名字, 当前线程池的各种参数
        String msg = String.format("Thread pool is EXHAUSTED!" +
                        " Thread Name: %s, Pool Size: %d (active: %d, core: %d, max: %d, largest: %d), Task: %d (completed: %d)," +
                        " Executor status:(isShutdown:%s, isTerminated:%s, isTerminating:%s), in %s://%s:%d!",
                threadName, e.getPoolSize(), e.getActiveCount(), e.getCorePoolSize(), e.getMaximumPoolSize(), e.getLargestPoolSize(),
                e.getTaskCount(), e.getCompletedTaskCount(), e.isShutdown(), e.isTerminated(), e.isTerminating(),
                url.getProtocol(), url.getIp(), url.getPort());
        logger.warn(msg);
        dumpJStack();
        throw new RejectedExecutionException(msg);
    }

    // 把类似jstack命令的结果 输出到文件, 每10分钟输出一次

    /**
     *  把类似jstack命令的结果 输出到文件
     *  若本方法被多次调用, 则限制每10分钟才能被调用一次
     *
     */
    private void dumpJStack() {
        long now = System.currentTimeMillis();

        //dump every 10 minutes
        // 限制只能每10分钟输出一次
        if (now - lastPrintTime < 10 * 60 * 1000) {
            return;
        }
        // 在多线程情况下, 这里保证只有一个线程能继续向下执行, 其他的返回 (只有一个线程可以写文件)
        // 获取信号量
        if (!guard.tryAcquire()) {
            return;
        }

        // 因为要写文件, 为了不影响性能, 所以新开线程池去写文件
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            // 取url的参数"dump.directory"对应的值, 若没有则取系统环境中"user.home"对应的值
            // 就是个文件存放的路径
            String dumpPath = url.getParameter(Constants.DUMP_DIRECTORY, System.getProperty("user.home"));

            SimpleDateFormat sdf;

            // 获取当前操作系统名,  就是为了后面生成一个日期字符串
            // 例如: "windows 7"
            String os = System.getProperty("os.name").toLowerCase();

            // window system don't support ":" in file name
            if (os.contains("win")) {
                sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            } else {
                sdf = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
            }

            // 日志文件名的后缀
            String dateStr = sdf.format(new Date());
            //try-with-resources
            try (FileOutputStream jStackStream = new FileOutputStream(new File(dumpPath, "Dubbo_JStack.log" + "." + dateStr))) {
                JVMUtil.jstack(jStackStream);
            } catch (Throwable t) {
                logger.error("dump jStack error", t);
            } finally {
                // 释放信号量
                guard.release();
            }
            lastPrintTime = System.currentTimeMillis();
        });
        //must shutdown thread pool ,if not will lead to OOM
        pool.shutdown();

    }

}
