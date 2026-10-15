package com.northwind.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.northwind.fakes.FakeServices;
import com.northwind.orders.Shared.Order;
import com.northwind.orders.Shared.OrderResult;
import com.northwind.orders.Shared.Outcome;
import com.sun.net.httpserver.HttpServer;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The ambiguous shipping timeout against the real fakes over HTTP. Runs in real time (about 7s):
 * the fake creates the label, stalls past the 5s activity timeout, and the retry must find the
 * existing label instead of buying a second one.
 */
class OrderShippingTimeoutTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(OrderWorkflowImpl.class)
          .registerSearchAttribute(
              Shared.ORDER_STATUS.getName(), IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
          .setUseTimeskipping(false)
          .setDoNotStart(true)
          .build();

  @Test
  void shippingTimeoutAfterLabelCreatedDoesNotBuyASecondLabel(TestWorkflowEnvironment env,
      Worker worker, OrderWorkflow workflow) throws Exception {
    HttpServer fakes = FakeServices.start(0);
    try {
      String base = "http://localhost:" + fakes.getAddress().getPort();
      worker.registerActivitiesImplementations(new OrderActivitiesImpl(base));
      env.start();

      // "slow-shipping": first POST /shipments creates the label then stalls past the 5s timeout.
      OrderResult r = workflow.processOrder(new Order("3001", "slow-shipping", "SKU-RED-MUG", 1, 1000));

      assertEquals(Outcome.CONFIRMED, r.outcome());
      String state = get(base + "/state");
      assertTrue(state.contains("labels=1"), state);
      assertTrue(state.contains("captured=1"), state);
    } finally {
      fakes.stop(0);
    }
  }

  private static String get(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(URI.create(url)).build(), BodyHandlers.ofString())
        .body();
  }
}
