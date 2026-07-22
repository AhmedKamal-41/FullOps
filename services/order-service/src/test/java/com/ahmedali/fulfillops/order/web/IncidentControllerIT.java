package com.ahmedali.fulfillops.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.OperationsIncident;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GET /api/v1/ops/incidents against a real Postgres — the status/kind/orderId filters
 * (IncidentSpecifications) combined, not just individually, since Order Detail needs
 * "this order's unresolved incidents" specifically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class IncidentControllerIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OperationsIncidentRepository incidentRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void theOrderIdFilterCombinesWithStatusToFindOneOrdersOwnIncidents() throws Exception {
    UUID orderOneId = seedOrder();
    UUID orderTwoId = seedOrder();
    seedIncident(orderOneId, IncidentKind.CANCELLATION_STUCK);
    seedIncident(orderOneId, IncidentKind.COMPENSATION_EXHAUSTED);
    seedIncident(orderTwoId, IncidentKind.CANCELLATION_STUCK);

    String responseJson =
        mockMvc
            .perform(
                get("/api/v1/ops/incidents")
                    .param("orderId", orderOneId.toString())
                    .param("status", "OPEN")
                    .with(operatorJwt()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode content = objectMapper.readTree(responseJson).get("content");
    assertThat(content.size()).isEqualTo(2);
    for (JsonNode incident : content) {
      assertThat(incident.get("orderId").asString()).isEqualTo(orderOneId.toString());
    }
  }

  @Test
  void theOrderIdFilterAloneReturnsNothingForAnOrderWithNoIncidents() throws Exception {
    UUID orderWithNoIncidents = seedOrder();

    String responseJson =
        mockMvc
            .perform(
                get("/api/v1/ops/incidents")
                    .param("orderId", orderWithNoIncidents.toString())
                    .with(operatorJwt()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(objectMapper.readTree(responseJson).get("content").size()).isEqualTo(0);
  }

  private UUID seedOrder() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(OrderStatus.REQUIRES_REVIEW);
    return orderRepository.save(order).getOrderId();
  }

  private void seedIncident(UUID orderId, IncidentKind kind) {
    incidentRepository.save(
        new OperationsIncident(orderId, kind, "seeded for IncidentControllerIT"));
  }

  private static JwtRequestPostProcessor operatorJwt() {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
  }
}
