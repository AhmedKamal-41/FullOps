package com.ahmedali.fulfillops.order.web;

import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.IncidentSpecifications;
import com.ahmedali.fulfillops.order.domain.IncidentStatus;
import com.ahmedali.fulfillops.order.domain.OperationsIncident;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.service.IncidentActionService;
import com.ahmedali.fulfillops.order.web.dto.AssignIncidentRequest;
import com.ahmedali.fulfillops.order.web.dto.IncidentResponse;
import com.ahmedali.fulfillops.order.web.dto.ResolveIncidentRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The incident lifecycle — list, acknowledge, assign, resolve. OPERATOR/ADMIN only (see
 * SecurityConfig). See docs/OPERATIONS_RUNBOOK.md for what each action means.
 */
@RestController
@RequestMapping("/api/v1/ops/incidents")
@Validated
public class IncidentController {

  private final OperationsIncidentRepository incidentRepository;
  private final IncidentActionService incidentActionService;

  public IncidentController(
      OperationsIncidentRepository incidentRepository,
      IncidentActionService incidentActionService) {
    this.incidentRepository = incidentRepository;
    this.incidentActionService = incidentActionService;
  }

  @Operation(summary = "List incidents, optionally filtered by status, kind, and/or order")
  @GetMapping
  public Page<IncidentResponse> list(
      @RequestParam(required = false) IncidentStatus status,
      @RequestParam(required = false) IncidentKind kind,
      @RequestParam(required = false) UUID orderId,
      Pageable pageable) {
    Specification<OperationsIncident> spec = Specification.allOf();
    if (status != null) {
      spec = spec.and(IncidentSpecifications.hasStatus(status));
    }
    if (kind != null) {
      spec = spec.and(IncidentSpecifications.hasKind(kind));
    }
    if (orderId != null) {
      spec = spec.and(IncidentSpecifications.hasOrderId(orderId));
    }
    return incidentRepository.findAll(spec, pageable).map(IncidentController::toResponse);
  }

  @Operation(summary = "Acknowledge an incident")
  @PostMapping("/{incidentId}/acknowledge")
  public IncidentResponse acknowledge(
      @PathVariable UUID incidentId, @AuthenticationPrincipal Jwt jwt) {
    return toResponse(incidentActionService.acknowledge(incidentId, jwt.getSubject()));
  }

  @Operation(summary = "Assign an incident to an operator")
  @PostMapping("/{incidentId}/assign")
  public IncidentResponse assign(
      @PathVariable UUID incidentId,
      @Valid @RequestBody AssignIncidentRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return toResponse(
        incidentActionService.assign(incidentId, jwt.getSubject(), request.assignee()));
  }

  @Operation(summary = "Resolve an incident")
  @PostMapping("/{incidentId}/resolve")
  public IncidentResponse resolve(
      @PathVariable UUID incidentId,
      @Valid @RequestBody(required = false) ResolveIncidentRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    String resolutionNote = request == null ? null : request.resolutionNote();
    return toResponse(incidentActionService.resolve(incidentId, jwt.getSubject(), resolutionNote));
  }

  private static IncidentResponse toResponse(OperationsIncident incident) {
    return new IncidentResponse(
        incident.getIncidentId(),
        incident.getOrderId(),
        incident.getKind().name(),
        incident.getDetail(),
        incident.getStatus().name(),
        incident.getCreatedAt(),
        incident.getAcknowledgedAt(),
        incident.getAcknowledgedBy(),
        incident.getAssignedTo(),
        incident.getAssignedAt(),
        incident.getResolvedAt(),
        incident.getResolvedBy(),
        incident.getResolutionNote());
  }
}
