package com.northwind.fakes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-ins for Northwind's payment gateway, inventory system, shipping provider and notifier, in
 * one process. Requests and responses are form-encoded / plain text to keep the demo free of
 * extra libraries.
 *
 * <p>Magic values that trigger the demo scenarios:
 *
 * <ul>
 *   <li>customer {@code declined}: the gateway declines the card (HTTP 402)
 *   <li>sku {@code SKU-OUT}: inventory says out of stock (HTTP 409)
 *   <li>customer {@code slow-shipping}: the shipping provider creates the label, then takes 12s to
 *       answer, the first time it sees that reference. The caller times out after 5s.
 *   <li>sku {@code SKU-SLOW}: inventory stalls 8s on the first attempt, a window to kill the worker
 *   <li>customer {@code capture-fails}: authorize, reserve and label succeed, then the gateway
 *       refuses the capture (HTTP 422), so the saga undoes all three
 * </ul>
 *
 * <p>Like the real provider we are assuming, POST /shipments is NOT idempotent: every call creates
 * a new label. Only GET /shipments?reference=... lets a caller find out what already exists.
 */
public class FakeServices {

  static final Map<String, String> authorizations = new ConcurrentHashMap<>();
  // authorization ID -> customer, so capture can recognise the capture-fails customer.
  static final Map<String, String> authCustomer = new ConcurrentHashMap<>();
  static final Set<String> voided = ConcurrentHashMap.newKeySet();
  static final Set<String> captured = ConcurrentHashMap.newKeySet();
  static final Set<String> reservations = ConcurrentHashMap.newKeySet();
  // Every label ever created: tracking -> client reference. Duplicates would show up here.
  static final Map<String, String> labels = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
  // Lookup index the provider offers: client reference -> tracking.
  static final Map<String, String> byReference = new ConcurrentHashMap<>();
  // Cancelled labels stay in `labels` (the provider keeps a record) and are listed as CANCELLED.
  static final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  static final Set<String> slowedOnce = ConcurrentHashMap.newKeySet();
  static final AtomicInteger seq = new AtomicInteger(1000);

  record Reply(int status, String body) {
    static Reply ok(String body) {
      return new Reply(200, body);
    }
  }

  interface Handler {
    Reply handle(Map<String, String> p) throws Exception;
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    HttpServer server = start(port);
    System.out.println("Fake services listening on :" + server.getAddress().getPort());
  }

  /** Starts the fakes; port 0 picks a free port (used by tests). */
  public static HttpServer start(int port) throws IOException {
    reset();
    HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
    s.setExecutor(Executors.newCachedThreadPool());

    route(s, "/payments/authorize", p -> {
      if ("declined".equals(p.get("customerId"))) return new Reply(402, "card declined");
      String auth = authorizations.computeIfAbsent(p.get("key"), k -> "auth-" + seq.incrementAndGet());
      authCustomer.put(auth, p.get("customerId"));
      return Reply.ok(auth);
    });
    route(s, "/payments/void", p -> {
      voided.add(p.get("key"));
      return Reply.ok("ok");
    });
    route(s, "/payments/capture", p -> {
      if ("capture-fails".equals(authCustomer.get(p.get("authorizationId")))) {
        return new Reply(422, "capture declined");
      }
      captured.add(p.get("key"));
      return Reply.ok("ok");
    });

    route(s, "/inventory/reserve", p -> {
      if ("SKU-OUT".equals(p.get("sku"))) return new Reply(409, "out of stock");
      if ("SKU-SLOW".equals(p.get("sku")) && slowedOnce.add("reserve:" + p.get("key"))) {
        System.out.println("    (inventory: stalling 8s on first attempt, time to kill the worker)");
        Thread.sleep(8_000);
      }
      reservations.add(p.get("key"));
      return Reply.ok("ok");
    });
    route(s, "/inventory/release", p -> {
      reservations.remove(p.get("key"));
      return Reply.ok("ok");
    });

    route(s, "/shipments", p -> {
      String ref = p.get("reference");
      if (p.containsKey("method") && p.get("method").equals("GET")) {
        if (ref == null) {
          StringBuilder sb = new StringBuilder("labels: " + labels.size() + "\n");
          synchronized (labels) {
            labels.forEach((t, r) -> sb.append(t).append("  reference=").append(r)
                .append(cancelled.contains(t) ? "  CANCELLED" : "").append("\n"));
          }
          return Reply.ok(sb.toString());
        }
        String t = byReference.get(ref);
        return t == null ? new Reply(404, "no label") : Reply.ok(t);
      }
      // Not idempotent: always makes a new label.
      String tracking = "TRK-" + seq.incrementAndGet();
      labels.put(tracking, ref);
      byReference.put(ref, tracking);
      if ("slow-shipping".equals(p.get("customerId")) && slowedOnce.add(ref)) {
        System.out.println("    (shipping provider: label created, now stalling 12s before answering)");
        Thread.sleep(12_000);
      }
      return Reply.ok(tracking);
    });
    route(s, "/shipments/cancel", p -> {
      String t = byReference.remove(p.get("reference"));
      if (t != null) cancelled.add(t);
      return Reply.ok("ok");
    });

    route(s, "/notify", p -> Reply.ok("sent"));

    // One order's footprint across the downstream systems: GET /order?id=7004
    route(s, "/order", p -> {
      String id = p.get("id");
      String auth = authorizations.get(id + ":authorize");
      String payment = auth == null ? "none"
          : captured.contains(id + ":capture") ? "captured (" + auth + ")"
          : voided.contains(id + ":authorize") ? "authorized, then voided (" + auth + ")"
          : "authorized (" + auth + ")";
      String stock = reservations.contains(id + ":reserve") ? "reserved" : "not reserved";
      String label = "none";
      synchronized (labels) {
        for (var e : labels.entrySet()) {
          if ((id + ":shipment").equals(e.getValue())) {
            label = e.getKey() + (cancelled.contains(e.getKey()) ? " CANCELLED" : " active");
          }
        }
      }
      return Reply.ok("payment: " + payment + "\ninventory: " + stock + "\nlabel: " + label + "\n");
    });

    route(s, "/state", p ->
        Reply.ok(
            "authorizations=" + authorizations.size()
                + " captured=" + captured.size()
                + " voided=" + voided.size()
                + " reserved=" + reservations.size()
                + " labels=" + labels.size()
                + " cancelled=" + cancelled.size() + "\n"));

    s.start();
    return s;
  }

  static void reset() {
    authorizations.clear();
    authCustomer.clear();
    cancelled.clear();
    voided.clear();
    captured.clear();
    reservations.clear();
    labels.clear();
    byReference.clear();
    slowedOnce.clear();
  }

  private static void route(HttpServer s, String path, Handler h) {
    s.createContext(path, ex -> {
      Reply r;
      Map<String, String> params = new LinkedHashMap<>();
      try {
        parse(ex.getRequestURI().getRawQuery(), params);
        parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), params);
        params.put("method", ex.getRequestMethod());
        r = h.handle(params);
      } catch (Exception e) {
        r = new Reply(500, String.valueOf(e));
      }
      System.out.printf(
          "[fake] %s %s %s -> %d%n",
          ex.getRequestMethod(), path, summary(params), r.status());
      send(ex, r);
    });
  }

  private static String summary(Map<String, String> p) {
    Map<String, String> copy = new LinkedHashMap<>(p);
    copy.remove("method");
    return copy.toString();
  }

  private static void parse(String s, Map<String, String> into) {
    if (s == null || s.isBlank()) return;
    for (String pair : s.split("&")) {
      int i = pair.indexOf('=');
      if (i < 0) continue;
      into.put(
          URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
    }
  }

  private static void send(HttpExchange ex, Reply r) throws IOException {
    byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(r.status(), b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }
}
