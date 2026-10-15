package com.northwind.orders;

import static com.northwind.orders.Shared.CARD_DECLINED;
import static com.northwind.orders.Shared.OUT_OF_STOCK;
import static com.northwind.orders.Shared.PAYMENT_DECLINED;

import com.northwind.orders.Shared.Order;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Calls the downstream systems over HTTP. Every call carries the idempotency key. */
public class OrderActivitiesImpl implements OrderActivities {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;
  private final String workerName;
  // When this thread's current attempt started; start-to-close counts from here, not from scheduling.
  private final ThreadLocal<Long> attemptStartedAt = new ThreadLocal<>();

  public OrderActivitiesImpl(String baseUrl) {
    this(baseUrl, "worker");
  }

  /** workerName prefixes the one-line-per-step log the demo shows next to the UI. */
  public OrderActivitiesImpl(String baseUrl, String workerName) {
    this.base = baseUrl;
    this.workerName = workerName;
  }

  @Override
  public String authorizePayment(Order order, String key) {
    logStep();
    return post("/payments/authorize", Map.of("key", key, "customerId", order.customerId(),
        "amountCents", String.valueOf(order.amountCents())));
  }

  @Override
  public void voidAuthorization(String key) {
    logStep();
    post("/payments/void", Map.of("key", key));
  }

  @Override
  public void reserveInventory(Order order, String key) {
    logStep();
    post("/inventory/reserve", Map.of("key", key, "sku", order.sku(),
        "quantity", String.valueOf(order.quantity())));
  }

  @Override
  public void releaseInventory(String key) {
    logStep();
    post("/inventory/release", Map.of("key", key));
  }

  /**
   * The shipping provider is not idempotent, so a blind retry after a timeout could buy a second
   * label. Look up by our reference first; only create when nothing exists.
   */
  @Override
  public String createShipment(Order order, String key) {
    logStep();
    Reply existing = send("GET", "/shipments", Map.of("reference", key));
    if (existing.status() == 200) {
      return existing.body();
    }
    return post("/shipments", Map.of("reference", key, "customerId", order.customerId(),
        "sku", order.sku(), "quantity", String.valueOf(order.quantity())));
  }

  @Override
  public void cancelShipment(String key) {
    logStep();
    post("/shipments/cancel", Map.of("reference", key));
  }

  @Override
  public void capturePayment(String authorizationId, String key) {
    logStep();
    post("/payments/capture", Map.of("key", key, "authorizationId", authorizationId));
  }

  @Override
  public void sendConfirmation(Order order, String trackingNumber, String key) {
    logStep();
    post("/notify", Map.of("key", key, "orderId", order.orderId(), "tracking", trackingNumber));
  }

  // ---- plumbing ----

  private record Reply(int status, String body) {}

  /** Prints e.g. "[worker-a3f9] 1003 ReserveInventory attempt 1" when a step starts. */
  private void logStep() {
    attemptStartedAt.set(System.currentTimeMillis());
    var info = Activity.getExecutionContext().getInfo();
    String orderId = info.getWorkflowId().replaceFirst("^order-", "");
    System.out.printf("[%s] %-5s %-18s attempt %d%n",
        workerName, orderId, info.getActivityType(), info.getAttempt());
  }

  private String post(String path, Map<String, String> params) {
    Reply r = send("POST", path, params);
    if (r.status() == 200) {
      return r.body();
    }
    throw failure(path, r);
  }

  private Reply send(String method, String path, Map<String, String> params) {
    String form = new LinkedHashMap<>(params).entrySet().stream()
        .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
        .collect(Collectors.joining("&"));
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(20));
      HttpRequest req = method.equals("GET")
          ? b.uri(URI.create(base + path + "?" + form)).GET().build()
          : b.uri(URI.create(base + path))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(BodyPublishers.ofString(form))
              .build();
      HttpResponse<String> res = http.send(req, BodyHandlers.ofString());
      Reply reply = new Reply(res.statusCode(), res.body());
      logIfLate(path);
      if (method.equals("GET") && reply.status() == 404) {
        return reply; // "nothing there" is an answer, not an error
      }
      if (reply.status() >= 400) {
        throw failure(path, reply);
      }
      return reply;
    } catch (IOException e) {
      logFailure("will retry: " + e);
      throw Activity.wrap(e); // retried per the workflow's retry policy
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Activity.wrap(e);
    }
  }

  /** One line in place of the SDK's stack trace, e.g. "... CapturePayment attempt 1: FAILED ...". */
  private void logFailure(String what) {
    var info = Activity.getExecutionContext().getInfo();
    System.out.printf("[%s] %-5s %-18s attempt %d: FAILED (%s)%n", workerName,
        info.getWorkflowId().replaceFirst("^order-", ""), info.getActivityType(), info.getAttempt(), what);
  }

  /**
   * The downstream answered after this attempt's start-to-close timeout. Temporal has already
   * failed the attempt (and likely retried), so it will reject whatever this attempt returns.
   */
  private void logIfLate(String path) {
    var info = Activity.getExecutionContext().getInfo();
    Long started = attemptStartedAt.get();
    if (started == null) {
      return;
    }
    long elapsed = System.currentTimeMillis() - started;
    if (elapsed > info.getStartToCloseTimeout().toMillis()) {
      System.out.printf("[%s] %-5s %-18s attempt %d: late reply from %s after %ds; "
              + "Temporal already timed out this attempt, result discarded%n",
          workerName, info.getWorkflowId().replaceFirst("^order-", ""), info.getActivityType(),
          info.getAttempt(), path, elapsed / 1000);
    }
  }

  private RuntimeException failure(String path, Reply r) {
    String msg = path + " returned " + r.status() + ": " + r.body();
    ApplicationFailure f = switch (r.status()) {
      case 402 -> ApplicationFailure.newNonRetryableFailure(msg, CARD_DECLINED);
      case 409 -> ApplicationFailure.newNonRetryableFailure(msg, OUT_OF_STOCK);
      case 422 -> ApplicationFailure.newNonRetryableFailure(msg, PAYMENT_DECLINED);
      default -> ApplicationFailure.newFailure(msg, "Downstream" + r.status()); // retryable
    };
    logFailure(f.getType() + (f.isNonRetryable() ? ", not retried" : ", will retry") + ": " + msg);
    return f;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
