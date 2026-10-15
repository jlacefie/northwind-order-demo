package com.northwind.orders;

import com.northwind.orders.Shared.Order;
import com.northwind.orders.Shared.OrderResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

  @WorkflowMethod
  OrderResult processOrder(Order order);

  @QueryMethod
  String status();
}
