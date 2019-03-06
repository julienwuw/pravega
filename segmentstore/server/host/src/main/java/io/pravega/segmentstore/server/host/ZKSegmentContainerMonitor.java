/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.host;

import io.pravega.common.Exceptions;
import io.pravega.common.LoggerHelpers;
import io.pravega.common.cluster.Host;
import io.pravega.common.concurrent.Futures;
import io.pravega.common.util.CollectionHelpers;
import io.pravega.segmentstore.server.ContainerHandle;
import io.pravega.segmentstore.server.SegmentContainerRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang.SerializationUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.utils.ZKPaths;

/**
 * Monitors the current set of running segment containers and ensure it matches the ownership assignment for this host.
 * This monitor watches the shared zk entry that contains the segment container ownership information
 * and starts or stops appropriate segment containers locally. Any start failures are periodically retried until
 * the desired ownership state is achieved.
 */
@Slf4j
public class ZKSegmentContainerMonitor implements AutoCloseable {

    private static final Duration INIT_TIMEOUT_PER_CONTAINER = Duration.ofSeconds(30L);
    private static final Duration CLOSE_TIMEOUT_PER_CONTAINER = Duration.ofSeconds(30L);
    private static final Duration MONITOR_INTERVAL = Duration.ofSeconds(10);

    // The host entry for which we are monitoring the container assignments.
    private final Host host;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // The zkNode which contains the segment container to host assignment.
    private final NodeCache hostContainerMapNode;
    private final SegmentContainerRegistry registry;

    // The list of container handles which are currently running in this node.
    private final Map<Integer, ContainerHandle> handles;

    // The list of containers which have ongoing start/stop tasks pending. This list is needed to ensure
    // we don't initiate conflicting tasks for the same containerId.
    private final Set<Integer> pendingTasks;
    private final ScheduledExecutorService executor;
    private AtomicReference<ScheduledFuture<?>> assigmentTask;

    /**
     * Creates an instance of ZKSegmentContainerMonitor.
     *
     * @param containerRegistry      The registry used to control the container state.
     * @param zkClient               The curator client.
     * @param pravegaServiceEndpoint The pravega endpoint for which we need to fetch the container assignment.
     */
    ZKSegmentContainerMonitor(SegmentContainerRegistry containerRegistry, CuratorFramework zkClient,
                              Host pravegaServiceEndpoint, ScheduledExecutorService executor) {
        Preconditions.checkNotNull(zkClient, "zkClient");

        this.registry = Preconditions.checkNotNull(containerRegistry, "containerRegistry");
        this.host = Preconditions.checkNotNull(pravegaServiceEndpoint, "pravegaServiceEndpoint");
        this.executor = Preconditions.checkNotNull(executor, "executor");
        this.handles = new ConcurrentHashMap<>();
        this.pendingTasks = new ConcurrentSkipListSet<>();
        String clusterPath = ZKPaths.makePath("cluster", "segmentContainerHostMapping");
        this.hostContainerMapNode = new NodeCache(zkClient, clusterPath);
        this.assigmentTask = new AtomicReference<>();
    }

    /**
     * Initialize the monitor. This will start the monitor thread which will process the start/stop container events.
     */
    public void initialize() {
        initialize(MONITOR_INTERVAL);
    }

    @VisibleForTesting
    @SneakyThrows(Exception.class)
    public void initialize(Duration monitorInterval) {
        Exceptions.checkNotClosed(closed.get(), this);

        // Start loading the segment container to node assigment map from zookeeper.
        this.hostContainerMapNode.start();

        // There are two triggers for the segment container monitor.
        // 1. On any segment container ownership changes notified via zookeeper. We will ensure the local containers
        //      are stopped/started according to the new ownership mapping.
        // 2. At scheduled intervals to perform retries on local segment container start failures.
        this.assigmentTask.set(this.executor.scheduleWithFixedDelay(
                this::checkAssignment, 0L, monitorInterval.getSeconds(), TimeUnit.SECONDS));
        this.hostContainerMapNode.getListenable().addListener(this::checkAssignment, this.executor);
    }

    @Override
    public void close() {
        Preconditions.checkState(closed.compareAndSet(false, true));
        try {
            this.hostContainerMapNode.close();
        } catch (IOException e) {
            // Ignoring exception on shutdown.
            log.warn("Failed to close hostContainerMapNode {}", e);
        }

        val task = this.assigmentTask.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }

        ArrayList<ContainerHandle> toClose = new ArrayList<>(this.handles.values());
        ArrayList<CompletableFuture<Void>> results = new ArrayList<>();
        for (ContainerHandle handle : toClose) {
            results.add(this.registry.stopContainer(handle, CLOSE_TIMEOUT_PER_CONTAINER)
                                     .thenAccept(v -> unregisterHandle(handle.getContainerId())));
        }

        // Wait for all the containers to be closed.
        Futures.await(Futures.allOf(results), CLOSE_TIMEOUT_PER_CONTAINER.toMillis());
    }

    @VisibleForTesting
    Collection<Integer> getRegisteredContainers() {
        return this.handles.keySet();
    }

    /**
     * The container assignment monitor.
     * This method will fetch the current owned containers for this host and ensures that the local containers' state
     * reflects this.
     */
    @Synchronized
    private void checkAssignment() {
        long traceId = LoggerHelpers.traceEnter(log, "checkAssignment");
        try {
            Exceptions.checkNotClosed(closed.get(), this);

            // Fetch the list of containers that is supposed to be owned by this host.
            Set<Integer> desiredList = getDesiredContainerList();
            if (desiredList != null) {
                Collection<Integer> runningContainers = new HashSet<>(this.handles.keySet());
                Collection<Integer> containersPendingTasks = new HashSet<>(this.pendingTasks);

                // Filter out containers which have pending tasks so we don't initiate conflicting events on the same
                // containers. Events for these containers will be tried on subsequent runs of this executor.
                Collection<Integer> containersToBeStarted = CollectionHelpers.filterOut(desiredList, runningContainers);
                containersToBeStarted = CollectionHelpers.filterOut(containersToBeStarted, containersPendingTasks);

                Collection<Integer> containersToBeStopped = CollectionHelpers.filterOut(runningContainers, desiredList);
                containersToBeStopped = CollectionHelpers.filterOut(containersToBeStopped, containersPendingTasks);

                log.info("Container Changes: Desired = {}, Current = {}, PendingTasks = {}, ToStart = {}, ToStop = {}.",
                        desiredList, runningContainers, containersPendingTasks, containersToBeStarted,
                        containersToBeStopped);

                // Initiate the start and stop tasks asynchronously.
                containersToBeStarted.forEach(this::startContainer);
                containersToBeStopped.forEach(this::stopContainer);
            } else {
                log.warn("No segment container assignments found");
            }
        } catch (Throwable e) {
            // Need to catch all exceptions here since throwing any exception here will halt this scheduled job.
            log.warn("Failed to monitor the segmentcontainer assignment: ", e);
        } finally {
            LoggerHelpers.traceLeave(log, "checkAssignment", traceId);
        }
    }

    // Stop the container given its id.
    private CompletableFuture<Void> stopContainer(int containerId) {
        log.info("Stopping Container {}.", containerId);
        ContainerHandle handle = handles.get(containerId);
        if (handle == null) {
            log.warn("Container {} handle is null, container is pending start or already unregistered.", containerId);
            return null;
        } else {
            this.pendingTasks.add(containerId);
            try {
                return registry
                        .stopContainer(handle, CLOSE_TIMEOUT_PER_CONTAINER)
                        .whenComplete((aVoid, throwable) -> {
                            if (throwable != null) {
                                log.warn("Stopping container {} failed: {}", containerId, throwable);
                            }
                            try {
                                // We remove the handle and don't attempt retry on stop container failures.
                                unregisterHandle(containerId);
                            } finally {
                                // The pending task has to be removed after the handle is removed to avoid inconsistencies
                                // with the container state.
                                // Using finally block to ensure this is always called, otherwise this will prevent other
                                // tasks from being attempted for this container id.
                                this.pendingTasks.remove(containerId);
                            }
                        });
            } catch (Throwable e) {
                // The pending task has to be removed on all failures to enable retries.
                this.pendingTasks.remove(containerId);
                throw e;
            }
        }
    }

    private CompletableFuture<ContainerHandle> startContainer(int containerId) {
        log.info("Starting Container {}.", containerId);
        this.pendingTasks.add(containerId);
        try {
            return this.registry
                    .startContainer(containerId, INIT_TIMEOUT_PER_CONTAINER)
                    .whenComplete((handle, ex) -> {
                        try {
                            if (ex == null) {
                                if (this.handles.putIfAbsent(handle.getContainerId(), handle) != null) {
                                    log.warn("Starting container {} succeeded but handle is already registered.",
                                            handle.getContainerId());
                                } else {
                                    handle.setContainerStoppedListener(this::unregisterHandle);
                                    log.info("Container {} has been registered.", handle.getContainerId());
                                }
                            } else {
                                log.warn("Starting container {} failed: {}", containerId, ex);
                            }
                        } finally {
                            // The pending task has to be removed in the end to avoid inconsistencies since containerhandle
                            // should be available immediately after the task is complete.
                            // Also need to ensure this is always called, hence doing this in a finally block.
                            this.pendingTasks.remove(containerId);
                        }
                    });
        } catch (Throwable e) {
            // The pending task has to be removed on all failures to enable retries.
            this.pendingTasks.remove(containerId);
            throw e;
        }
    }

    private void unregisterHandle(int containerId) {
        if (this.handles.remove(containerId) == null) {
            log.warn("Attempted to unregister non-registered container {}.", containerId);
        } else {
            log.info("Container {} has been unregistered.", containerId);
        }
    }

    private Set<Integer> getDesiredContainerList() {
        log.debug("Fetching the latest container assignment from ZooKeeper.");
        if (hostContainerMapNode.getCurrentData() != null) { //Check if path exists.
            //read data from zk.
            byte[] containerToHostMapSer = hostContainerMapNode.getCurrentData().getData();
            if (containerToHostMapSer != null) {
                @SuppressWarnings("unchecked")
                val controlMapping = (Map<Host, Set<Integer>>) SerializationUtils.deserialize(containerToHostMapSer);
                return controlMapping.entrySet().stream()
                                     .filter(ep -> ep.getKey().equals(this.host))
                                     .map(Map.Entry::getValue)
                                     .findFirst().orElse(Collections.emptySet());
            }
        }

        return null;
    }
}