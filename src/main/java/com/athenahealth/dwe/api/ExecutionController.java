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
import org.springframework.web.bind.annotation.RestController;

import com.athenahealth.dwe.api.ExecutionDtos.ExecutionDetail;
import com.athenahealth.dwe.api.ExecutionDtos.ExecutionReceipt;
import com.athenahealth.dwe.api.ExecutionDtos.StepDetail;
import com.athenahealth.dwe.api.ExecutionDtos.SubmissionRequest;
import com.athenahealth.dwe.persistence.WorkflowQueries;
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
