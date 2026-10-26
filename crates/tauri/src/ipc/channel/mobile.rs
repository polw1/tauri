// Copyright 2019-2024 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

use std::{
  cell::RefCell,
  collections::HashMap,
  str::FromStr,
  sync::{
    Mutex, OnceLock,
    atomic::{AtomicU64, Ordering},
  },
};

use serde::Serialize;

use super::{Channel, ChannelInner, JavaScriptChannelId};
use crate::{Runtime, Webview};

type Channels = HashMap<u32, Channel<serde_json::Value>>;

// A pre-serialized JSON value can locate a channel only while its Rust owner is alive.
static SERIALIZED_CHANNELS: OnceLock<Mutex<HashMap<u32, std::sync::Weak<ChannelInner>>>> =
  OnceLock::new();

thread_local! {
  static SERIALIZING_CHANNELS: RefCell<Option<Channels>> = const { RefCell::new(None) };
}

struct SerializationScope(Option<Channels>);

impl Drop for SerializationScope {
  fn drop(&mut self) {
    // Drop captured channels after releasing the RefCell borrow, including on unwind.
    let channels = SERIALIZING_CHANNELS.replace(self.0.take());
    drop(channels);
  }
}

pub(super) fn collect<T>(channel: &Channel<T>) {
  SERIALIZING_CHANNELS.with_borrow_mut(|channels| {
    if let Some(channels) = channels {
      channels.entry(channel.id()).or_insert_with(|| Channel {
        inner: channel.inner.clone(),
        phantom: Default::default(),
      });
    } else {
      SERIALIZED_CHANNELS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .insert(channel.id(), std::sync::Arc::downgrade(&channel.inner));
    }
  });
}

pub(super) fn forget_serialized(inner: &ChannelInner) {
  if let Some(channels) = SERIALIZED_CHANNELS.get() {
    let mut channels = channels.lock().unwrap();
    if channels
      .get(&inner.id)
      .is_some_and(|channel| std::ptr::eq(channel.as_ptr(), inner))
    {
      channels.remove(&inner.id);
    }
  }
}

fn visit_channels(value: &serde_json::Value, visit: &mut impl FnMut(JavaScriptChannelId)) {
  match value {
    serde_json::Value::String(value) => {
      if let Ok(id) = JavaScriptChannelId::from_str(value) {
        visit(id);
      }
    }
    serde_json::Value::Array(values) => {
      for value in values {
        visit_channels(value, visit);
      }
    }
    serde_json::Value::Object(values) => {
      for value in values.values() {
        visit_channels(value, visit);
      }
    }
    _ => {}
  }
}

pub(crate) struct MobilePayload {
  value: serde_json::Value,
  channels: Channels,
}

impl MobilePayload {
  pub(crate) fn serialize(payload: impl Serialize) -> serde_json::Result<Self> {
    let scope = SerializationScope(SERIALIZING_CHANNELS.replace(Some(HashMap::new())));
    let value = serde_json::to_value(&payload);
    let mut channels = SERIALIZING_CHANNELS.take().unwrap();
    drop(scope);
    value.map(|value| {
      if let Some(serialized) = SERIALIZED_CHANNELS.get() {
        let serialized = serialized.lock().unwrap();
        visit_channels(&value, &mut |id| {
          if let std::collections::hash_map::Entry::Vacant(entry) = channels.entry(id.0.0) {
            if let Some(inner) = serialized.get(&id.0.0).and_then(std::sync::Weak::upgrade) {
              entry.insert(Channel {
                inner,
                phantom: Default::default(),
              });
            }
          }
        });
      }
      Self { value, channels }
    })
  }

  pub(crate) fn from_js<R: Runtime>(value: serde_json::Value, webview: &Webview<R>) -> Self {
    let mut channels = HashMap::new();
    visit_channels(&value, &mut |id| {
      channels
        .entry(id.0.0)
        .or_insert_with(|| id.channel_on(webview.clone()));
    });
    Self { value, channels }
  }

  pub(crate) fn register(self) -> (serde_json::Value, RegisteredChannels) {
    let mut tokens = HashMap::new();
    let mut entries = CHANNELS.get_or_init(Default::default).lock().unwrap();
    for (id, channel) in self.channels {
      let token = NEXT_TOKEN.fetch_add(1, Ordering::Relaxed);
      entries.insert(
        token,
        NativeChannel {
          channel,
          references: 1,
        },
      );
      tokens.insert(id, token);
    }
    (self.value, RegisteredChannels { tokens })
  }
}

struct NativeChannel {
  channel: Channel<serde_json::Value>,
  references: usize,
}

static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);
static CHANNELS: OnceLock<Mutex<HashMap<u64, NativeChannel>>> = OnceLock::new();

/// Holds the Rust handoff reference until native code has adopted the channels.
pub(crate) struct RegisteredChannels {
  pub(crate) tokens: HashMap<u32, u64>,
}

impl Drop for RegisteredChannels {
  fn drop(&mut self) {
    for token in self.tokens.values() {
      release_channel(*token);
    }
  }
}

pub(crate) fn get_channel(token: u64) -> Option<Channel<serde_json::Value>> {
  CHANNELS
    .get_or_init(Default::default)
    .lock()
    .unwrap()
    .get(&token)
    .map(|entry| entry.channel.clone())
}

/// Retains the channel for a native Invoke and its decoded Channel instances.
#[doc(hidden)]
pub fn retain_channel(token: u64) {
  if let Some(entry) = CHANNELS
    .get_or_init(Default::default)
    .lock()
    .unwrap()
    .get_mut(&token)
  {
    entry.references += 1;
  }
}

/// Releases one handoff or native lifetime reference.
#[doc(hidden)]
pub fn release_channel(token: u64) {
  let channel = {
    let mut entries = CHANNELS.get_or_init(Default::default).lock().unwrap();
    if let Some(entry) = entries.get_mut(&token) {
      entry.references -= 1;
      if entry.references == 0 {
        entries.remove(&token)
      } else {
        None
      }
    } else {
      None
    }
  };
  // The last drop can send `end` to the webview; never hold the registry lock here.
  drop(channel);
}

#[cfg(test)]
mod tests {
  use super::*;
  use std::sync::{Arc, atomic::AtomicUsize};

  struct Guard(Arc<AtomicUsize>);
  impl Drop for Guard {
    fn drop(&mut self) {
      self.0.fetch_add(1, Ordering::SeqCst);
    }
  }

  fn channel(dropped: &Arc<AtomicUsize>) -> Channel<serde_json::Value> {
    let guard = Guard(dropped.clone());
    Channel::new(move |_| {
      let _ = &guard;
      Ok(())
    })
  }

  #[test]
  fn plain_serialization_does_not_retain_channels() {
    let dropped = Arc::new(AtomicUsize::new(0));
    let channel = channel(&dropped);
    let json = serde_json::to_string(&channel).unwrap();
    assert_eq!(json, format!("\"__CHANNEL__:{}\"", channel.id()));
    drop(channel);
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  #[test]
  fn preserialized_payload_retains_its_still_live_rust_channel() {
    let dropped = Arc::new(AtomicUsize::new(0));
    let channel = channel(&dropped);
    let value = serde_json::json!({"nested": [channel]});
    let payload = MobilePayload::serialize(value).unwrap();
    drop(channel);
    assert_eq!(payload.channels.len(), 1);
    assert_eq!(dropped.load(Ordering::SeqCst), 0);
    drop(payload);
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  #[test]
  fn native_and_handoff_references_are_independent() {
    let dropped = Arc::new(AtomicUsize::new(0));
    let payload = MobilePayload::serialize(channel(&dropped)).unwrap();
    let (_, registration) = payload.register();
    let token = *registration.tokens.values().next().unwrap();
    retain_channel(token);
    drop(registration);
    assert!(get_channel(token).is_some());
    assert_eq!(dropped.load(Ordering::SeqCst), 0);
    release_channel(token);
    assert!(get_channel(token).is_none());
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  #[test]
  fn failed_handoff_releases_unclaimed_channels() {
    let dropped = Arc::new(AtomicUsize::new(0));
    let (_, registration) = MobilePayload::serialize(channel(&dropped))
      .unwrap()
      .register();
    let token = *registration.tokens.values().next().unwrap();
    drop(registration);
    assert!(get_channel(token).is_none());
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  #[test]
  fn stale_release_does_not_remove_a_new_handoff_of_the_same_channel() {
    let dropped = Arc::new(AtomicUsize::new(0));
    let channel = channel(&dropped);
    let (_, old) = MobilePayload::serialize(&channel).unwrap().register();
    let old_token = old.tokens[&channel.id()];
    drop(old);
    let (_, new) = MobilePayload::serialize(&channel).unwrap().register();
    let token = new.tokens[&channel.id()];
    assert_ne!(old_token, token);
    release_channel(old_token);
    assert!(get_channel(token).is_some());
    drop(new);
    drop(channel);
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  struct FailingPayload(Channel<serde_json::Value>);
  impl Serialize for FailingPayload {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
      use serde::ser::{Error, SerializeSeq};
      let mut sequence = serializer.serialize_seq(None)?;
      sequence.serialize_element(&self.0)?;
      Err(S::Error::custom("serialization failed after a channel"))
    }
  }

  #[test]
  fn failed_serialization_releases_channels_and_restores_the_scope() {
    let dropped = Arc::new(AtomicUsize::new(0));
    assert!(MobilePayload::serialize(FailingPayload(channel(&dropped))).is_err());
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
    assert!(SERIALIZING_CHANNELS.with_borrow(|channels| channels.is_none()));
  }

  #[test]
  fn nested_serialization_restores_the_outer_scope() {
    #[derive(Serialize)]
    struct Outer<'a> {
      nested: Nested,
      channel: &'a Channel<serde_json::Value>,
    }
    struct Nested;
    impl Serialize for Nested {
      fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let dropped = Arc::new(AtomicUsize::new(0));
        drop(MobilePayload::serialize(channel(&dropped)).unwrap());
        assert_eq!(dropped.load(Ordering::SeqCst), 1);
        serializer.serialize_unit()
      }
    }
    let dropped = Arc::new(AtomicUsize::new(0));
    let channel = channel(&dropped);
    let payload = MobilePayload::serialize(Outer {
      nested: Nested,
      channel: &channel,
    })
    .unwrap();
    drop(channel);
    assert_eq!(payload.channels.len(), 1);
    assert_eq!(dropped.load(Ordering::SeqCst), 0);
    drop(payload);
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  #[test]
  fn panicking_serialization_restores_the_scope() {
    struct Panicking;
    impl Serialize for Panicking {
      fn serialize<S: serde::Serializer>(&self, _: S) -> Result<S::Ok, S::Error> {
        panic!("serializer panic");
      }
    }
    assert!(std::panic::catch_unwind(|| MobilePayload::serialize(Panicking)).is_err());
    assert!(SERIALIZING_CHANNELS.with_borrow(|channels| channels.is_none()));
    let dropped = Arc::new(AtomicUsize::new(0));
    drop(MobilePayload::serialize(channel(&dropped)).unwrap());
    assert_eq!(dropped.load(Ordering::SeqCst), 1);
  }

  #[test]
  fn javascript_payload_finds_nested_channels_without_retaining_a_weak_cache() {
    use crate::test::{mock_builder, mock_context, noop_assets};
    let app = mock_builder().build(mock_context(noop_assets())).unwrap();
    let window = crate::WebviewWindowBuilder::new(&app, "nested", crate::WebviewUrl::default())
      .build()
      .unwrap();
    let value = serde_json::json!({"nested": ["__CHANNEL__:42", {"again": "__CHANNEL__:42"}]});
    let payload = MobilePayload::from_js(value.clone(), &window.webview);
    assert_eq!(payload.value, value);
    assert_eq!(payload.channels.len(), 1);
    let inner = Arc::downgrade(&payload.channels[&42].inner);
    drop(payload);
    assert!(inner.upgrade().is_none());
    assert!(window.webview.channels.0.lock().unwrap().is_empty());
  }
}
