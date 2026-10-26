// Copyright 2019-2024 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelSendLifetimeTest {
  private static final long PROBE = 424242;
  private static volatile boolean released;
  private static volatile boolean checking;
  private static final ObjectMapper mapper = new ObjectMapper();

  static {
    mapper.registerModule(new SimpleModule().addDeserializer(Channel.class,
      new ChannelDeserializer((token, data) -> { delivery(token); return Unit.INSTANCE; }, mapper)));
  }

  private static Channel create(long token) throws Exception {
    ChannelLifetime lifetime = new ChannelLifetime(token, id -> Unit.INSTANCE,
      id -> { if (id == PROBE) released = true; return Unit.INSTANCE; });
    return mapper.readerFor(Channel.class)
      .withAttribute("tauri.channelLifetimes", Collections.singletonMap(42L, lifetime))
      .readValue("\"__CHANNEL__:42\"");
  }

  private static void send(long token) throws Exception {
    create(token).sendObject("message");
  }

  private static void delivery(long token) {
    if (!checking) return;
    for (int i = 0; i < 20; i++) {
      System.gc();
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      assertFalse("lifetime finalized before delivery of token " + token, released);
    }
  }

  @Test
  public void lifetimeRemainsAliveUntilSendReturns() throws Exception {
    released = false;
    checking = false;
    // Warm the temporary-channel send path so the JIT can discard dead references.
    for (int i = 0; i < 30000; i++) send(101);
    checking = true;
    send(PROBE);
  }

  @Test
  public void sameChannelCanSendWhileAnotherDeliveryWaits() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch secondDelivered = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ObjectMapper concurrentMapper = new ObjectMapper();
    concurrentMapper.registerModule(new SimpleModule().addDeserializer(Channel.class,
      new ChannelDeserializer((token, data) -> {
        if (data.equals("\"first\"")) {
          firstEntered.countDown();
          try {
            assertTrue("second delivery blocked behind the first", secondDelivered.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        } else {
          secondDelivered.countDown();
        }
        return Unit.INSTANCE;
      }, concurrentMapper)));
    ChannelLifetime lifetime = new ChannelLifetime(101, id -> Unit.INSTANCE, id -> Unit.INSTANCE);
    Channel channel = concurrentMapper.readerFor(Channel.class)
      .withAttribute("tauri.channelLifetimes", Collections.singletonMap(42L, lifetime))
      .readValue("\"__CHANNEL__:42\"");
    Thread first = new Thread(() -> {
      try {
        channel.sendObject("first");
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    first.start();
    assertTrue("first delivery did not start", firstEntered.await(2, TimeUnit.SECONDS));
    channel.sendObject("second");
    first.join(3000);
    assertFalse("first delivery did not finish", first.isAlive());
    assertNull(failure.get());
  }
}
