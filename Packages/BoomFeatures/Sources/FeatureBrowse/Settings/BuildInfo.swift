import Foundation

/// Facts about this installed copy of the app.
enum BuildInfo {
    /// "1.0".
    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–"
    }

    /// "12".
    static var build: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "–"
    }

    /// When the signing of this build runs out, read from the provisioning profile inside the app. Nil when the app
    /// has no profile (simulator builds).
    static func expirationDate(in bundle: Bundle = .main) -> Date? {
        guard let url = bundle.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else { return nil }
        return expirationDate(inProfile: data)
    }

    /// The profile is a signed blob with a property list in plain text inside; find the list and read its
    /// `ExpirationDate`.
    static func expirationDate(inProfile data: Data) -> Date? {
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex)
        else { return nil }
        let plist = data[start.lowerBound..<end.upperBound]
        guard let object = try? PropertyListSerialization.propertyList(from: plist, options: [], format: nil),
              let dictionary = object as? [String: Any] else { return nil }
        return dictionary["ExpirationDate"] as? Date
    }
}
