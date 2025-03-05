// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.scheduler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ScalarType;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.Pair;
import com.starrocks.common.util.LogUtil;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.common.util.concurrent.QueryableReentrantLock;
import com.starrocks.memory.MemoryTrackable;
import com.starrocks.persist.ImageWriter;
import com.starrocks.persist.metablock.SRMetaBlockEOFException;
import com.starrocks.persist.metablock.SRMetaBlockException;
import com.starrocks.persist.metablock.SRMetaBlockID;
import com.starrocks.persist.metablock.SRMetaBlockReader;
import com.starrocks.persist.metablock.SRMetaBlockWriter;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.qe.ShowResultSetMetaData;
import com.starrocks.scheduler.history.TaskRunHistory;
import com.starrocks.scheduler.persist.ArchiveTaskRunsLog;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.scheduler.persist.TaskRunStatusChange;
import com.starrocks.scheduler.persist.TaskSchedule;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.SubmitTaskStmt;
import com.starrocks.sql.common.DmlException;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.thrift.TGetTasksParams;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.starrocks.scheduler.SubmitResult.SubmitStatus.SUBMITTED;

public class TaskManager implements MemoryTrackable {

    private static final Logger LOG = LogManager.getLogger(TaskManager.class);

    // taskId -> Task , Task may have Normal Task, Periodical Task
    // every TaskRun must be generated by a Task
    private final Map<Long, Task> idToTaskMap;
    // taskName -> Task, include Manual Task, Periodical Task
    private final Map<String, Task> nameToTaskMap;
    private final Map<Long, ScheduledFuture<?>> periodFutureMap;

    // include PENDING/RUNNING taskRun;
    private final TaskRunManager taskRunManager;
    private final TaskRunScheduler taskRunScheduler;

    // The periodScheduler is used to generate the corresponding TaskRun on time for the Periodical Task.
    // This scheduler can use the time wheel to optimize later.
    private final ScheduledExecutorService periodScheduler = Executors.newScheduledThreadPool(1);

    // The dispatchTaskScheduler is responsible for periodically checking whether the running TaskRun is completed
    // and updating the status. It is also responsible for placing pending TaskRun in the running TaskRun queue.
    // This operation need to consider concurrency.
    // This scheduler can use notify/wait to optimize later.
    private final ScheduledExecutorService dispatchScheduler = Executors.newScheduledThreadPool(1);
    // Use to concurrency control
    private final QueryableReentrantLock taskLock;

    private final AtomicBoolean isStart = new AtomicBoolean(false);

    public TaskManager() {
        idToTaskMap = Maps.newConcurrentMap();
        nameToTaskMap = Maps.newConcurrentMap();
        periodFutureMap = Maps.newConcurrentMap();
        taskRunManager = new TaskRunManager();
        taskLock = new QueryableReentrantLock(true);
        taskRunScheduler = taskRunManager.getTaskRunScheduler();
    }

    public void start() {
        if (isStart.compareAndSet(false, true)) {
            clearUnfinishedTaskRun();
            registerPeriodicalTask();
            dispatchScheduler.scheduleAtFixedRate(() -> {
                if (!taskRunManager.tryTaskRunLock()) {
                    LOG.warn("TaskRun scheduler cannot acquire the lock");
                    return;
                }
                try {
                    taskRunManager.checkRunningTaskRun();
                    taskRunManager.scheduledPendingTaskRun();
                } catch (Exception ex) {
                    LOG.warn("failed to dispatch task.", ex);
                } finally {
                    taskRunManager.taskRunUnlock();
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    private void registerPeriodicalTask() {
        for (Task task : nameToTaskMap.values()) {
            if (task.getType() != Constants.TaskType.PERIODICAL) {
                continue;
            }

            TaskSchedule taskSchedule = task.getSchedule();
            if (task.getState() != Constants.TaskState.ACTIVE) {
                continue;
            }

            if (taskSchedule == null) {
                continue;
            }
            registerScheduler(task);
        }
    }

    @VisibleForTesting
    static long getInitialDelayTime(long periodSeconds, LocalDateTime startTime, LocalDateTime scheduleTime) {
        Duration duration = Duration.between(scheduleTime, startTime);
        long initialDelay = duration.getSeconds();
        // if startTime < now, start scheduling from the next period
        if (initialDelay < 0) {
            // if schedule time is not a complete second, add extra 1 second to avoid less than expect scheduler time.
            // eg:
            //  Register scheduler, task:mv-271809, initialDay:22, periodSeconds:60, startTime:2023-12-29T17:50,
            //  scheduleTime:2024-01-30T15:27:37.342356010
            // Before:schedule at : Hour:MINUTE:59
            // After: schedule at : HOUR:MINUTE:00
            int extra = scheduleTime.getNano() > 0 ? 1 : 0;
            return ((initialDelay % periodSeconds) + periodSeconds + extra) % periodSeconds;
        } else {
            return initialDelay;
        }
    }

    private void clearUnfinishedTaskRun() {
        if (!taskRunManager.tryTaskRunLock()) {
            return;
        }
        try {
            // clear pending task runs
            List<TaskRun> taskRuns = taskRunScheduler.getCopiedPendingTaskRuns();
            for (TaskRun taskRun : taskRuns) {
                taskRun.getStatus().setErrorMessage("Fe abort the task");
                taskRun.getStatus().setErrorCode(-1);
                taskRun.getStatus().setState(Constants.TaskRunState.FAILED);

                taskRunManager.getTaskRunHistory().addHistory(taskRun.getStatus());
                TaskRunStatusChange statusChange = new TaskRunStatusChange(taskRun.getTaskId(), taskRun.getStatus(),
                        Constants.TaskRunState.PENDING, Constants.TaskRunState.FAILED);
                GlobalStateMgr.getCurrentState().getEditLog().logUpdateTaskRun(statusChange);

                // remove pending task run
                taskRunScheduler.removePendingTaskRun(taskRun, Constants.TaskRunState.FAILED);
            }

            // clear running task runs
            Set<Long> runningTaskIds = taskRunScheduler.getCopiedRunningTaskIds();
            for (Long taskId : runningTaskIds) {
                TaskRun taskRun = taskRunScheduler.getRunningTaskRun(taskId);
                taskRun.getStatus().setErrorMessage("Fe abort the task");
                taskRun.getStatus().setErrorCode(-1);
                taskRun.getStatus().setState(Constants.TaskRunState.FAILED);
                taskRun.getStatus().setFinishTime(System.currentTimeMillis());

                taskRunManager.getTaskRunHistory().addHistory(taskRun.getStatus());
                TaskRunStatusChange statusChange = new TaskRunStatusChange(taskRun.getTaskId(), taskRun.getStatus(),
                        Constants.TaskRunState.RUNNING, Constants.TaskRunState.FAILED);
                GlobalStateMgr.getCurrentState().getEditLog().logUpdateTaskRun(statusChange);

                // remove running task run
                taskRunScheduler.removeRunningTask(taskId);
            }
        } finally {
            taskRunManager.taskRunUnlock();
        }
    }

    public void createTask(Task task, boolean isReplay) throws DdlException {
        takeTaskLock();
        try {
            if (nameToTaskMap.containsKey(task.getName())) {
                throw new DdlException("Task [" + task.getName() + "] already exists");
            }
            if (!isReplay) {
                // TaskId should be assigned by the framework
                Preconditions.checkArgument(task.getId() == 0);
                task.setId(GlobalStateMgr.getCurrentState().getNextId());
            }
            if (task.getType() == Constants.TaskType.PERIODICAL) {
                task.setState(Constants.TaskState.ACTIVE);
                if (!isReplay) {
                    TaskSchedule schedule = task.getSchedule();
                    if (schedule == null) {
                        throw new DdlException("Task [" + task.getName() + "] has no scheduling information");
                    }
                    registerScheduler(task);
                }
            }
            nameToTaskMap.put(task.getName(), task);
            idToTaskMap.put(task.getId(), task);
            if (!isReplay) {
                GlobalStateMgr.getCurrentState().getEditLog().logCreateTask(task);
            }
        } finally {
            taskUnlock();
        }
    }

    private boolean stopScheduler(String taskName) {
        Task task = nameToTaskMap.get(taskName);
        if (task.getType() != Constants.TaskType.PERIODICAL) {
            return false;
        }
        if (task.getState() == Constants.TaskState.PAUSE) {
            return true;
        }
        TaskSchedule taskSchedule = task.getSchedule();
        // this will not happen
        if (taskSchedule == null) {
            LOG.warn("fail to obtain scheduled info for task [{}]", task.getName());
            return true;
        }
        ScheduledFuture<?> future = periodFutureMap.get(task.getId());
        if (future == null) {
            LOG.warn("fail to obtain scheduled info for task [{}]", task.getName());
            return true;
        }
        boolean isCancel = future.cancel(true);
        if (!isCancel) {
            LOG.warn("fail to cancel scheduler for task [{}]", task.getName());
        }
        return isCancel;
    }

    public boolean killTask(String taskName, boolean force) {
        Task task = nameToTaskMap.get(taskName);
        if (task == null) {
            return false;
        }
        if (!taskRunManager.tryTaskRunLock()) {
            return false;
        }
        try {
            taskRunScheduler.removePendingTask(task);
        } catch (Exception ex) {
            LOG.warn("failed to kill task.", ex);
        } finally {
            taskRunManager.taskRunUnlock();
        }
        taskRunManager.killTaskRun(task.getId(), force);
        return true;
    }

    public SubmitResult executeTask(String taskName) {
        Task task = getTask(taskName);
        if (task == null) {
            return new SubmitResult(null, SubmitResult.SubmitStatus.FAILED);
        }
        ExecuteOption option = new ExecuteOption(task);
        option.setManual(true);
        return executeTask(taskName, option);
    }

    public SubmitResult executeTask(String taskName, ExecuteOption option) {
        Task task = getTask(taskName);
        if (task == null) {
            return new SubmitResult(null, SubmitResult.SubmitStatus.FAILED);
        }
        if (option.getIsSync()) {
            return executeTaskSync(task, option);
        } else {
            return executeTaskAsync(task, option);
        }
    }

    public SubmitResult executeTaskSync(Task task) {
        return executeTaskSync(task, new ExecuteOption(task));
    }

    public SubmitResult executeTaskSync(Task task, ExecuteOption option) {
        TaskRun taskRun;
        SubmitResult submitResult;
        if (!tryTaskLock()) {
            throw new DmlException("Failed to get task lock when execute Task sync[" + task.getName() + "]");
        }
        try {
            taskRun = TaskRunBuilder.newBuilder(task)
                    .properties(option.getTaskRunProperties())
                    .setExecuteOption(option)
                    .setConnectContext(ConnectContext.get()).build();
            submitResult = taskRunManager.submitTaskRun(taskRun, option);
            if (submitResult.getStatus() != SUBMITTED) {
                throw new DmlException("execute task:" + task.getName() + " failed");
            }
        } finally {
            taskUnlock();
        }

        try {
            taskRunScheduler.addSyncRunningTaskRun(taskRun);
            Constants.TaskRunState taskRunState = taskRun.getFuture().get();
            if (!taskRunState.isSuccessState()) {
                String msg = taskRun.getStatus().getErrorMessage();
                throw new DmlException("execute task %s failed: %s", task.getName(), msg);
            }
            return submitResult;
        } catch (InterruptedException | ExecutionException e) {
            Throwable rootCause = e.getCause();
            throw new DmlException("execute task %s failed: %s", rootCause, task.getName(), rootCause.getMessage());
        } catch (Exception e) {
            throw new DmlException("execute task %s failed: %s", e, task.getName(), e.getMessage());
        } finally {
            taskRunScheduler.removeSyncRunningTaskRun(taskRun);
        }
    }

    public SubmitResult executeTaskAsync(Task task, ExecuteOption option) {
        TaskRun taskRun = TaskRunBuilder
                .newBuilder(task)
                .properties(option.getTaskRunProperties())
                .setExecuteOption(option)
                .build();
        return taskRunManager.submitTaskRun(taskRun, option);
    }

    public void dropTasks(List<Long> taskIdList, boolean isReplay) {
        takeTaskLock();
        try {
            for (long taskId : taskIdList) {
                Task task = idToTaskMap.get(taskId);
                if (task == null) {
                    LOG.warn("drop taskId {} failed because task is null", taskId);
                    continue;
                }
                if (task.getType() == Constants.TaskType.PERIODICAL && !isReplay) {
                    boolean isCancel = stopScheduler(task.getName());
                    if (!isCancel) {
                        continue;
                    }
                    periodFutureMap.remove(task.getId());
                }
                if (!killTask(task.getName(), false)) {
                    LOG.warn("kill task failed: {}", task.getName());
                }
                idToTaskMap.remove(task.getId());
                nameToTaskMap.remove(task.getName());
            }

            if (!isReplay) {
                GlobalStateMgr.getCurrentState().getEditLog().logDropTasks(taskIdList);
            }
        } finally {
            taskUnlock();
        }
        LOG.info("drop tasks:{}", taskIdList);
    }

    private boolean isTaskMatched(Task task, TGetTasksParams params) {
        if (params == null) {
            return true;
        }
        String dbName = params.db;
        if (dbName != null && !dbName.equals(task.getDbName())) {
            return false;
        }
        String taskName = params.task_name;
        if (taskName != null && !taskName.equalsIgnoreCase(task.getName())) {
            return false;
        }
        return true;
    }

    public List<Task> filterTasks(TGetTasksParams params) {
        List<Task> taskList = Lists.newArrayList();
        nameToTaskMap.values().stream()
                .filter(t -> isTaskMatched(t, params))
                .forEach(taskList::add);
        return taskList;
    }

    public void alterTask(Task currentTask, Task changedTask, boolean isReplay) {
        Constants.TaskType currentType = currentTask.getType();
        Constants.TaskType changedType = changedTask.getType();
        boolean hasChanged = false;
        if (currentType == Constants.TaskType.MANUAL) {
            if (changedType == Constants.TaskType.EVENT_TRIGGERED) {
                hasChanged = true;
            }
        } else if (currentTask.getType() == Constants.TaskType.EVENT_TRIGGERED) {
            if (changedType == Constants.TaskType.MANUAL) {
                hasChanged = true;
            }
        } else if (currentTask.getType() == Constants.TaskType.PERIODICAL) {
            if (!isReplay) {
                boolean isCancel = stopScheduler(currentTask.getName());
                if (!isCancel) {
                    throw new RuntimeException("stop scheduler failed");
                }
            }
            periodFutureMap.remove(currentTask.getId());
            currentTask.setState(Constants.TaskState.UNKNOWN);
            currentTask.setSchedule(null);
            hasChanged = true;
        }

        if (changedType == Constants.TaskType.PERIODICAL) {
            currentTask.setState(Constants.TaskState.ACTIVE);
            TaskSchedule schedule = changedTask.getSchedule();
            currentTask.setSchedule(schedule);
            if (!isReplay) {
                registerScheduler(currentTask);
            }
            hasChanged = true;
        }

        if (hasChanged) {
            currentTask.setType(changedTask.getType());
            if (!isReplay) {
                GlobalStateMgr.getCurrentState().getEditLog().logAlterTask(changedTask);
            }
        }
    }

    private void registerScheduler(Task task) {
        LocalDateTime scheduleTime = LocalDateTime.now();
        TaskSchedule schedule = task.getSchedule();
        LocalDateTime startTime = Utils.getDatetimeFromLong(schedule.getStartTime());
        long periodSeconds = TimeUtils.convertTimeUnitValueToSecond(schedule.getPeriod(), schedule.getTimeUnit());
        long initialDelay = getInitialDelayTime(periodSeconds, startTime, scheduleTime);
        LOG.info("Register scheduler, task:{}, initialDelay:{}, periodSeconds:{}, startTime:{}, scheduleTime:{}",
                task.getName(), initialDelay, periodSeconds, startTime, scheduleTime);
        ExecuteOption option = new ExecuteOption(Constants.TaskRunPriority.LOWEST.value(), true, task.getProperties());
        ScheduledFuture<?> future = periodScheduler.scheduleAtFixedRate(() ->
                executeTask(task.getName(), option), initialDelay, periodSeconds, TimeUnit.SECONDS);
        periodFutureMap.put(task.getId(), future);
    }

    public void replayAlterTask(Task task) {
        Task currentTask = getTask(task.getName());
        alterTask(currentTask, task, true);
    }

    private boolean tryTaskLock() {
        try {
            if (!taskLock.tryLock(5, TimeUnit.SECONDS)) {
                Thread owner = taskLock.getOwner();
                if (owner != null) {
                    LOG.warn("task lock is held by: {}", LogUtil.dumpThread(owner, 50));
                } else {
                    LOG.warn("task lock owner is null");
                }
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            LOG.warn("got exception while getting task lock", e);
        }
        return false;
    }

    /**
     * Keep trying to get the lock until succeed
     */
    private void takeTaskLock() {
        int i = 1;
        while (!tryTaskLock()) {
            LOG.warn("fail to get TaskManager lock after retry {} times", i);
            i++;
        }
    }

    public void taskUnlock() {
        this.taskLock.unlock();
    }

    public void replayCreateTask(Task task) {
        if (task.getType() == Constants.TaskType.PERIODICAL) {
            TaskSchedule taskSchedule = task.getSchedule();
            if (taskSchedule == null) {
                LOG.warn("replay a null schedule period Task [{}]", task.getName());
                return;
            }
        }
        if (task.getExpireTime() > 0 && System.currentTimeMillis() > task.getExpireTime()) {
            return;
        }
        try {
            createTask(task, true);
        } catch (DdlException e) {
            LOG.warn("failed to replay create task [{}]", task.getName(), e);
        }
    }

    public void replayDropTasks(List<Long> taskIdList) {
        dropTasks(taskIdList, true);
    }

    public TaskRunManager getTaskRunManager() {
        return taskRunManager;
    }

    public TaskRunScheduler getTaskRunScheduler() {
        return taskRunScheduler;
    }

    public TaskRunHistory getTaskRunHistory() {
        return taskRunManager.getTaskRunHistory();
    }

    public ShowResultSet handleSubmitTaskStmt(SubmitTaskStmt submitTaskStmt) throws DdlException {
        Task task = TaskBuilder.buildTask(submitTaskStmt, ConnectContext.get());
        String taskName = task.getName();
        SubmitResult submitResult;
        try {
            createTask(task, false);
            if (task.getType() == Constants.TaskType.MANUAL) {
                submitResult = executeTask(task.getName());
            } else {
                submitResult = new SubmitResult(null, SUBMITTED);
            }
        } catch (DdlException ex) {
            if (ex.getMessage().contains("Failed to get task lock")) {
                submitResult = new SubmitResult(null, SubmitResult.SubmitStatus.REJECTED);
            } else {
                LOG.warn("Failed to create Task [{}]", taskName, ex);
                throw ex;
            }
        }

        ShowResultSetMetaData.Builder builder = ShowResultSetMetaData.builder();
        builder.addColumn(new Column("TaskName", ScalarType.createVarchar(40)));
        builder.addColumn(new Column("Status", ScalarType.createVarchar(10)));
        List<String> item = ImmutableList.of(taskName, submitResult.getStatus().toString());
        List<List<String>> result = ImmutableList.of(item);
        return new ShowResultSet(builder.build(), result);
    }

    public void loadTasksV2(SRMetaBlockReader reader)
            throws IOException, SRMetaBlockException, SRMetaBlockEOFException {
        reader.readCollection(Task.class, this::replayCreateTask);

        reader.readCollection(TaskRunStatus.class, this::replayCreateTaskRun);
    }

    public void saveTasksV2(ImageWriter imageWriter) throws IOException, SRMetaBlockException {
        taskRunManager.getTaskRunHistory().forceGC();
        List<TaskRunStatus> runStatusList = getMatchedTaskRunStatus(null);
        LOG.info("saveTasksV2, nameToTaskMap size:{}, runStatusList size: {}", nameToTaskMap.size(), runStatusList.size());
        SRMetaBlockWriter writer = imageWriter.getBlockWriter(SRMetaBlockID.TASK_MGR,
                2 + nameToTaskMap.size() + runStatusList.size());
        writer.writeInt(nameToTaskMap.size());
        for (Task task : nameToTaskMap.values()) {
            writer.writeJson(task);
        }

        writer.writeInt(runStatusList.size());
        for (TaskRunStatus status : runStatusList) {
            writer.writeJson(status);
        }

        writer.close();
    }

    public List<TaskRunStatus> getMatchedTaskRunStatus(TGetTasksParams params) {
        List<TaskRunStatus> taskRunList = Lists.newArrayList();
        // pending task runs
        List<TaskRun> pendingTaskRuns = taskRunScheduler.getCopiedPendingTaskRuns();
        pendingTaskRuns.stream()
                .map(TaskRun::getStatus)
                .filter(t -> t.match(params))
                .forEach(taskRunList::add);

        // running task runs
        Set<TaskRun> runningTaskRuns = taskRunScheduler.getCopiedRunningTaskRuns();
        runningTaskRuns.stream()
                .map(TaskRun::getStatus)
                .filter(t -> t.match(params))
                .forEach(taskRunList::add);

        // history task runs
        taskRunList.addAll(taskRunManager.getTaskRunHistory().lookupHistory(params));

        return taskRunList;
    }

    /**
     * Return the last refresh TaskRunStatus for the task which the source type is MV.
     * The iteration order is by the task refresh time:
     * PendingTaskRunMap > RunningTaskRunMap > TaskRunHistory
     * TODO: Maybe only return needed MVs rather than all MVs.
     */
    public Map<String, List<TaskRunStatus>> listMVRefreshedTaskRunStatus(String dbName,
                                                                         Set<String> taskNames) {
        Map<String, List<TaskRunStatus>> mvNameRunStatusMap = Maps.newHashMap();
        Predicate<TaskRunStatus> taskRunFilter = (task) ->
                Objects.nonNull(task)
                        && task.getSource() == Constants.TaskSource.MV
                        && task.getState() != Constants.TaskRunState.MERGED
                        && (dbName == null || task.getDbName().equals(dbName))
                        && (CollectionUtils.isEmpty(taskNames) || taskNames.contains(task.getTaskName()));
        Consumer<TaskRunStatus> addResult = task -> {
            // Keep only the first one of duplicated task runs
            if (isSameTaskRunJob(task, mvNameRunStatusMap)) {
                mvNameRunStatusMap.computeIfAbsent(task.getTaskName(), x -> Lists.newArrayList()).add(task);
            }
        };

        // running
        taskRunScheduler.getCopiedRunningTaskRuns().stream()
                .map(TaskRun::getStatus)
                .filter(taskRunFilter)
                .forEach(addResult);

        // pending task runs
        List<TaskRun> pendingTaskRuns = taskRunScheduler.getCopiedPendingTaskRuns();
        pendingTaskRuns.stream()
                .map(TaskRun::getStatus)
                .filter(taskRunFilter)
                .forEach(addResult);

        // history
        taskRunManager.getTaskRunHistory().lookupLastJobOfTasks(dbName, taskNames)
                .stream()
                .filter(taskRunFilter)
                .forEach(addResult);

        return mvNameRunStatusMap;
    }

    private boolean isSameTaskRunJob(TaskRunStatus taskRunStatus,
                                     Map<String, List<TaskRunStatus>> mvNameRunStatusMap) {
        // 1. if task status has already existed, existed task run status's job id is not null, find the same job id.
        // 2. otherwise, add it to the result.
        if (!mvNameRunStatusMap.containsKey(taskRunStatus.getTaskName())) {
            return true;
        }
        List<TaskRunStatus> existedTaskRuns = mvNameRunStatusMap.get(taskRunStatus.getTaskName());
        if (existedTaskRuns == null || existedTaskRuns.isEmpty()) {
            return true;
        }
        if (!Config.enable_show_materialized_views_include_all_task_runs) {
            return false;
        }
        String jobId = taskRunStatus.getStartTaskRunId();
        return !Strings.isNullOrEmpty(jobId) && jobId.equals(existedTaskRuns.get(0).getStartTaskRunId());
    }

    public void replayCreateTaskRun(TaskRunStatus status) {
        if (status.getState().isFinishState() && System.currentTimeMillis() > status.getExpireTime()) {
            return;
        }
        LOG.debug("replayCreateTaskRun:" + status);

        switch (status.getState()) {
            case PENDING:
                String taskName = status.getTaskName();
                Task task = nameToTaskMap.get(taskName);
                if (task == null) {
                    LOG.warn("fail to obtain task name {} because task is null", taskName);
                    return;
                }
                ExecuteOption executeOption = new ExecuteOption(task);
                executeOption.setReplay(true);
                TaskRun taskRun = TaskRunBuilder
                        .newBuilder(task)
                        .setExecuteOption(executeOption)
                        .build();
                // TODO: To avoid the same query id collision, use a new query id instead of an old query id
                taskRun.initStatus(status.getQueryId(), status.getCreateTime());
                if (!taskRunScheduler.addPendingTaskRun(taskRun)) {
                    LOG.warn("Submit task run to pending queue failed in follower, reject the submit:{}", taskRun);
                }
                break;
            // this will happen in build image
            case RUNNING:
                status.setState(Constants.TaskRunState.FAILED);
                taskRunManager.getTaskRunHistory().addHistory(status);
                break;
            case FAILED:
                taskRunManager.getTaskRunHistory().addHistory(status);
                break;
            case MERGED:
            case SUCCESS:
                status.setProgress(100);
                taskRunManager.getTaskRunHistory().addHistory(status);
                break;
        }
    }

    public void replayUpdateTaskRun(TaskRunStatusChange statusChange) {
        LOG.debug("replayUpdateTaskRun:" + statusChange);
        Constants.TaskRunState fromStatus = statusChange.getFromStatus();
        Constants.TaskRunState toStatus = statusChange.getToStatus();
        Long taskId = statusChange.getTaskId();

        if (fromStatus == Constants.TaskRunState.PENDING) {
            // It is possible to update out of order for priority queue.
            TaskRun pendingTaskRun = taskRunScheduler.getTaskRunByQueryId(taskId, statusChange.getQueryId());
            if (pendingTaskRun == null) {
                LOG.warn("could not find query_id:{}, taskId:{}, when replay update pendingTaskRun",
                        statusChange.getQueryId(), taskId);
                return;
            }
            // remove it from pending task queue
            taskRunScheduler.removePendingTaskRun(pendingTaskRun, toStatus);

            TaskRunStatus status = pendingTaskRun.getStatus();
            if (toStatus == Constants.TaskRunState.RUNNING) {
                if (status.getQueryId().equals(statusChange.getQueryId())) {
                    status.setState(Constants.TaskRunState.RUNNING);
                    taskRunScheduler.addRunningTaskRun(pendingTaskRun);
                }
                // for fe restart, should keep logic same as clearUnfinishedTaskRun
            } else if (toStatus == Constants.TaskRunState.FAILED) {
                status.setErrorMessage(statusChange.getErrorMessage());
                status.setErrorCode(statusChange.getErrorCode());
                status.setState(Constants.TaskRunState.FAILED);
                taskRunManager.getTaskRunHistory().addHistory(status);
            } else if (toStatus == Constants.TaskRunState.MERGED) {
                // This only happened when the task run is merged by others and no run ever.
                LOG.info("Replay update pendingTaskRun which is merged by others, query_id:{}, taskId:{}",
                        statusChange.getQueryId(), taskId);
                status.setErrorMessage(statusChange.getErrorMessage());
                status.setErrorCode(statusChange.getErrorCode());
                status.setState(Constants.TaskRunState.MERGED);
                status.setProgress(100);
                status.setFinishTime(statusChange.getFinishTime());
                taskRunManager.getTaskRunHistory().addHistory(status);
            } else {
                LOG.warn("Illegal TaskRun queryId:{} status transform from {} to {}",
                        statusChange.getQueryId(), fromStatus, toStatus);
            }
        } else if (fromStatus == Constants.TaskRunState.RUNNING &&
                (toStatus == Constants.TaskRunState.SUCCESS || toStatus == Constants.TaskRunState.FAILED)) {
            // NOTE: TaskRuns before the fe restart will be replayed in `replayCreateTaskRun` which
            // will not be rerun because `InsertOverwriteJobRunner.replayStateChange` will replay, so
            // the taskRun's may be PENDING/RUNNING/SUCCESS.
            TaskRun runningTaskRun = taskRunScheduler.removeRunningTask(taskId);
            if (runningTaskRun != null) {
                TaskRunStatus status = runningTaskRun.getStatus();
                if (status.getQueryId().equals(statusChange.getQueryId())) {
                    if (toStatus == Constants.TaskRunState.FAILED) {
                        status.setErrorMessage(statusChange.getErrorMessage());
                        status.setErrorCode(statusChange.getErrorCode());
                    }
                    status.setState(toStatus);
                    status.setProgress(100);
                    status.setFinishTime(statusChange.getFinishTime());
                    status.setExtraMessage(statusChange.getExtraMessage());
                    taskRunManager.getTaskRunHistory().addHistory(status);
                }
            } else {
                // Find the task status from history map.
                String queryId = statusChange.getQueryId();
                TaskRunStatus status = taskRunManager.getTaskRunHistory().getTask(queryId);
                if (status == null) {
                    return;
                }
                // Do update extra message from change status.
                status.setExtraMessage(statusChange.getExtraMessage());
            }
        } else {
            LOG.warn("Illegal TaskRun queryId:{} status transform from {} to {}",
                    statusChange.getQueryId(), fromStatus, toStatus);
        }
    }

    public void replayDropTaskRuns(List<String> queryIdList) {
        for (String queryId : ListUtils.emptyIfNull(queryIdList)) {
            taskRunManager.getTaskRunHistory().removeTaskByQueryId(queryId);
        }
    }

    public void replayAlterRunningTaskRunProgress(Map<Long, Integer> taskRunProgresMap) {
        for (Map.Entry<Long, Integer> entry : taskRunProgresMap.entrySet()) {
            // When replaying the log, the task run may have ended
            // and the status has changed to success or failed
            TaskRun taskRun = taskRunScheduler.getRunningTaskRun(entry.getKey());
            if (taskRun != null) {
                taskRun.getStatus().setProgress(entry.getValue());
            }
        }
    }

    public void replayArchiveTaskRuns(ArchiveTaskRunsLog log) {
        taskRunManager.getTaskRunHistory().replay(log);
    }

    public void removeExpiredTasks() {
        long currentTimeMs = System.currentTimeMillis();

        List<Long> taskIdToDelete = Lists.newArrayList();
        if (!tryTaskLock()) {
            return;
        }
        try {
            List<Task> currentTask = filterTasks(null);
            for (Task task : currentTask) {
                if (task.getType() == Constants.TaskType.PERIODICAL) {
                    TaskSchedule taskSchedule = task.getSchedule();
                    if (taskSchedule == null) {
                        taskIdToDelete.add(task.getId());
                        LOG.warn("clean up a null schedule periodical Task [{}]", task.getName());
                        continue;
                    }
                    // active periodical task should not clean
                    if (task.getState() == Constants.TaskState.ACTIVE) {
                        continue;
                    }
                }
                Long expireTime = task.getExpireTime();
                if (expireTime > 0 && currentTimeMs > expireTime) {
                    taskIdToDelete.add(task.getId());
                }
            }
        } finally {
            taskUnlock();
        }
        // this will do in checkpoint thread and does not need write log
        dropTasks(taskIdToDelete, true);
    }

    public void removeExpiredTaskRuns() {
        taskRunManager.getTaskRunHistory().vacuum();
    }

    @Override
    public Map<String, Long> estimateCount() {
        return ImmutableMap.of("Task", (long) idToTaskMap.size());
    }

    @Override
    public List<Pair<List<Object>, Long>> getSamples() {
        List<Object> taskSamples = idToTaskMap.values()
                .stream()
                .limit(1)
                .collect(Collectors.toList());
        return Lists.newArrayList(Pair.create(taskSamples, (long) idToTaskMap.size()));
    }

    public boolean containTask(String taskName) {
        takeTaskLock();
        try {
            return nameToTaskMap.containsKey(taskName);
        } finally {
            taskUnlock();
        }
    }

    public Task getTask(String taskName) {
        takeTaskLock();
        try {
            return nameToTaskMap.get(taskName);
        } finally {
            taskUnlock();
        }
    }

    public Task getTaskWithoutLock(String taskName) {
        return nameToTaskMap.get(taskName);
    }

}
