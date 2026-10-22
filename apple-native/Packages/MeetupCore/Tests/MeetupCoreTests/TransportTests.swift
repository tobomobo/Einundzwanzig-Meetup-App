import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import MeetupCore

private final class StubURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        guard request.httpMethod == "GET", request.timeoutInterval == 15,
              request.value(forHTTPHeaderField: "Accept") == "test/fixture" else {
            client?.urlProtocol(self, didFailWithError: MeetupCoreError.invalidData("Unexpected request policy."))
            return
        }
        if url.path == "/failure" {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        if url.path == "/slow" { return }
        let headers = url.path == "/advertised-size" ? ["Content-Length": "100"] : [:]
        let response = HTTPURLResponse(url: url, statusCode: url.path == "/status" ? 429 : 200,
                                       httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("1234".utf8))
        if url.path == "/streamed-size" { client?.urlProtocol(self, didLoad: Data("56789".utf8)) }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class TransportTests: XCTestCase, @unchecked Sendable {
    private func transport() -> URLSessionTransport {
        URLSessionTransport(maximumBytes: 8) {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StubURLProtocol.self]
            return config
        }
    }

    func testGETPolicyAndSuccessfulBoundedResponse() async throws {
        let data = try await transport().get(try XCTUnwrap(URL(string: "https://unit.test/ok")), accept: "test/fixture")
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "1234")
    }

    func testRejectsAdvertisedAndStreamedOversizeBodies() async throws {
        for path in ["advertised-size", "streamed-size"] {
            do {
                _ = try await transport().get(try XCTUnwrap(URL(string: "https://unit.test/" + path)), accept: "test/fixture")
                XCTFail("Expected size error")
            } catch { XCTAssertEqual(error as? MeetupCoreError, .responseTooLarge(limit: 8)) }
        }
    }

    func testHTTPAndConnectionErrorsRemainErrors() async throws {
        do {
            _ = try await transport().get(try XCTUnwrap(URL(string: "https://unit.test/status")), accept: "test/fixture")
            XCTFail("Expected HTTP error")
        } catch { XCTAssertEqual(error as? MeetupCoreError, .httpStatus(429)) }
        do {
            _ = try await transport().get(try XCTUnwrap(URL(string: "https://unit.test/failure")), accept: "test/fixture")
            XCTFail("Expected connection error")
        } catch { XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet) }
    }

    func testCancellationBeforeOrDuringStartDoesNotHang() async throws {
        let url = try XCTUnwrap(URL(string: "https://unit.test/slow"))
        let transport = transport()
        let task = Task { try await transport.get(url, accept: "test/fixture") }
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch { XCTAssertTrue(error is CancellationError || (error as? URLError)?.code == .cancelled) }
    }
}
