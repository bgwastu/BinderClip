import XCTest
@testable import BinderClip

final class MediaBundleTests: XCTestCase {
    func testRoundTripPreservesTypesAndBytes() {
        let items = [
            MediaBundle.Item(mimeType: "image/heic", data: Data([1, 2, 3])),
            MediaBundle.Item(mimeType: "video/quicktime", data: Data([4, 5, 6, 7]))
        ]
        let decoded = try! XCTUnwrap(MediaBundle.decode(MediaBundle.encode(items)))
        XCTAssertEqual(decoded.map(\.mimeType), items.map(\.mimeType))
        XCTAssertEqual(decoded.map(\.data), items.map(\.data))
    }
}
