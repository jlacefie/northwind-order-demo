package com.northwind.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;

class OrderWorkflowTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(OrderWorkflowImpl.class)
          .registerSearchAttribute(
              Shared.ORDER_STATUS.getName(), IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
          .setDoNotStart(true)
          .build();

  private static final Order ORDER = new Order("1001", "cust-42", "SKU-RED-MUG", 2, 2599);

  @Test
  void happyPathRunsEveryStepInOrderAndUndoesNothing(
      TestWorkflowEnvironment env, Worker worker, OrderWorkflow workflow) {
    OrderActivities activities = mock(OrderActivities.class, withSettings().withoutAnnotations());
    when(activities.authorizePayment(ORDER, "1001:authorize")).thenReturn("auth-1");
    when(activities.createShipment(ORDER, "1001:shipment")).thenReturn("TRACK-1");
    worker.registerActivitiesImplementations(activities);
    env.start();

    OrderResult result = workflow.processOrder(ORDER);

    assertEquals(new OrderResult("1001", Outcome.CONFIRMED, "TRACK-1", null), result);
    InOrder order = inOrder(activities);
    order.verify(activities).authorizePayment(ORDER, "1001:authorize");
    order.verify(activities).reserveInventory(ORDER, "1001:reserve");
    order.verify(activities).createShipment(ORDER, "1001:shipment");
    order.verify(activities).capturePayment("auth-1", "1001:capture");
    order.verify(activities).sendConfirmation(ORDER, "TRACK-1", "1001:confirm");
    verify(activities, never()).voidAuthorization(any());
    verify(activities, never()).releaseInventory(any());
    verify(activities, never()).cancelShipment(any());
  }
}
