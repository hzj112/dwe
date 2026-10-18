package com.athenahealth.dwe.api;

import java.net.URI;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.athenahealth.dwe.api.ExecutionDtos.ExecutionDetail;
import com.athenahealth.dwe.api.ExecutionDtos.ExecutionReceipt;
import com.athenahealth.dwe.api.ExecutionDtos.StepDetail;
import com.athenahealth.dwe.api.ExecutionDtos.SubmissionRequest;
import com.athenahealth.dwe.api.ExecutionDtos.Page;
import com.athenahealth.dwe.api.ExecutionDtos.ExecutionSummary;
import com.athenahealth.dwe.api.ExecutionDtos.CycleItem;
import com.athenahealth.dwe.api.ExecutionDtos.AttemptItem;
import com.athenahealth.dwe.persistence.WorkflowQueries;
import com.athenahealth.dwe.persistence.PageCursor.InvalidQueryException;
import com.athenahealth.dwe.service.WorkflowSubmissionService;
import com.athenahealth.dwe.service.WorkflowRetryService;

/** Exposes execution submission and status endpoints; services and repositories do the underlying work. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/executions")
public class ExecutionController {
    private final WorkflowSubmissionService submissions;
    private final WorkflowQueries queries;
    private final WorkflowRetryService retries;

    public ExecutionController(WorkflowSubmissionService submissions, WorkflowQueries queries, WorkflowRetryService retries) {
        this.submissions = submissions;
        this.queries = queries;
        this.retries = retries;
    }

    /** Creates a persisted execution and returns HTTP 201 with its status URL; steps run asynchronously. */
    @PostMapping
    public ResponseEntity<ExecutionReceipt> submit(@RequestBody SubmissionRequest request) {
        ExecutionReceipt receipt = submissions.submit(request.definition(), request.inputs());
        return ResponseEntity.created(URI.create("/api/v1/executions/" + receipt.executionId())).body(receipt);
    }

    /** Returns the execution's current status, submitted definition, and step summaries. */
    @GetMapping("/{id}")
    public ExecutionDetail execution(@PathVariable UUID id) {
        return queries.execution(id);
    }

    /** Lists summaries in creation order; continuation tokens retain the selected status filter. */
    @GetMapping
    public Page<ExecutionSummary> executions(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String cursor) {
        return queries.executions(status, limit, cursor);
    }

    /** Exposes the initial run and subsequent manual retries without changing their state. */
    @GetMapping("/{id}/cycles")
    public Page<CycleItem> cycles(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(required = false) String cursor) {
        return queries.cycles(id, limit, cursor);
    }

    /** Exposes claimed attempts, optionally restricted to one step and/or cycle. */
    @GetMapping("/{id}/attempts")
    public Page<AttemptItem> attempts(@PathVariable UUID id, @RequestParam(required = false) String stepId,
                                     @RequestParam(required = false) String cycleNo,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) String cursor) {
        return queries.attempts(id, stepId, parseCycle(cycleNo), limit, cursor);
    }

    /** Explicitly supplied empty filters are invalid, rather than silently meaning all cycles. */
    private static Integer parseCycle(String cycleNo) {
        if (cycleNo == null) return null;
        try { return Integer.valueOf(cycleNo); }
        catch (NumberFormatException e) { throw new InvalidQueryException("cycleNo must be a positive integer"); }
    }

    /** Returns one step's configuration, attempt counts, output, and latest error. */
    @GetMapping("/{id}/steps/{stepId}")
    public StepDetail step(@PathVariable UUID id, @PathVariable String stepId) {
        return queries.step(id, stepId);
    }

    /** Resumes the original execution; inputs, policy overrides, and even an empty JSON object are not accepted. */
    @PostMapping("/{id}/retry")
    public ExecutionReceipt retry(@PathVariable UUID id, @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retry does not accept a request body");
        }
        return retries.retry(id);
    }
}
