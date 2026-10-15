package com.northwind.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.northwind.orders.Shared.Order;
import com.northwind.orders.Shared.OrderResult;
import com.northwind.orders.Shared.Outcome;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;

/** Business rejection and compensation, with mocked activities. */
class OrderFlowsTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(OrderWorkflowImpl.class)
          .registerSearchAttribute(
              Shared.ORDER_STATUS.getName(), IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
          .setDoNotStart(true)
          .build();

  private static Order order(String id, String customer, String sku) {
    return new Order(id, customer, sku, 1, 1000);
  }

  @Test
  void outOfStockVoidsThePaymentAndRejects(
      TestWorkflowEnvironment env, Worker worker, OrderWorkflow workflow) {
    Order o = order("2001", "cust-1", "SKU-OUT");
    OrderActivities a = mock(OrderActivities.class, withSettings().withoutAnnotations());
    when(a.authorizePayment(o, "2001:authorize")).thenReturn("auth-1");
    org.mockito.Mockito.doThrow(
            ApplicationFailure.newNonRetryableFailure("none left", Shared.OUT_OF_STOCK))
        .when(a)
        .reserveInventory(o, "2001:reserve");
    worker.registerActivitiesImplementations(a);
    env.start();

    OrderResult r = workflow.processOrder(o);

    assertEquals(Outcome.REJECTED, r.outcome());
    assertEquals(Shared.OUT_OF_STOCK, r.reason());
    verify(a).voidAuthorization("2001:authorize");
    verify(a, never()).createShipment(any(), any());
    verify(a, never()).capturePayment(any(), any());
  }

  @Test
  void shipmentFailureUndoesEverythingAndFailsTheWorkflow(
      TestWorkflowEnvironment env, Worker worker, OrderWorkflow workflow) {
    Order o = order("2002", "cust-1", "SKU-RED-MUG");
    OrderActivities a = mock(OrderActivities.class, withSettings().withoutAnnotations());
    when(a.authorizePayment(o, "2002:authorize")).thenReturn("auth-2");
    when(a.createShipment(o, "2002:shipment"))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("carrier down", "CarrierDown"));
    worker.registerActivitiesImplementations(a);
    env.start();

    assertThrows(WorkflowFailedException.class, () -> workflow.processOrder(o));

    verify(a).cancelShipment("2002:shipment");
    verify(a).releaseInventory("2002:reserve");
    verify(a).voidAuthorization("2002:authorize");
    verify(a, never()).capturePayment(any(), any());
  }

  @Test
  void declinedCaptureUndoesAllThreeStepsNewestFirstAndRejects(
      TestWorkflowEnvironment env, Worker worker, OrderWorkflow workflow) {
    Order o = order("2003", "capture-fails", "SKU-RED-MUG");
    OrderActivities a = mock(OrderActivities.class, withSettings().withoutAnnotations());
    when(a.authorizePayment(o, "2003:authorize")).thenReturn("auth-3");
    when(a.createShipment(o, "2003:shipment")).thenReturn("TRK-3");
    org.mockito.Mockito.doThrow(
            ApplicationFailure.newNonRetryableFailure("capture declined", Shared.PAYMENT_DECLINED))
        .when(a)
        .capturePayment("auth-3", "2003:capture");
    worker.registerActivitiesImplementations(a);
    env.start();

    OrderResult r = workflow.processOrder(o);

    assertEquals(Outcome.REJECTED, r.outcome());
    assertEquals(Shared.PAYMENT_DECLINED, r.reason());
    InOrder undo = inOrder(a);
    undo.verify(a).capturePayment("auth-3", "2003:capture");
    undo.verify(a).cancelShipment("2003:shipment");
    undo.verify(a).releaseInventory("2003:reserve");
    undo.verify(a).voidAuthorization("2003:authorize");
    verify(a, never()).sendConfirmation(any(), any(), any());
  }
}
