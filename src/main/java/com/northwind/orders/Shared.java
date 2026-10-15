package com.northwind.orders;

import io.temporal.common.SearchAttributeKey;

public final class Shared {
  private Shared() {}

  public static final String TASK_QUEUE = "orders";

  public static final SearchAttributeKey<String> ORDER_STATUS =
      SearchAttributeKey.forKeyword("OrderStatus");
  public static final SearchAttributeKey<String> CUSTOMER_ID =
      SearchAttributeKey.forKeyword("CustomerId");

  // Failure types the activities raise as non-retryable.
  public static final String CARD_DECLINED = "CardDeclined";
  public static final String OUT_OF_STOCK = "OutOfStock";
  public static final String PAYMENT_DECLINED = "PaymentDeclined"; // capture refused at the end

  public static String workflowId(String orderId) {
    return "order-" + orderId;
  }

  public record Order(
      String orderId, String customerId, String sku, int quantity, long amountCents) {}

  public enum Outcome {
    CONFIRMED,
    REJECTED
  }

  public record OrderResult(String orderId, Outcome outcome, String trackingNumber, String reason) {}
}
