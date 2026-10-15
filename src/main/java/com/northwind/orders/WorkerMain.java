package com.northwind.orders;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

public class WorkerMain {
  public static void main(String[] args) {
    // A late result for a timed-out attempt is rejected by the server; the SDK logs that with a
    // stack trace. OrderActivitiesImpl prints one readable line for it instead.
    System.setProperty("org.slf4j.simpleLogger.log.io.temporal.internal.worker.ActivityWorker", "error");
    // Same for activity failures: OrderActivitiesImpl prints one "FAILED (...)" line per failure.
    System.setProperty(
        "org.slf4j.simpleLogger.log.io.temporal.internal.activity", "error");
    // A new name on every start, so a restarted worker is visibly a different process in the UI.
    String name = "worker-" + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
    WorkflowClient client = TemporalClients.client(name);
    String fakes = TemporalClients.env("FAKES_URL", "http://localhost:8080");

    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(Shared.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
    worker.registerActivitiesImplementations(new OrderActivitiesImpl(fakes, name));
    factory.start();
    System.out.println("[" + name + "] started, polling task queue '" + Shared.TASK_QUEUE
        + "' on " + client.getOptions().getNamespace());
  }
}
