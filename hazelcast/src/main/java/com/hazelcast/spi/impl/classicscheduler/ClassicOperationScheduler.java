/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl.classicscheduler;

import com.hazelcast.instance.GroupProperties;
import com.hazelcast.instance.HazelcastThreadGroup;
import com.hazelcast.instance.NodeExtension;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.NIOThread;
import com.hazelcast.nio.Packet;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.impl.ResponsePacketHandler;
import com.hazelcast.spi.impl.OperationHandler;
import com.hazelcast.spi.impl.OperationHandlerFactory;
import com.hazelcast.spi.impl.OperationScheduler;
import com.hazelcast.spi.impl.PartitionSpecificRunnable;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link com.hazelcast.spi.impl.OperationScheduler} that scheduled:
 * <ol>
 * <li>partition specific operations to a specific partition-operation-thread (using a mod on the partition-id)</li>
 * <li> non specific operations to generic-operation-threads</li>
 * </ol>
 * The actual processing of the 'task' that is scheduled, is forwarded to the {@link com.hazelcast.spi.impl.OperationHandler}. So
 * this class is purely responsible for assigning a 'task' to a particular thread.
 * <p/>
 * The {@link #execute(Object, int, boolean)} accepts an Object instead of a runnable to prevent needing to
 * create wrapper runnables around tasks. This is done to reduce the amount of object litter and therefor
 * reduce pressure on the gc.
 * <p/>
 * There are 2 category of operation threads:
 * <ol>
 * <li>partition specific operation threads: these threads are responsible for executing e.g. a map.put.
 * Operations for the same partition, always end up in the same thread.
 * </li>
 * <li>
 * generic operation threads: these threads are responsible for executing operations that are not
 * specific to a partition. E.g. a heart beat.
 * </li>
 * </ol>
 */
public final class ClassicOperationScheduler implements OperationScheduler {

    public static final int TERMINATION_TIMEOUT_SECONDS = 3;

    private final ILogger logger;

    //all operations for specific partitions will be executed on these threads, .e.g map.put(key,value).
    private final PartitionOperationThread[] partitionOperationThreads;
    private final OperationHandler[] partitionOperationHandlers;

    //the generic workqueues are shared between all generic operation threads, so that work can be stolen
    //and a task gets processed as quickly as possible.
    private final BlockingQueue genericWorkQueue = new LinkedBlockingQueue();
    private final ConcurrentLinkedQueue genericPriorityWorkQueue = new ConcurrentLinkedQueue();
    //all operations that are not specific for a partition will be executed here, e.g heartbeat or map.size
    private final GenericOperationThread[] genericOperationThreads;
    private final OperationHandler[] genericOperationHandlers;

    private final ResponseThread responseThread;
    private final ResponsePacketHandler responsePacketHandler;
    private final Address thisAddress;
    private final NodeExtension nodeExtension;
    private final HazelcastThreadGroup threadGroup;
    private final OperationHandler adHocOperationHandler;

    public ClassicOperationScheduler(GroupProperties properties,
                                     LoggingService loggerService,
                                     Address thisAddress,
                                     OperationHandlerFactory operationHandlerFactory,
                                     ResponsePacketHandler responsePacketHandler,
                                     HazelcastThreadGroup hazelcastThreadGroup,
                                     NodeExtension nodeExtension) {
        this.thisAddress = thisAddress;
        this.nodeExtension = nodeExtension;
        this.threadGroup = hazelcastThreadGroup;
        this.logger = loggerService.getLogger(ClassicOperationScheduler.class);
        this.responsePacketHandler = responsePacketHandler;

        this.adHocOperationHandler = operationHandlerFactory.createAdHocOperationHandler();

        this.partitionOperationHandlers = initPartitionOperationHandlers(properties, operationHandlerFactory);
        this.partitionOperationThreads = initPartitionThreads(properties);

        this.genericOperationHandlers = initGenericOperationHandlers(properties, operationHandlerFactory);
        this.genericOperationThreads = initGenericThreads();

        this.responseThread = initResponseThread();

        logger.info("Starting with " + genericOperationThreads.length + " generic operation threads and "
                + partitionOperationThreads.length + " partition operation threads.");
    }

    private OperationHandler[] initGenericOperationHandlers(GroupProperties properties, OperationHandlerFactory handlerFactory) {
        int genericThreadCount = properties.GENERIC_OPERATION_THREAD_COUNT.getInteger();
        if (genericThreadCount <= 0) {
            // default generic operation thread count
            int coreSize = Runtime.getRuntime().availableProcessors();
            genericThreadCount = Math.max(2, coreSize / 2);
        }

        OperationHandler[] operationHandlers = new OperationHandler[genericThreadCount];
        for (int partitionId = 0; partitionId < operationHandlers.length; partitionId++) {
            operationHandlers[partitionId] = handlerFactory.createGenericOperationHandler();
        }
        return operationHandlers;
    }

    private OperationHandler[] initPartitionOperationHandlers(GroupProperties properties,
                                                              OperationHandlerFactory handlerFactory) {
        OperationHandler[] operationHandlers = new OperationHandler[properties.PARTITION_COUNT.getInteger()];
        for (int partitionId = 0; partitionId < operationHandlers.length; partitionId++) {
            operationHandlers[partitionId] = handlerFactory.createPartitionHandler(partitionId);
        }
        return operationHandlers;
    }

    private ResponseThread initResponseThread() {
        ResponseThread thread = new ResponseThread(threadGroup, logger, responsePacketHandler);
        thread.start();
        return thread;
    }

    private PartitionOperationThread[] initPartitionThreads(GroupProperties properties) {
        int threadCount = properties.PARTITION_OPERATION_THREAD_COUNT.getInteger();
        if (threadCount <= 0) {
            // default partition operation thread count
            int coreSize = Runtime.getRuntime().availableProcessors();
            threadCount = Math.max(2, coreSize);
        }

        PartitionOperationThread[] threads = new PartitionOperationThread[threadCount];
        for (int threadId = 0; threadId < threads.length; threadId++) {
            String threadName = threadGroup.getThreadPoolNamePrefix("partition-operation") + threadId;
            LinkedBlockingQueue workQueue = new LinkedBlockingQueue();
            ConcurrentLinkedQueue priorityWorkQueue = new ConcurrentLinkedQueue();
            PartitionOperationThread operationThread = new PartitionOperationThread(
                    threadName, threadId, workQueue, priorityWorkQueue, logger,
                    threadGroup, nodeExtension, partitionOperationHandlers);
            threads[threadId] = operationThread;
            operationThread.start();
        }

        return threads;
    }

    private GenericOperationThread[] initGenericThreads() {
        // we created as many generic operation handlers, as there are generic threads.
        int threadCount = genericOperationHandlers.length;
        GenericOperationThread[] threads = new GenericOperationThread[threadCount];
        for (int threadId = 0; threadId < threads.length; threadId++) {
            String threadName = threadGroup.getThreadPoolNamePrefix("generic-operation") + threadId;
            OperationHandler operationHandler = genericOperationHandlers[threadId];
            GenericOperationThread operationThread = new GenericOperationThread(
                    threadName, threadId, genericWorkQueue, genericPriorityWorkQueue,
                    logger, threadGroup, nodeExtension, operationHandler);
            threads[threadId] = operationThread;
            operationThread.start();
        }

        return threads;
    }

    @Override
    public OperationHandler[] getPartitionOperationHandlers() {
        return partitionOperationHandlers;
    }

    @Override
    public OperationHandler[] getGenericOperationHandlers() {
        return genericOperationHandlers;
    }


    @Override
    public boolean isAllowedToRunInCurrentThread(Operation op) {
        if (op == null) {
            throw new NullPointerException("op can't be null");
        }

        Thread currentThread = Thread.currentThread();

        // IO threads are not allowed to run any operation
        if (currentThread instanceof NIOThread) {
            return false;
        }

        int partitionId = op.getPartitionId();
        //todo: do we want to allow non partition specific tasks to be run on a partitionSpecific operation thread?
        if (partitionId < 0) {
            return true;
        }

        //we are only allowed to execute partition aware actions on an OperationThread.
        if (!(currentThread instanceof PartitionOperationThread)) {
            return false;
        }

        PartitionOperationThread partitionThread = (PartitionOperationThread) currentThread;

        //so it is an partition operation thread, now we need to make sure that this operation thread is allowed
        //to execute operations for this particular partitionId.
        return toPartitionThreadIndex(partitionId) == partitionThread.threadId;
    }

    @Override
    public boolean isOperationThread() {
        return Thread.currentThread() instanceof OperationThread;
    }

    @Override
    public boolean isInvocationAllowedFromCurrentThread(Operation op) {
        if (op == null) {
            throw new NullPointerException("op can't be null");
        }

        Thread currentThread = Thread.currentThread();

        // IO threads are not allowed to run any operation
        if (currentThread instanceof NIOThread) {
            return false;
        }

        if (op.getPartitionId() < 0) {
            return true;
        }

        // we are allowed to invoke from non PartitionOperationThreads (including GenericOperationThread).
        if (!(currentThread instanceof PartitionOperationThread)) {
            return true;
        }

        // we are only allowed to invoke from a PartitionOperationThread if the operation belongs to that
        // PartitionOperationThread.
        PartitionOperationThread partitionThread = (PartitionOperationThread) currentThread;
        return toPartitionThreadIndex(op.getPartitionId()) == partitionThread.threadId;
    }

    @Override
    public int getRunningOperationCount() {
        int result = 0;
        for (OperationHandler handler : partitionOperationHandlers) {
            if (handler.currentTask() != null) {
                result++;
            }
        }
        for (OperationHandler handler : genericOperationHandlers) {
            if (handler.currentTask() != null) {
                result++;
            }
        }
        return result;
    }

    @Override
    public int getOperationExecutorQueueSize() {
        int size = 0;

        for (PartitionOperationThread t : partitionOperationThreads) {
            size += t.workQueue.size();
        }

        size += genericWorkQueue.size();

        return size;
    }

    @Override
    public int getPriorityOperationExecutorQueueSize() {
        int size = 0;

        for (PartitionOperationThread t : partitionOperationThreads) {
            size += t.priorityWorkQueue.size();
        }

        size += genericPriorityWorkQueue.size();
        return size;
    }

    @Override
    public int getResponseQueueSize() {
        return responseThread.workQueue.size();
    }

    @Override
    public int getPartitionOperationThreadCount() {
        return partitionOperationThreads.length;
    }

    @Override
    public int getGenericOperationThreadCount() {
        return genericOperationThreads.length;
    }

    @Override
    public void execute(Operation op) {
        if (op == null) {
            throw new NullPointerException("op can't be null");
        }
        execute(op, op.getPartitionId(), op.isUrgent());
    }

    @Override
    public void execute(PartitionSpecificRunnable task) {
        if (task == null) {
            throw new NullPointerException("task can't be null");
        }
        execute(task, task.getPartitionId(), false);
    }

    @Override
    public void execute(Packet packet) {
        if (packet == null) {
            throw new NullPointerException("packet can't be null");
        }

        if (!packet.isHeaderSet(Packet.HEADER_OP)) {
            throw new IllegalStateException("Packet " + packet + " doesn't have Packet.HEADER_OP set");
        }

        if (packet.isHeaderSet(Packet.HEADER_RESPONSE)) {
            //it is an response packet.
            responseThread.workQueue.add(packet);
        } else {
            //it is an must be an operation packet
            int partitionId = packet.getPartitionId();
            boolean hasPriority = packet.isUrgent();
            execute(packet, partitionId, hasPriority);
        }
    }

    @Override
    public void runOperationOnCallingThread(Operation op) {
        if (op == null) {
            throw new NullPointerException("op can't be null");
        }

        if (!isAllowedToRunInCurrentThread(op)) {
            throw new IllegalThreadStateException("Operation: " + op + " cannot be run in current thread! -> "
                    + Thread.currentThread());
        }

        //TODO: We need to find the correct operation handler.
        OperationHandler operationHandler = getCurrentThreadOperationHandler();
        operationHandler.process(op);
    }

    public OperationHandler getCurrentThreadOperationHandler() {
        Thread thread = Thread.currentThread();
        if (!(thread instanceof OperationThread)) {
            return adHocOperationHandler;
        }

        OperationThread operationThread = (OperationThread) thread;
        return operationThread.getCurrentOperationHandler();
    }

    private void execute(Object task, int partitionId, boolean priority) {
        BlockingQueue workQueue;
        Queue priorityWorkQueue;
        if (partitionId < 0) {
            workQueue = genericWorkQueue;
            priorityWorkQueue = genericPriorityWorkQueue;
        } else {
            OperationThread partitionOperationThread = partitionOperationThreads[toPartitionThreadIndex(partitionId)];
            workQueue = partitionOperationThread.workQueue;
            priorityWorkQueue = partitionOperationThread.priorityWorkQueue;
        }

        if (priority) {
            offerWork(priorityWorkQueue, task);
            offerWork(workQueue, OperationThread.TRIGGER_TASK);
        } else {
            offerWork(workQueue, task);
        }
    }

    private void offerWork(Queue queue, Object task) {
        //in 3.3 we are going to apply backpressure on overload and then we are going to do something
        //with the return values of the offer methods.
        //Currently the queues are all unbound, so this can't happen anyway.


        if (task instanceof Runnable && !(task instanceof PartitionSpecificRunnable)) {
            throw new RuntimeException();
        }

        boolean offer = queue.offer(task);
        if (!offer) {
            logger.severe("Failed to offer " + task + " to ClassicOperationScheduler due to overload");
        }
    }

    private int toPartitionThreadIndex(int partitionId) {
        return partitionId % partitionOperationThreads.length;
    }

    @Override
    public void shutdown() {
        responseThread.shutdown();
        shutdownAll(partitionOperationThreads);
        shutdownAll(genericOperationThreads);
        awaitTermination(partitionOperationThreads);
        awaitTermination(genericOperationThreads);
    }

    private static void shutdownAll(OperationThread[] operationThreads) {
        for (OperationThread thread : operationThreads) {
            thread.shutdown();
        }
    }

    private static void awaitTermination(OperationThread[] operationThreads) {
        for (OperationThread thread : operationThreads) {
            try {
                thread.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void dumpPerformanceMetrics(StringBuffer sb) {
        for (int k = 0; k < partitionOperationThreads.length; k++) {
            OperationThread operationThread = partitionOperationThreads[k];
            sb.append(operationThread.getName())
                    .append(" processedCount=").append(operationThread.processedCount)
                    .append(" pendingCount=").append(operationThread.workQueue.size())
                    .append('\n');
        }
        sb.append("pending generic operations ").append(genericWorkQueue.size()).append('\n');
        for (int k = 0; k < genericOperationThreads.length; k++) {
            OperationThread operationThread = genericOperationThreads[k];
            sb.append(operationThread.getName())
                    .append(" processedCount=").append(operationThread.processedCount).append('\n');
        }
        sb.append(responseThread.getName())
                .append(" processedCount=").append(responseThread.processedResponses)
                .append(" pendingCount=").append(responseThread.workQueue.size()).append('\n');
    }

    @Override
    public String toString() {
        return "ClassicOperationScheduler{"
                + "node=" + thisAddress
                + '}';
    }
}
