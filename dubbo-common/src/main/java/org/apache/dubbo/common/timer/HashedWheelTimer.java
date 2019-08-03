/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.timer;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassHelper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
// 一个Timer对象对应一个圆盘（环形队列），不同的Timer对象有不同的圆盘， 每个圆盘的格对应不同的链表，每个链表元素就是一个任务
// 可以根据不同的业务场景来创建不同的Timer对象, Timer对象之间互不影响（除了统计Timer数量的变量之外）
public class HashedWheelTimer implements Timer {

    /**
     * may be in spi?
     */
    public static final String NAME = "hased";

    private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);

    // 活着的HashedWheelTimer个数
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    // 可以生成的HashedWheelTimer对象数量上限 （可以按不同业务场景创建Timer, 虽然类型相同, 但却是不同的对象）
    private static final int INSTANCE_COUNT_LIMIT = 64;
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    // worker任务
    private final Worker worker = new Worker();
    // worker任务所附着的线程
    private final Thread workerThread;

    private static final int WORKER_STATE_INIT = 0; // 0-初始化
    private static final int WORKER_STATE_STARTED = 1; // 1-已启动
    private static final int WORKER_STATE_SHUTDOWN = 2; // 2-关闭

    /**
     * 0 - init, 1 - started, 2 - shut down
     */
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    // Timer对象所管理的worker任务的状态
    private volatile int workerState;

    // 一个槽位代表的时间长度 (单位是纳秒)
    private final long tickDuration;
    // 一个圆轮所有bucket(槽位/格数)的集合
    private final HashedWheelBucket[] wheel;
    // 二进制位都是1的值
    private final int mask;
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    // 暂时估计是存放原始的timeout对象
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();
    // state值是1 (已取消状态) 的HashedWheelTimeout对象集合
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();
    // 等待执行的timeout对象数量
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    // 等待执行的timeout对象的最大数量
    private final long maxPendingTimeouts;

    // worker任务开始执行的时间, 值是个时间戳
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory      a {@link ThreadFactory} that creates a
     *                           background {@link Thread} which is dedicated to
     *                           {@link TimerTask} execution.
     * @param tickDuration       the duration between tick
     * @param unit               the time unit of the {@code tickDuration}
     * @param ticksPerWheel      the size of the wheel
     * @param maxPendingTimeouts The maximum number of pending timeouts after which call to
     *                           {@code newTimeout} will result in
     *                           {@link java.util.concurrent.RejectedExecutionException}
     *                           being thrown. No maximum pending timeouts limit is assumed if
     *                           this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    // 生成一个HashedWheelTimer对象, 并验证参数和初始化一些成员变量
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel,
            long maxPendingTimeouts) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        // 槽位的集合
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        // Convert tickDuration to nanos.
        this.tickDuration = unit.toNanos(tickDuration);

        // Prevent overflow.
        // 防止溢出, 一个槽位代表的时间长度, 取值区间在(0, Long.MAX_VALUE / 槽位数量)
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        // 将worker和线程合一起, workerThread就可以直接运行了
        workerThread = threadFactory.newThread(worker);

        this.maxPendingTimeouts = maxPendingTimeouts;

        // 限制timer的实例数，因为每个timer都会起一个线程, 过多会影响性能
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            // 生成的Timer对象太多, 则log
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            // HashedWheelTimer对象的workerState值, 若原来不是2, 则设置成2, 并且将INSTANCE_COUNTER值减1
            // 执行WORKER_STATE_UPDATER.getAndSet()会返回更新前的值
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    // 创建槽位, 并返回槽位的集合
    // 参数ticksPerWheel是槽位数(刻度数/格数)
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        // 将参数ticksPerWheel转成2的次幂值 (ticksPerWheel是刻度数)
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        // 槽位的集合, 每个槽位对应一个链表, 链表元素是任务
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    // 这个函数就是将参数ticksPerWheel转成2的次幂值,(是上限的次幂)
    // 例如: ticksPerWheel=7 返回8, ticksPerWheel=129, 返回256
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        // 或运算 和 无符号右移
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    // 启动当前Timer对象中的worker任务线程,
    // 若Timer的成员变量startTime没有被赋值, 则等待woker任务给startTime赋值
    // 若是多次调用Timer对象的newTimeout(), 则startTime肯定是已经有值了. 当多次调用时, 还能根据当前worker任务的状态来决定是否有动作
    public void start() {
        // 取当前Timer对象的workerState属性值
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                // 若当前Timer对象的workerState属性值是0, 更新为1, 启动worker线程
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    // 这个是另起一个线程执行worker任务
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                // 若woker线程已经启动, 则保持, 无操作
                break;
            case WORKER_STATE_SHUTDOWN:
                // 任务一旦停止就不能再启动
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.
        while (startTime == 0) {
            try {
                // 等待startTime被赋值 (woker任务给startTime赋值后, 这里再继续向下执行)
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    // 停止当前Timer对象中的worker线程, 返回当前Timer中还没有处理的Timeout任务
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }

        // 当前对象的workerState值不是1, 进if
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            // 当前对象的workerState值不是2, 进if
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                // 活着的HashedWheelTimer个数-1
                INSTANCE_COUNTER.decrementAndGet();
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            // while循环持续中断worker线程直到它醒悟它该结束了(有可能被一些耗时的操作耽误了)
            // 通过isAlive判断worker线程是否已经结束
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            // 减少Timer实例个数
            INSTANCE_COUNTER.decrementAndGet();
        }
        // 返回该Timer中还没有处理的Timeout任务
        return worker.unprocessedTimeouts();
    }

    @Override
    // 当前对象的workerThread线程是否已停止 (通过成员变量workerState值来判断)
    public boolean isStop() {
        return WORKER_STATE_SHUTDOWN == WORKER_STATE_UPDATER.get(this);
    }

    @Override


    /**
     * 1.新建一个timeout对象, 并放到当前timer对象的timeouts中
     * 2.启动当前timer对象中worker任务所在的线程, 让worker任务去消费圆盘中的任务 (worker取出圆盘中的timeout对象, 执行timeout对象中的task属性的run方法)
     * 大体上来说就是: timer对象会使用newTimeout()生产timeout对象, worker任务线程就去消费这些timeout对象
     *
     *
     * 新建一个timeout对象时, 会关联上timer对象和task对象, 即, 给它的成员变量timer, task, deadline 赋上值
     * @param task 一个要执行的任务
     * @param delay 等待delay时间后, 执行一次task.
     *              例如: timer.newTimeout(new PrintTask(), 5, TimeUnit.SECONDS); 表示等待5秒之后执行一次PrintTask
     * @param unit
     * @return 返回一个timeout对象, 它的成员变量timer，task，deadline 都已给出值
     */
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        // 等待执行的timeout对象数量+1
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            // 若超出最大等待数量, 则抛出异常
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        // 启动当前Timer对象中的worker任务线程
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.

        // 把timeout对象加到Timer的成员变量timeouts中

        // timeout的最晚的开始执行时间（这个时间是个相对时间, 相对于startTime的延迟时间），startTime是worker线程开始执行的时间
        // deadline = 当前时间 + 延迟时间 - worker任务开始执行的时间
        // 相对于startTime这个时间点, timeout最晚可以延迟deadline时间执行（startTime是worker任务开始执行的时间戳, deadline是相对的时间块）
        // 例如: 当startTime="20190728050530" 这个时间点时, timeout可以相对它延迟5s执行
        // 例如: startTime是3点, 当前时间是4点, delay是1小时, 那deadline值是2小时, 也就是timeout对象要求在当前时间之后延迟1小时执行, 而实际上该timeout对象是在worker任务开始那一刻的2个小时之后执行
        // 所以说 这个deadline是相对于startTime的一个非时间戳值（浮动值）
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        // 防止加法溢出
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        // timeout对象添加到Timer对象的timeouts集合中, 后面的worker任务会将timeouts集合中的元素添加到bucket，这样worker就会执行它
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    // log 生成的HashedWheelTimer对象太多
    private static void reportTooManyInstances() {
        String resourceType = ClassHelper.simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    private final class Worker implements Runnable {
        // 存放还没有处理的Timeout任务
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        // 走一步 (就是一个圆盘, 圆盘上分很多个槽位, 走一步意思就是从一个槽位到下一个槽位)
        // tick可以理解为一个计数器, 换一个槽位值加一, tick & mask 得到当前的槽位序号
        // tick默认值是0, 从0开始计数, 值表示当前在哪个槽位上
        private long tick;

        @Override
        // 依次执行圆盘里每个格对应的链表中的任务, 每次tick+1就换一个格执行该格里面的任务
        // 只要Timer对象的workerState=1（启动状态, 表示该Timer下的worker在工作），这个过程就会一直进行
        // 这也说明Timer对象可以通过workerState属性值，对worker的停止进行控制
        public void run() {
            // Initialize the startTime.
            // worker任务开始执行的时间
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start().
            // startTime被赋值后, 这里就通知其他线程继续执行
            startTimeInitialized.countDown();

            do {
                // 等待, 直到第(tick +1)格的时间到了, 返回第(tick +1)个格对应的总的时间
                // 例如: 若第一个格子对应时间是5s, 那么第二个格子对应的总时间就是10s... 第五个格子对应的总时间就是25s （每个timeout对象里的deadline值也是这种相对值, 而不是时间戳）
                // 该函数返回了, 就表示第(tick +1)格的链表中的任务都可以执行了
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    // tick所对应的bucket的序号
                    int idx = (int) (tick & mask);
                    // 将取消状态的timeout对象从链表中移除
                    processCancelledTasks();
                    HashedWheelBucket bucket =
                            wheel[idx];
                    // 将Timer对象中的timeouts 逐个添加到相应的Bucket对象里
                    transferTimeoutsToBuckets();
                    // 执行bucket中的任务
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
                // 只要父类Timer对象的workerState=1, 那这个循环就一直执行
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);


            // 到了这里，说明worker的状态已经不是started了，到了shutdown
            // 那就得善后了，遍历所有的bucket，将还没来得及处理的任务全部放到unprocessedTimeouts中
            // Fill the unprocessedTimeouts so we can return them from stop() method.
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            // 处理timeouts中的Timeout对象 (这些对象是还没有添加到bucket中的)
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                // 把没有取消的任务添加到unprocessedTimeouts中
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            // 将cancelledTimeouts队列中的timeout对象从 timeout对象本身所在的链表中移除
            processCancelledTasks();
        }

        // 将HashedWheelTimer对象中的timeouts 逐个添加到Bucket对象里
        // 需要注意的是一次最多分发100000个任务到对应的bucket中，这是为了防止worker没法做其他的事情
        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                // state=1的timeout对象不进bucket (timeout是已取消状态)
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }

                // timeout一共需要走的步数
                long calculated = timeout.deadline / tickDuration;
                // 轮数 (需要几轮走完, calculated - tick是还剩的步数, tick是已经走的步数)
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                // 取max的意思是: 如果这个任务我们取晚了，就将它加入到当前tick值对应的桶中 (这时的calculated<tick)
                final long ticks = Math.max(calculated, tick);
                // 确定该timeout应该放的位置 (放在哪个bucket里)
                int stopIndex = (int) (ticks & mask);

                HashedWheelBucket bucket = wheel[stopIndex];
                // 把timeout放到bucket对应的链表里
                bucket.addTimeout(timeout);
            }
        }

        // 处理cancelledTimeouts队列中的所有timeout对象, 将这些timeout对象从自己所在的链表中移除
        private void processCancelledTasks() {
            for (; ; ) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    // 从链表中移除timeout对象
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         *
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */

        /**
         * 等待, 直到第(tick +1)格的时间到了（该格的时间到了，就表示该格中的timeout对应的task对象可以执行了）
         * 返回worker任务已运行的时间currentTime (也就是第(tick +1)个格对应的总的时间),
         * 返回该格对应的总时间
         * @return
         */
        // 这个函数会一直sleep,直到第(tick +1)个格对应的总的时间被耗费掉了, 才返回
        // 返回的不是时间戳, 是两个时间戳的差值
        // 例如: tick=3, 则tick+1=4 , 若每个格对应的时间是5s, 第(tick +1)个格对应的总的时间是20s,
        // 该程序返回时, worker任务已运行了20s, 这就表示第4个格的时间到了, 第4个格的链表中的任务可以运行了。
        private long waitForNextTick() {
            // 第(tick +1)个格对应的总的时间
            long deadline = tickDuration * (tick + 1);

            for (; ; ) {
                // worker任务已运行的时间, 不是绝对的时间戳的值, 是两个时间戳的差值, 是相对值
                final long currentTime = System.nanoTime() - startTime;
                // 需要睡眠的毫秒数
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                //  <=0 表示不需要睡眠, 直接返回worker任务已运行的时间currentTime (也就是第(tick +1)个格对应的总的时间)
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }
                if (isWindows()) {
                    // todo 作用？？ 难道是为了sleepTimeMs最后是个整数？？
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                // 不到运行时间就睡
                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    // 被中断时, 若外层类HashedWheelTimer的对象的成员变量workerState=2, 则返回最小值
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        // 返回还没有处理的Timeout任务
        // (包括Timer的成员变量wheel中所有bucket中的尚未处理的timeout 和 Timer的成员变量timeouts中尚未处理的timeout)
        Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout {

        // HashedWheelTimeout的状态
        private static final int ST_INIT = 0; // 初始
        private static final int ST_CANCELLED = 1; // 已取消
        private static final int ST_EXPIRED = 2; // 已到期
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        // 创建当前timeout对象的timer对象
        private final HashedWheelTimer timer;
        // 要执行的任务
        private final TimerTask task;
        // 执行任务的最晚时间, 是相对于timer对象的startTime的一个变动值, 例如: 在startTime之后的5秒执行 (startTime是个时间戳, deadline是个相对值)
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        private volatile int state = ST_INIT;

        /**
         * RemainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
         * HashedWheelTimeout will be added to the correct HashedWheelBucket.
         */
        // 在HashedWheelTimeout对象加入到HashedWheelBucket之前, remainingRounds值就已经设置好了
        // 这个remainingRounds值意思就是还有几圈, 我才能执行
        long remainingRounds;

        /**
         * This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
         * As only the workerThread will act on it there is no need for synchronization / volatile.
         */
        // HashedWheelTimeout是双向链表
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        /**
         * The bucket to which the timeout was added
         */
        // 当前Timeout对象所添加到的Bucket
        HashedWheelBucket bucket;

        // 把当前timeout对象和它对应的timer对象, task对象关联上
        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        // 将当前Timeout对象的state值由0更新成1 （从初始状态 更新成 已取消状态）, 并加入到timer对象的cancelledTimeouts中
        public boolean cancel() {
            // 这里只需要更新timeout对象的state值就可以了, 不需要有其他操作
            // 因为当tick值每变一次的时候, 这个timeout对象会被从bucket中删掉
            // only update the state it will be removed from HashedWheelBucket on next tick.
            // 若当前Timeout对象的state值若是0, 则更新成1, 成功则返回true
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            // 将当前Timeout对象添加到Timer对象的cancelledTimeouts变量中, tick值每变一次, 都会处理一次cancelledTimeouts中的元素
            timer.cancelledTimeouts.add(this);
            return true;
        }

        // 将当前timeout对象从它所属的bucket对象中移除
        void remove() {
            // 该timeout对象所在的bucket对象
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                // 移除链表中的一个元素 (从bucket对象所指向的链表中移除)
                bucket.remove(this);
            } else {
                // 还在等待执行的timeout对象数量-1
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        // 当前Timeout对象的值若是expected, 则更新成state, 返回true. 若不是则无动作,返回false
        public boolean compareAndSetState(int expected, int state) {
            // true则更新成功, false没有更新
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        // 当前timeout对象的state值是否为取消状态 ( ST_CANCELLED = 1 已取消)
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        // 是否到期 (通过判断当前timeout对象的state属性值是否为2)
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        /**
         * 把当前Timeout对象的state属性值从0改为2, 并执行该timeout对象关联的TimerTask任务
         * （state从初始状态改为已到期状态）
         */
        public void expire() {
            // 把当前Timeout对象的state属性值从0改为2, 若当前state值不是0, 就直接返回
            // 避开已取消和已到期的任务
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                // 执行task对象的run方法 (从代码来看, 这段代码是在worker线程里执行)
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        // 返回的字符串是 "当前timeout对象 还有多久开始执行/多少ns之前已经执行/正在执行/已经被取消, 具体任务对象是***"
        public String toString() {
            final long currentTime = System.nanoTime();
            // 还有多久执行
            long remaining = deadline - currentTime + timer.startTime;
            String simpleClassName = ClassHelper.simpleClassName(this.getClass());

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName)
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    private static final class HashedWheelBucket {

        /**
         * Used for the linked-list datastructure
         */
        // 指向链表头, 尾
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         */
        // 在链表尾部新增一个元素 (链表元素是HashedWheelTimeout类型)
        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            // timeout中的bucket变量值是当前对象
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                // 链表尾部新增元素, 链表第一个元素的prev是空(即 不需要赋值) , 链表最后一个元素的 next是空 (即 不需要赋值)
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         */
        // 遍历当前bucket对应的链表, 依次判断每个元素是否到期, 到期就从链表中移除并执行任务
        void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            // 遍历当前bucket对应的链表, 依次处理每个timeout元素
            // (1)看是否到期, 到期就从链表中移除并执行任务; (2)移除状态是已取消的元素; (3)若圈数>0, 则圈数-1
            while (timeout != null) {
                // 暂存timeout的下一个元素
                HashedWheelTimeout next = timeout.next;
                // timeout的圈数为0了, 该timeout中的task才能执行
                if (timeout.remainingRounds <= 0) {
                    // 移除timeout, 返回timeout的下一个元素
                    next = remove(timeout);
                    // 两个deadline都是相对于Timer的startTime的一个变动值
                    // 若if成立, 则表示该timeout对象到期, 可以执行了
                    if (timeout.deadline <= deadline) {
                        // 设置当前timeout对象的state属性值为2, 并执行任务
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    // 圈数>0, 则圈数-1
                    timeout.remainingRounds--;
                }
                timeout = next;
            }
        }

        /**
         * 移除当前bucket对象对应的链表中的一个元素, Timer中还在等待的timeout对象数量-1 (没什么可看的, 就是那种老套的指针指来指去的操作)
         * @param timeout 要移除的元素
         * @return 参数timeout的下一个元素(可以为null)
         */
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            // timeout的下一个元素
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            // 还在等待的timeout对象数量-1
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        // 将当前Bucket对象中state=0 的Timeout对象放入参数set中, 也就是还没有处理的放到set里
        // (Timeout对象的成员变量state=1和state=2),  0 初始, 1 已取消, 2 已到期
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        // 返回并移除链表中的第一个元素,(该链表是当前Bucket对象指向的链表, 每个Bucket对象都会指向一个链表)
        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            // 链表中没有元素返回null
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                // 链表只有一个元素时
                tail = this.head = null;
            } else {
                // 链表有多个元素, 将链表中新晋的第一个元素的prev置为空
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }

    // 是否为windows系统
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
    }
}
