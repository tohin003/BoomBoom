import Foundation
import Testing
@testable import InnerTube

/// Loads a recorded InnerTube answer from `Fixtures/<name>.json`.
func fixture(_ name: String) throws -> JSON {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
    return try JSON(data: Data(contentsOf: url))
}
