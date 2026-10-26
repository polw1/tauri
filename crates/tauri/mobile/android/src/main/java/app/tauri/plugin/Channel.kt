// Copyright 2019-2024 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.plugin

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper

const val CHANNEL_PREFIX = "__CHANNEL__:"
internal const val CHANNEL_LIFETIMES = "tauri.channelLifetimes"

internal class ChannelLifetime(val token: Long, retain: (Long) -> Unit, private val release: (Long) -> Unit) {
  init {
    retain(token)
  }

  @Synchronized
  protected fun finalize() {
    release(token)
  }
}

internal class ChannelDeserializer(val sendChannelData: (channelId: Long, data: String) -> Unit, private val objectMapper: ObjectMapper): JsonDeserializer<Channel>() {
  override fun deserialize(
    jsonParser: JsonParser?,
    deserializationContext: DeserializationContext
  ): Channel {
    val channelDef = deserializationContext.readValue(jsonParser, String::class.java)
    val callback = channelDef.substring(CHANNEL_PREFIX.length).toLongOrNull() ?: throw Error("unexpected channel value $channelDef")
    val lifetimes = deserializationContext.getAttribute(CHANNEL_LIFETIMES) as? Map<*, *>
    val lifetime = lifetimes?.get(callback) as? ChannelLifetime
    if (lifetimes != null && lifetime == null) {
      throw deserializationContext.weirdStringException(channelDef, Channel::class.java, "channel has no registration")
    }
    return Channel(callback, { res ->
      if (lifetime == null) {
        sendChannelData(callback, res)
      } else {
        try {
          sendChannelData(lifetime.token, res)
        } finally {
          // Matching the finalizer lock provides an API 21 reachability fence (JLS 12.6.2).
          synchronized(lifetime) {}
        }
      }
    }, objectMapper)
  }
}

class Channel(val id: Long, private val handler: (data: String) -> Unit, private val objectMapper: ObjectMapper) {
  fun send(data: JSObject) {
    handler(PluginResult(data).toString())
  }

  fun sendObject(data: Any) {
    handler(objectMapper.writeValueAsString(data))
  }
}
