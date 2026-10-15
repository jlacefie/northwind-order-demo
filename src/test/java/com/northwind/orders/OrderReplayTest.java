package com.northwind.orders;

import io.temporal.testing.WorkflowReplayer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Replays real histories recorded on Temporal Cloud against the current workflow code. If a code
 * change would make in-flight orders take a different path, this fails with a nondeterminism error
 * before the deploy does. Refresh a history with:
 * {@code temporal workflow show -w order-<id> -o json > src/test/resources/histories/order-<id>.json}
 */
class OrderReplayTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "order-cloud-h1.json", // happy path
      "order-cloud-s1.json", // shipping timed out after creating the label; retry found it
      "order-cloud-c1.json", // worker stopped mid-order, restarted, finished
  })
  void recordedHistoryReplaysOnCurrentCode(String file) throws Exception {
    String json = Files.readString(
        Path.of("src/test/resources/histories", file), StandardCharsets.UTF_8);
    WorkflowReplayer.replayWorkflowExecution(json, OrderWorkflowImpl.class);
  }
}
