/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.persistent;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.node.tasks.cancel.CancelTasksResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.PersistentTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * This component is responsible for coordination of execution of persistent tasks on individual nodes. It runs on all
 * non-transport client nodes in the cluster and monitors cluster state changes to detect started commands.
 */
public class PersistentTasksNodeService extends AbstractComponent implements ClusterStateListener {
    private final Map<Long, AllocatedPersistentTask> runningTasks = new HashMap<>();
    private final PersistentTasksService persistentTasksService;
    private final PersistentTasksExecutorRegistry persistentTasksExecutorRegistry;
    private final TaskManager taskManager;
    private final NodePersistentTasksExecutor nodePersistentTasksExecutor;


    public PersistentTasksNodeService(Settings settings,
                                      PersistentTasksService persistentTasksService,
                                      PersistentTasksExecutorRegistry persistentTasksExecutorRegistry,
                                      TaskManager taskManager, NodePersistentTasksExecutor nodePersistentTasksExecutor) {
        super(settings);
        this.persistentTasksService = persistentTasksService;
        this.persistentTasksExecutorRegistry = persistentTasksExecutorRegistry;
        this.taskManager = taskManager;
        this.nodePersistentTasksExecutor = nodePersistentTasksExecutor;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        PersistentTasksCustomMetaData tasks = event.state().getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
        PersistentTasksCustomMetaData previousTasks = event.previousState().getMetaData().custom(PersistentTasksCustomMetaData.TYPE);

        // Cluster State   Local State      Local Action
        //   STARTED         NULL          Create as STARTED, Start
        //   STARTED         STARTED       Noop - running
        //   STARTED         COMPLETED     Noop - waiting for notification ack

        //   NULL            NULL          Noop - nothing to do
        //   NULL            STARTED       Remove locally, Mark as PENDING_CANCEL, Cancel
        //   NULL            COMPLETED     Remove locally

        // Master states:
        // NULL - doesn't exist in the cluster state
        // STARTED - exist in the cluster state

        // Local state:
        // NULL - we don't have task registered locally in runningTasks
        // STARTED - registered in TaskManager, requires master notification when finishes
        // PENDING_CANCEL - registered in TaskManager, doesn't require master notification when finishes
        // COMPLETED - not registered in TaskManager, notified, waiting for master to remove it from CS so we can remove locally

        // When task finishes if it is marked as STARTED or PENDING_CANCEL it is marked as COMPLETED and unregistered,
        // If the task was STARTED, the master notification is also triggered (this is handled by unregisterTask() method, which is
        // triggered by PersistentTaskListener

        if (Objects.equals(tasks, previousTasks) == false || event.nodesChanged()) {
            // We have some changes let's check if they are related to our node
            String localNodeId = event.state().getNodes().getLocalNodeId();
            Set<Long> notVisitedTasks = new HashSet<>(runningTasks.keySet());
            if (tasks != null) {
                for (PersistentTask<?> taskInProgress : tasks.tasks()) {
                    if (localNodeId.equals(taskInProgress.getExecutorNode())) {
                        Long allocationId = taskInProgress.getAllocationId();
                        AllocatedPersistentTask persistentTask = runningTasks.get(allocationId);
                        if (persistentTask == null) {
                            // New task - let's start it
                            startTask(taskInProgress);
                        } else {
                            // The task is still running
                            notVisitedTasks.remove(allocationId);
                        }
                    }
                }
            }

            for (Long id : notVisitedTasks) {
                AllocatedPersistentTask task = runningTasks.get(id);
                if (task.getState() == AllocatedPersistentTask.State.COMPLETED) {
                    // Result was sent to the caller and the caller acknowledged acceptance of the result
                    runningTasks.remove(id);
                } else {
                    // task is running locally, but master doesn't know about it - that means that the persistent task was removed
                    // cancel the task without notifying master
                    logger.trace("Found unregistered persistent task with id {} - cancelling ", id);
                    cancelTask(id);
                }
            }

        }

    }

    private <Request extends PersistentTaskRequest> void startTask(PersistentTask<Request> taskInProgress) {
        PersistentTasksExecutor<Request> action = persistentTasksExecutorRegistry.getPersistentTaskExecutorSafe(taskInProgress.getTaskName());
        AllocatedPersistentTask task = (AllocatedPersistentTask) taskManager.register("persistent", taskInProgress.getTaskName() + "[c]",
                taskInProgress.getRequest());
        boolean processed = false;
        try {
            task.init(persistentTasksService, taskManager, logger, taskInProgress.getId(), taskInProgress.getAllocationId());
            try {
                runningTasks.put(taskInProgress.getAllocationId(), task);
                nodePersistentTasksExecutor.executeTask(taskInProgress.getRequest(), task, action);
            } catch (Exception e) {
                // Submit task failure
                task.markAsFailed(e);
            }
            processed = true;
        } finally {
            if (processed == false) {
                // something went wrong - unregistering task
                taskManager.unregister(task);
            }
        }
    }

    /**
     * Unregisters and then cancels the locally running task using the task manager. No notification to master will be send upon
     * cancellation.
     */
    private void cancelTask(Long allocationId) {
        AllocatedPersistentTask task = runningTasks.remove(allocationId);
        if (task.markAsCancelled()) {
            // Cancel the local task using the task manager
            persistentTasksService.sendTaskManagerCancellation(task.getId(), new ActionListener<CancelTasksResponse>() {
                @Override
                public void onResponse(CancelTasksResponse cancelTasksResponse) {
                    logger.trace("Persistent task with id {} was cancelled", task.getId());

                }

                @Override
                public void onFailure(Exception e) {
                    // There is really nothing we can do in case of failure here
                    logger.warn((Supplier<?>) () -> new ParameterizedMessage("failed to cancel task {}", task.getPersistentTaskId()), e);
                }
            });
        }
    }


    public static class Status implements Task.Status {
        public static final String NAME = "persistent_executor";

        private final AllocatedPersistentTask.State state;

        public Status(AllocatedPersistentTask.State state) {
            this.state = requireNonNull(state, "State cannot be null");
        }

        public Status(StreamInput in) throws IOException {
            state = AllocatedPersistentTask.State.valueOf(in.readString());
        }

        @Override
        public String getWriteableName() {
            return NAME;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("state", state.toString());
            builder.endObject();
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(state.toString());
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }

        public AllocatedPersistentTask.State getState() {
            return state;
        }

        @Override
        public boolean isFragment() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Status status = (Status) o;
            return state == status.state;
        }

        @Override
        public int hashCode() {
            return Objects.hash(state);
        }
    }

}