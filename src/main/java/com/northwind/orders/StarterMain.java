package com.northwind.orders;

import com.northwind.orders.Shared.Order;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributes;

/**
 * CLI.
 *
 * <pre>
 *   start  &lt;orderId&gt; [customerId] [sku]   start an order and wait for the result
 *   submit &lt;orderId&gt; [customerId] [sku]   start an order and return immediately
 *   status &lt;orderId&gt;                      ask a running order where it is (query)
 *   result &lt;orderId&gt;                      wait for an order's result
 * </pre>
 */
public class StarterMain {
  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: start|submit|status|result <orderId> [customerId] [sku]");
      System.exit(2);
    }
    String cmd = args[0];
    String orderId = args[1];
    WorkflowClient client = TemporalClients.client();
    String wfId = Shared.workflowId(orderId);

    switch (cmd) {
      case "start", "submit" -> {
        String customer = args.length > 2 ? args[2] : "cust-42";
        String sku = args.length > 3 ? args[3] : "SKU-RED-MUG";
        Order order = new Order(orderId, customer, sku, 2, 2599);
        OrderWorkflow wf = client.newWorkflowStub(
            OrderWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(wfId)
                .setTaskQueue(Shared.TASK_QUEUE)
                // One order ID, one run: a second submit is rejected even after the first finished
                // (within the namespace's retention period). The default only rejects while running.
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setTypedSearchAttributes(
                    SearchAttributes.newBuilder().set(Shared.CUSTOMER_ID, customer).build())
                .build());
        try {
          WorkflowClient.start(wf::processOrder, order);
        } catch (WorkflowExecutionAlreadyStarted e) {
          System.out.println("rejected: " + wfId + " was already submitted (run "
              + e.getExecution().getRunId() + "). Nothing was charged or shipped again.");
          System.exit(1);
        }
        if (cmd.equals("submit")) {
          System.out.println("started " + wfId);
        } else {
          System.out.println("started " + wfId + ", waiting...");
          System.out.println(WorkflowStub.fromTyped(wf).getResult(Shared.OrderResult.class));
        }
      }
      case "status" -> {
        // A query runs on a worker, so it fails while none is polling. The order itself is safe.
        try {
          System.out.println(client.newWorkflowStub(OrderWorkflow.class, wfId).status());
        } catch (WorkflowServiceException e) {
          System.out.println("no worker to answer the query (order is still durable in Temporal): "
              + e.getCause().getMessage());
        }
      }
      case "result" ->
          System.out.println(client.newUntypedWorkflowStub(wfId)
              .getResult(Shared.OrderResult.class));
      default -> {
        System.err.println("unknown command " + cmd);
        System.exit(2);
      }
    }
    System.exit(0);
  }
}
