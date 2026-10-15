package com.northwind.orders;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;

/**
 * Connects to Temporal Cloud. TEMPORAL_ADDRESS, TEMPORAL_NAMESPACE and TEMPORAL_API_KEY come from
 * the environment (.env, passed in by docker compose); nothing is hard-coded.
 */
public final class TemporalClients {
  private TemporalClients() {}

  public static WorkflowClient client() {
    return client(null);
  }

  /** identity shows up in the UI on every event this client's worker handles; null = SDK default. */
  public static WorkflowClient client(String identity) {
    String address = required("TEMPORAL_ADDRESS");
    String namespace = required("TEMPORAL_NAMESPACE");
    String apiKey = required("TEMPORAL_API_KEY");

    WorkflowServiceStubsOptions opts = WorkflowServiceStubsOptions.newBuilder()
        .setTarget(address)
        .setEnableHttps(true)
        .addApiKey(() -> apiKey)
        .build();
    WorkflowServiceStubs stubs = WorkflowServiceStubs.newConnectedServiceStubs(opts, Duration.ofSeconds(30));
    WorkflowClientOptions.Builder clientOpts = WorkflowClientOptions.newBuilder().setNamespace(namespace);
    if (identity != null) {
      clientOpts.setIdentity(identity);
    }
    return WorkflowClient.newInstance(stubs, clientOpts.build());
  }

  private static String required(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException(name + " is not set; copy .env.example to .env and fill it in");
    }
    return v;
  }

  public static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? fallback : v;
  }
}
