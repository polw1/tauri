// Copyright 2019-2024 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

import XCTest
@testable import Tauri

final class TauriTests: XCTestCase {
  private final class Fixture {
    var retained: [UInt64] = []
    var released: [UInt64] = []
    var sent: [(UInt64, String)] = []

    func invoke(token: UInt64 = 101) throws -> Invoke {
      let invoke = Invoke(
        command: "test", callback: 0, error: 1, sendResponse: { _, _ in },
        sendChannelData: { self.sent.append(($0, $1)) }, data: "\"__CHANNEL__:42\"")
      try invoke.attachChannels(
        "{\"42\":\(token)}", retain: { self.retained.append($0) },
        release: { self.released.append($0) })
      return invoke
    }
  }

  func testRepeatedDecodeKeepsChannelUntilInvokeIsReleased() throws {
    let fixture = Fixture()
    let invoke = try fixture.invoke()
    try autoreleasepool {
      let first = try invoke.parseArgs(Channel.self)
      XCTAssertEqual(first.id, 42)
    }
    invoke.resolve()
    XCTAssertEqual(fixture.released, [])
    let second = try invoke.parseArgs(Channel.self)
    try second.send("second")
    XCTAssertEqual(second.id, 42)
    XCTAssertEqual(fixture.retained, [101])
    XCTAssertEqual(fixture.sent.map { $0.0 }, [101])
    XCTAssertEqual(fixture.sent.map { $0.1 }, ["\"second\""])
    withExtendedLifetime(invoke) {}
  }

  func testChannelOutlivesInvokeAndReleasesExactlyOnce() throws {
    let fixture = Fixture()
    var channel: Channel?
    weak var weakInvoke: Invoke?
    try autoreleasepool {
      let invoke = try fixture.invoke()
      weakInvoke = invoke
      channel = try invoke.parseArgs(Channel.self)
    }
    XCTAssertNil(weakInvoke)
    XCTAssertEqual(fixture.released, [])
    try channel!.send("alive")
    XCTAssertEqual(fixture.sent.map { $0.0 }, [101])
    channel = nil
    XCTAssertEqual(fixture.retained, [101])
    XCTAssertEqual(fixture.released, [101])
  }

  func testLifetimeRemainsAliveUntilSendReturns() throws {
    var released = false
    var delivered = false
    func makeChannel() throws -> Channel {
      let invoke = Invoke(command: "test", callback: 0, error: 1,
        sendResponse: { _, _ in },
        sendChannelData: { token, _ in
          XCTAssertEqual(token, 101)
          XCTAssertFalse(released, "lifetime released before delivery")
          delivered = true
        }, data: "\"__CHANNEL__:42\"")
      try invoke.attachChannels("{\"42\":101}", retain: { _ in }, release: { _ in released = true })
      return try invoke.parseArgs(Channel.self)
    }
    try makeChannel().send("message")
    XCTAssertTrue(delivered)
    XCTAssertTrue(released)
  }

  func testIgnoredArgumentsReleaseTheirRegistration() throws {
    let fixture = Fixture()
    try autoreleasepool {
      let invoke = try fixture.invoke()
      invoke.resolve()
      XCTAssertEqual(fixture.released, [])
      withExtendedLifetime(invoke) {}
    }
    XCTAssertEqual(fixture.retained, [101])
    XCTAssertEqual(fixture.released, [101])
  }

  func testRejectedInvokeReleasesUndecodedArguments() throws {
    let fixture = Fixture()
    try autoreleasepool {
      let invoke = try fixture.invoke()
      invoke.reject("No command test found")
      XCTAssertEqual(fixture.released, [])
      withExtendedLifetime(invoke) {}
    }
    XCTAssertEqual(fixture.released, [101])
  }

  func testSamePublicIdInDifferentInvokesUsesIndependentTokens() throws {
    let fixture = Fixture()
    var firstInvoke: Invoke? = try fixture.invoke(token: 101)
    var first: Channel? = try firstInvoke!.parseArgs(Channel.self)
    let secondInvoke = try fixture.invoke(token: 102)
    let second = try secondInvoke.parseArgs(Channel.self)
    XCTAssertEqual(first!.id, second.id)
    firstInvoke = nil
    XCTAssertEqual(fixture.released, [])
    first = nil
    XCTAssertEqual(fixture.released, [101])
    try second.send("fresh")
    XCTAssertEqual(fixture.sent.map { $0.0 }, [102])
    XCTAssertEqual(try secondInvoke.parseArgs(Channel.self).id, 42)
    XCTAssertEqual(fixture.retained, [101, 102])
  }

  func testLegacyDecodingStillSendsPublicId() throws {
    var sent: [UInt64] = []
    let decoder = JSONDecoder()
    let handler: (UInt64, String) -> Void = { token, _ in sent.append(token) }
    decoder.userInfo[channelDataKey] = handler
    let channel = try decoder.decode(Channel.self, from: Data("\"__CHANNEL__:42\"".utf8))
    try channel.send("legacy")
    XCTAssertEqual(channel.id, 42)
    XCTAssertEqual(sent, [42])
  }

  func testUnattachedInvokeStillSendsPublicId() throws {
    var sent: [UInt64] = []
    let invoke = Invoke(command: "test", callback: 0, error: 1,
      sendResponse: { _, _ in }, sendChannelData: { token, _ in sent.append(token) },
      data: "\"__CHANNEL__:42\"")
    try invoke.parseArgs(Channel.self).send("legacy")
    XCTAssertEqual(sent, [42])
  }

  func testAttachedInvokeRejectsChannelsMissingFromMetadata() throws {
    for metadata in ["{}", "{\"43\":101}"] {
      var sent: [UInt64] = []
      let invoke = Invoke(command: "test", callback: 0, error: 1,
        sendResponse: { _, _ in }, sendChannelData: { token, _ in sent.append(token) },
        data: "\"__CHANNEL__:42\"")
      try invoke.attachChannels(metadata, retain: { _ in }, release: { _ in })
      XCTAssertThrowsError(try invoke.parseArgs(Channel.self)) { error in
        guard case DecodingError.dataCorrupted = error else {
          return XCTFail("expected decoding failure, got \(error)")
        }
      }
      XCTAssertEqual(sent, [])
    }
  }

  func testInvalidMetadataDoesNotRetainAnyToken() {
    for metadata in ["[]", "{\"42\":101,\"bad\":102}", "{\"4294967296\":101}",
      "{\"42\":0}", "{\"42\":-1}", "{\"42\":1.5}", "{\"42\":\"101\"}",
      "{\"042\":101}", "{\"42\":18446744073709551616}"]
    {
      var retained: [UInt64] = []
      let invoke = Invoke(command: "test", callback: 0, error: 1,
        sendResponse: { _, _ in }, sendChannelData: { _, _ in }, data: "{}")
      XCTAssertThrowsError(try invoke.attachChannels(
        metadata, retain: { retained.append($0) }, release: { _ in }))
      XCTAssertEqual(retained, [], metadata)
    }
  }
}
