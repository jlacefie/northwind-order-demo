package com.northwind.orders;

import static com.northwind.orders.Shared.CARD_DECLINED;
import static com.northwind.orders.Shared.ORDER_STATUS;
import static com.northwind.orders.Shared.OUT_OF_STOCK;
import static com.northwind.orders.Shared.PAYMENT_DECLINED;

import com.northwind.orders.Shared.Order;
import com.northwind.orders.Shared.OrderResult;
import com.northwind.orders.Shared.Outcome;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Set;

public class OrderWorkflowImpl implements OrderWorkflow {

  private static final Set<String> BUSINESS_REJECTIONS = Set.of(CARD_DECLINED, OUT_OF_STOCK, PAYMENT_DECLINED);

  private final OrderActivities activities =
      Workflow.newActivityStub(
          OrderActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(5))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(10))
                      .setMaximumAttempts(5)
                      .build())
              .build());

  private String status = "RECEIVED";

  @Override
  public OrderResult processOrder(Order order) {
    String id = order.orderId();
    Saga saga = new Saga(new Saga.Options.Builder().build());
    String trackingNumber;

    try {
      // Each undo step is registered before its forward step: a call that timed out may still
      // have taken effect, so the undo must run either way.
      setStatus("AUTHORIZING_PAYMENT");
      saga.addCompensation(activities::voidAuthorization, id + ":authorize");
      String authorizationId = activities.authorizePayment(order, id + ":authorize");

      setStatus("RESERVING_INVENTORY");
      saga.addCompensation(activities::releaseInventory, id + ":reserve");
      activities.reserveInventory(order, id + ":reserve");

      setStatus("CREATING_SHIPMENT");
      saga.addCompensation(activities::cancelShipment, id + ":shipment");
      trackingNumber = activities.createShipment(order, id + ":shipment");

      setStatus("CAPTURING_PAYMENT");
      activities.capturePayment(authorizationId, id + ":capture");
    } catch (ActivityFailure e) {
      setStatus("COMPENSATING");
      // Detached so the undo steps still run if the workflow itself was cancelled.
      Workflow.newDetachedCancellationScope(saga::compensate).run();

      String rejection = businessRejection(e);
      if (rejection != null) {
        setStatus("REJECTED");
        return new OrderResult(id, Outcome.REJECTED, null, rejection);
      }
      setStatus("FAILED");
      throw e;
    }

    // Past this point the customer has paid and the parcel exists. A failed email must not undo
    // either, so the confirmation sits outside the saga.
    setStatus("SENDING_CONFIRMATION");
    activities.sendConfirmation(order, trackingNumber, id + ":confirm");

    setStatus("CONFIRMED");
    return new OrderResult(id, Outcome.CONFIRMED, trackingNumber, null);
  }

  @Override
  public String status() {
    return status;
  }

  private void setStatus(String newStatus) {
    status = newStatus;
    Workflow.upsertTypedSearchAttributes(ORDER_STATUS.valueSet(newStatus));
  }

  /** Returns the failure type if the activity failed for a business reason, otherwise null. */
  private static String businessRejection(ActivityFailure e) {
    if (e.getCause() instanceof ApplicationFailure cause
        && BUSINESS_REJECTIONS.contains(cause.getType())) {
      return cause.getType();
    }
    return null;
  }
}
