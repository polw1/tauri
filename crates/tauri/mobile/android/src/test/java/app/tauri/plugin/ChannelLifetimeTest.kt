// Copyright 2019-2024 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.core.type.TypeReference
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class ChannelLifetimeTest {
  private class Fixture {
    val retained = CopyOnWriteArrayList<Long>()
    val released = CopyOnWriteArrayList<Long>()
    val sent = CopyOnWriteArrayList<Pair<Long, String>>()
    val mapper = ObjectMapper().also { mapper ->
      mapper.registerModule(SimpleModule().addDeserializer(Channel::class.java,
        ChannelDeserializer({ id, data -> sent.add(id to data) }, mapper)))
    }

    fun invoke(token: Long = 101): Invoke = Invoke(
      1, "test", 0, 1, { _, _ -> }, "\"__CHANNEL__:42\"", mapper
    ).also { it.attachChannels("{\"42\":$token}", { retained.add(it) }, { released.add(it) }) }
  }

  @Test
  fun repeatedDecodeKeepsTheChannelUntilInvokeIsCollected() {
    val fixture = Fixture()
    val invoke = fixture.invoke()
    val queue = ReferenceQueue<Channel>()
    val channel = PhantomReference(invoke.parseArgs(Channel::class.java), queue)
    awaitCollection(queue)
    invoke.resolve()
    assertEquals(emptyList<Long>(), fixture.released)
    val second = invoke.parseArgs(object : TypeReference<Channel>() {})
    assertEquals(42L, second.id)
    second.sendObject("second")
    assertEquals(listOf(101L to "\"second\""), fixture.sent)
    assertEquals(listOf(101L), fixture.retained)
    channel.clear()
  }

  @Test
  fun channelOutlivesInvoke() {
    val fixture = Fixture()
    val invokeQueue = ReferenceQueue<Invoke>()
    val (invokeReference, channel) = fixture.invoke().let {
      PhantomReference(it, invokeQueue) to it.parseArgs(Channel::class.java)
    }
    awaitCollection(invokeQueue)
    assertEquals(emptyList<Long>(), fixture.released)
    channel.sendObject("alive")
    assertEquals(listOf(101L to "\"alive\""), fixture.sent)
    invokeReference.clear()
  }

  @Test
  fun droppingInvokeAndChannelReleasesOnce() {
    val fixture = Fixture()
    val queue = ReferenceQueue<Channel>()
    val channel = PhantomReference(fixture.invoke().parseArgs(Channel::class.java), queue)
    awaitCollection(queue)
    awaitRelease(fixture, 101)
    assertEquals(listOf(101L), fixture.retained)
    assertEquals(listOf(101L), fixture.released)
    channel.clear()
  }

  @Test
  fun ignoredArgumentsStillReleaseTheirRegistration() {
    val fixture = Fixture()
    val queue = ReferenceQueue<Invoke>()
    val invoke = ignoredInvoke(fixture, queue)
    awaitCollection(queue)
    awaitRelease(fixture, 101)
    assertEquals(listOf(101L), fixture.retained)
    assertEquals(listOf(101L), fixture.released)
    invoke.clear()
  }

  @Test
  fun oldFinalizerCannotReleaseANewInvokeWithTheSamePublicId() {
    val fixture = Fixture()
    val queue = ReferenceQueue<Channel>()
    val old = PhantomReference(fixture.invoke(101).parseArgs(Channel::class.java), queue)
    val freshInvoke = fixture.invoke(102)
    val fresh = freshInvoke.parseArgs(Channel::class.java)
    awaitCollection(queue)
    awaitRelease(fixture, 101)
    assertEquals(listOf(101L), fixture.released)
    assertEquals(42L, fresh.id)
    fresh.sendObject("fresh")
    assertEquals(listOf(102L to "\"fresh\""), fixture.sent)
    assertEquals(42L, freshInvoke.parseArgs(object : TypeReference<Channel>() {}).id)
    old.clear()
  }

  @Test
  fun legacyDecodingStillSendsThePublicId() {
    val fixture = Fixture()
    fixture.mapper.readValue("\"__CHANNEL__:42\"", Channel::class.java).sendObject("legacy")
    assertEquals(listOf(42L to "\"legacy\""), fixture.sent)
    assertEquals(emptyList<Long>(), fixture.retained)
    assertEquals(emptyList<Long>(), fixture.released)
  }

  @Test
  fun unattachedInvokeStillSendsThePublicId() {
    val fixture = Fixture()
    val invoke = Invoke(1, "test", 0, 1, { _, _ -> }, "\"__CHANNEL__:42\"", fixture.mapper)
    invoke.parseArgs(Channel::class.java).sendObject("legacy")
    assertEquals(listOf(42L to "\"legacy\""), fixture.sent)
  }

  @Test
  fun attachedInvokeRejectsChannelsMissingFromMetadata() {
    for (metadata in listOf("{}", "{\"43\":101}")) {
      val fixture = Fixture()
      val invoke = Invoke(1, "test", 0, 1, { _, _ -> }, "\"__CHANNEL__:42\"", fixture.mapper)
      invoke.attachChannels(metadata, { fixture.retained.add(it) }, { fixture.released.add(it) })
      assertThrows(JsonMappingException::class.java) { invoke.parseArgs(Channel::class.java) }
      assertThrows(JsonMappingException::class.java) { invoke.parseArgs(object : TypeReference<Channel>() {}) }
      assertEquals(emptyList<Pair<Long, String>>(), fixture.sent)
    }
  }

  @Test
  fun readerContextDoesNotLeakBetweenInvokesSharingAMapper() {
    val fixture = Fixture()
    val first = fixture.invoke(101)
    val second = fixture.invoke(102)
    first.parseArgs(Channel::class.java).sendObject("first")
    second.parseArgs(object : TypeReference<Channel>() {}).sendObject("second")
    first.parseArgs(object : TypeReference<Channel>() {}).sendObject("first again")
    assertEquals(listOf(101L, 102L, 101L), fixture.sent.map { it.first })
  }

  @Test
  fun invalidMetadataIsRejectedBeforeAnyTokenIsRetained() {
    for (metadata in listOf("[]", "{\"42\":101,\"bad\":102}", "{\"4294967296\":101}",
      "{\"42\":0}", "{\"42\":-1}", "{\"42\":1.5}", "{\"42\":\"101\"}",
      "{\"042\":101}", "{\"42\":9223372036854775808}")) {
      val fixture = Fixture()
      val invoke = Invoke(1, "test", 0, 1, { _, _ -> }, "{}", fixture.mapper)
      assertThrows(IllegalArgumentException::class.java) {
        invoke.attachChannels(metadata, { fixture.retained.add(it) }, { fixture.released.add(it) })
      }
      assertEquals(metadata, emptyList<Long>(), fixture.retained)
    }
  }

  private fun awaitRelease(fixture: Fixture, token: Long) {
    repeat(200) {
      System.gc()
      System.runFinalization()
      if (fixture.released.contains(token)) return
      Thread.sleep(25)
    }
    fail("channel token $token was not released")
  }

  private fun ignoredInvoke(fixture: Fixture, queue: ReferenceQueue<Invoke>): PhantomReference<Invoke> {
    val invoke = fixture.invoke()
    invoke.resolve()
    return PhantomReference(invoke, queue)
  }

  private fun awaitCollection(queue: ReferenceQueue<*>) {
    repeat(200) {
      System.gc()
      System.runFinalization()
      if (queue.remove(25) != null) return
    }
    fail("object was not collected")
  }
}
