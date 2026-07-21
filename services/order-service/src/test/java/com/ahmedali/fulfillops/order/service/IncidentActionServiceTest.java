package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.order.domain.IncidentActionHistoryRepository;
import com.ahmedali.fulfillops.order.domain.IncidentActionType;
import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.IncidentStatus;
import com.ahmedali.fulfillops.order.domain.OperationsIncident;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentActionServiceTest {

  private final OperationsIncidentRepository incidentRepository =
      mock(OperationsIncidentRepository.class);
  private final IncidentActionHistoryRepository actionHistoryRepository =
      mock(IncidentActionHistoryRepository.class);
  private final OperationsProjectionUpdater projectionUpdater =
      mock(OperationsProjectionUpdater.class);

  private final IncidentActionService service =
      new IncidentActionService(incidentRepository, actionHistoryRepository, projectionUpdater);

  @Test
  void acknowledgingAnOpenIncidentMovesItToAcknowledgedAndRecordsTheAction() {
    OperationsIncident incident = openIncident();
    UUID incidentId = incident.getIncidentId();
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

    OperationsIncident result = service.acknowledge(incidentId, "operator.demo");

    assertThat(result.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
    assertThat(result.getAcknowledgedBy()).isEqualTo("operator.demo");
    verify(actionHistoryRepository)
        .save(argThatMatchesAction(incidentId, IncidentActionType.ACKNOWLEDGED, "operator.demo"));
  }

  @Test
  void assigningAnIncidentRecordsTheAssigneeInTheActionDetail() {
    OperationsIncident incident = openIncident();
    UUID incidentId = incident.getIncidentId();
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

    OperationsIncident result = service.assign(incidentId, "operator.demo", "operator.other");

    assertThat(result.getAssignedTo()).isEqualTo("operator.other");
  }

  @Test
  void resolvingAnIncidentRecalculatesTheProjectionsOpenIncidentCount() {
    OperationsIncident incident = openIncident();
    UUID incidentId = incident.getIncidentId();
    UUID orderId = incident.getOrderId();
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

    OperationsIncident result = service.resolve(incidentId, "operator.demo", "fixed manually");

    assertThat(result.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    assertThat(result.getResolutionNote()).isEqualTo("fixed manually");
    verify(projectionUpdater).recalculateOpenIncidentCount(orderId);
  }

  @Test
  void resolvingAnAlreadyResolvedIncidentIsRejected() {
    OperationsIncident incident = openIncident();
    UUID incidentId = incident.getIncidentId();
    incident.resolve("someone.else", "already fixed");
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

    assertThatThrownBy(() -> service.resolve(incidentId, "operator.demo", "note"))
        .isInstanceOf(IncidentAlreadyResolvedException.class);
  }

  @Test
  void acknowledgingAnUnknownIncidentIsRejected() {
    UUID incidentId = UUID.randomUUID();
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.acknowledge(incidentId, "operator.demo"))
        .isInstanceOf(IncidentNotFoundException.class);
  }

  @Test
  void acknowledgingAnAlreadyAcknowledgedIncidentIsStillAllowed() {
    // ACKNOWLEDGED is not a terminal state — re-acknowledging (e.g. a different operator
    // double-checking) is harmless, only RESOLVED blocks further actions.
    OperationsIncident incident = openIncident();
    incident.acknowledge("first.operator");
    UUID incidentId = incident.getIncidentId();
    when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

    OperationsIncident result = service.acknowledge(incidentId, "second.operator");

    assertThat(result.getAcknowledgedBy()).isEqualTo("second.operator");
    verify(actionHistoryRepository, times(1))
        .save(argThatMatchesAction(incidentId, IncidentActionType.ACKNOWLEDGED, "second.operator"));
  }

  private static OperationsIncident openIncident() {
    return new OperationsIncident(UUID.randomUUID(), IncidentKind.CANCELLATION_STUCK, "stuck");
  }

  private static com.ahmedali.fulfillops.order.domain.IncidentActionHistory argThatMatchesAction(
      UUID incidentId, IncidentActionType action, String actor) {
    return org.mockito.ArgumentMatchers.argThat(
        savedAction ->
            savedAction.getIncidentId().equals(incidentId)
                && savedAction.getAction() == action
                && savedAction.getActor().equals(actor));
  }
}
