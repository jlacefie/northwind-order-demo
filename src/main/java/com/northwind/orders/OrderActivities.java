package com.northwind.orders;

import com.northwind.orders.Shared.Order;
import io.temporal.activity.ActivityInterface;

/**
 * One method per external call. Every method takes an idempotency key and must be safe to call
 * more than once with the same key. Undo methods must also be safe when the forward call never
 * happened.
 */
@ActivityInterface
public interface OrderActivities {

  String authorizePayment(Order order, String idempotencyKey);

  void voidAuthorization(String idempotencyKey);

  void reserveInventory(Order order, String idempotencyKey);

  void releaseInventory(String idempotencyKey);

  /** Returns the tracking number. */
  String createShipment(Order order, String idempotencyKey);

  void cancelShipment(String idempotencyKey);

  void capturePayment(String authorizationId, String idempotencyKey);

  void sendConfirmation(Order order, String trackingNumber, String idempotencyKey);
}
