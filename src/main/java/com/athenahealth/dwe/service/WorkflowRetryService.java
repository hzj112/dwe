package com.athenahealth.dwe.service;

import com.athenahealth.dwe.api.ExecutionDtos.ExecutionReceipt;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.persistence.WorkflowStore;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Exposes manual retry as an application operation; the store owns transactional eligibility checks. */
@Service
public class WorkflowRetryService {
    private final WorkflowStore store;

    public WorkflowRetryService(WorkflowStore store) { this.store = store; }

    /** Returns the committed command state, even if workers have already begun the newly created cycle. */
    public ExecutionReceipt retry(UUID executionId) {
        return new ExecutionReceipt(executionId, WorkflowStatus.PENDING, store.retry(executionId));
    }
}
