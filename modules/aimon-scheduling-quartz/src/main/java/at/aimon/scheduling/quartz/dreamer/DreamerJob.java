/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz.dreamer;

import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.core.memory.dreamer.DreamerCycleSummary;
import at.aimon.core.memory.dreamer.DreamerEngine;

/**
 * Quartz {@link Job} that runs one consolidation cycle for a single workspace.
 *
 * <p>
 * Per design doc §6.3.1 the dreamer is a long-lived scheduled component,
 * orthogonal to any agent runtime. The job carries only the
 * {@code workspaceId} in its {@link JobDataMap} (so it survives the JDBC job
 * store's serialization round-trip) and resolves both {@link WorkspaceStore}
 * and {@link DreamerEngine} from the {@code SchedulerContext} at exec time.
 * Use {@link DreamerJobRegistrar} to wire deps + schedule the job.
 *
 * <p>
 * The job is expected to be idempotent at the cycle level: re-running for the
 * same workspace just re-evaluates current observations. Exceptions thrown by
 * the engine are wrapped in {@link JobExecutionException} so Quartz can apply
 * its own retry / misfire policy.
 */
public final class DreamerJob implements Job {

    /** {@link JobDataMap} key carrying the workspace id (string). */
    public static final String DATA_KEY_WORKSPACE_ID = "workspaceId";

    /** {@code SchedulerContext} key under which the {@link WorkspaceStore} is published. */
    public static final String CONTEXT_KEY_WORKSPACE_STORE = "memoryWorkspaceStore";

    /** {@code SchedulerContext} key under which the {@link DreamerEngine} is published. */
    public static final String CONTEXT_KEY_DREAMER_ENGINE = "memoryDreamerEngine";

    private static final Logger log = LoggerFactory.getLogger(DreamerJob.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String workspaceId = context.getMergedJobDataMap().getString(DATA_KEY_WORKSPACE_ID);
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new JobExecutionException("DreamerJob requires '" + DATA_KEY_WORKSPACE_ID + "' in JobDataMap");
        }

        WorkspaceStore workspaceStore;
        DreamerEngine dreamerEngine;
        try {
            workspaceStore = (WorkspaceStore) context.getScheduler().getContext().get(CONTEXT_KEY_WORKSPACE_STORE);
            dreamerEngine = (DreamerEngine) context.getScheduler().getContext().get(CONTEXT_KEY_DREAMER_ENGINE);
        } catch (SchedulerException e) {
            throw new JobExecutionException("Failed to read scheduler context for DreamerJob", e);
        }
        if (workspaceStore == null) {
            throw new JobExecutionException(
                    "WorkspaceStore not registered in scheduler context (key=" + CONTEXT_KEY_WORKSPACE_STORE + ")");
        }
        if (dreamerEngine == null) {
            throw new JobExecutionException(
                    "DreamerEngine not registered in scheduler context (key=" + CONTEXT_KEY_DREAMER_ENGINE + ")");
        }

        Workspace workspace = workspaceStore.findById(workspaceId)
                .orElseThrow(() -> new JobExecutionException("Workspace not found: " + workspaceId));

        try {
            DreamerCycleSummary summary = dreamerEngine.consolidate(workspace);
            log.debug("DreamerJob completed for workspace={}: {}", workspaceId, summary);
        } catch (RuntimeException e) {
            log.error("DreamerJob failed for workspace={}: {}", workspaceId, e.getMessage(), e);
            throw new JobExecutionException("Dreamer cycle failed for workspace " + workspaceId, e);
        }
    }
}
