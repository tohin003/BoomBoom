import Foundation

/// A parsed JSON tree, read with safe navigation: a missing key or index gives `.null`, never a crash.
///
/// InnerTube answers are deep, loosely typed trees whose shape shifts between experiments, so the parsers walk them
/// with paths (`json["contents", 0, "musicShelfRenderer"]`) instead of decoding fixed structs.
enum JSON: Sendable, Hashable {
    case object([String: JSON])
    case array([JSON])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    /// One step of a path: a key into an object or an index into an array.
    enum Step: Hashable, Sendable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral {
        case key(String)
        case index(Int)

        init(stringLiteral value: String) { self = .key(value) }
        init(integerLiteral value: Int) { self = .index(value) }
    }

    subscript(_ path: Step...) -> JSON { value(at: path) }

    func value(at path: [Step]) -> JSON {
        var node = self
        for step in path {
            switch (step, node) {
            case (.key(let key), .object(let object)):
                node = object[key] ?? .null
            case (.index(let index), .array(let array)):
                let resolved = index < 0 ? array.count + index : index
                guard array.indices.contains(resolved) else { return .null }
                node = array[resolved]
            default:
                return .null
            }
        }
        return node
    }

    var exists: Bool {
        if case .null = self { return false }
        return true
    }

    var string: String? {
        switch self {
        case .string(let value): value
        case .number(let value): Int(exactly: value).map(String.init) ?? String(value)
        default: nil
        }
    }

    /// A non-empty string, or nil.
    var text: String? {
        guard let value = string, !value.isEmpty else { return nil }
        return value
    }

    var int: Int? {
        switch self {
        // `Int(exactly:)` gives nil instead of trapping on NaN, infinity or a number too big for `Int`.
        case .number(let value): Int(exactly: value.rounded(.towardZero))
        case .string(let value): Int(value)
        default: nil
        }
    }

    var double: Double? {
        switch self {
        case .number(let value): value
        case .string(let value): Double(value)
        default: nil
        }
    }

    var bool: Bool? {
        switch self {
        case .bool(let value): value
        case .string(let value): value == "true" ? true : value == "false" ? false : nil
        default: nil
        }
    }

    /// The elements, or an empty array for anything that is not an array.
    var array: [JSON] {
        if case .array(let value) = self { return value }
        return []
    }

    var object: [String: JSON]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var keys: [String] { object.map { Array($0.keys) } ?? [] }

    /// For a single-key wrapper such as `{"musicShelfRenderer": {...}}`: the key and its value.
    var soleEntry: (key: String, value: JSON)? {
        guard let object, let first = object.first(where: { $0.key != "trackingParams" }) else { return nil }
        return (first.key, first.value)
    }

    /// The text of a `{"runs": [...]}` or `{"simpleText": ...}` node, or a plain string.
    var runsText: String? {
        if case .string(let value) = self { return value.isEmpty ? nil : value }
        let runs = self["runs"].array
        if !runs.isEmpty {
            let joined = runs.compactMap { $0["text"].string }.joined()
            return joined.isEmpty ? nil : joined
        }
        return self["simpleText"].text ?? self["content"].text
    }

    var runs: [JSON] { self["runs"].array }
}

// MARK: - Reading and writing

extension JSON {
    init(data: Data) throws {
        let object = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        self = JSON(any: object)
    }

    init(any value: Any) {
        switch value {
        case let dictionary as [String: Any]:
            self = .object(dictionary.mapValues { JSON(any: $0) })
        case let array as [Any]:
            self = .array(array.map { JSON(any: $0) })
        case let string as String:
            self = .string(string)
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                self = .bool(number.boolValue)
            } else {
                self = .number(number.doubleValue)
            }
        default:
            self = .null
        }
    }

    var anyValue: Any {
        switch self {
        case .object(let object): object.mapValues(\.anyValue)
        case .array(let array): array.map(\.anyValue)
        case .string(let string): string
        case .number(let number):
            number.rounded() == number && abs(number) < 1e15 ? NSNumber(value: Int64(number)) : NSNumber(value: number)
        case .bool(let bool): NSNumber(value: bool)
        case .null: NSNull()
        }
    }

    /// Compact JSON with sorted keys, so equal trees give equal bytes.
    func data() throws -> Data {
        try JSONSerialization.data(withJSONObject: anyValue, options: [.sortedKeys, .fragmentsAllowed])
    }
}

extension JSON: ExpressibleByDictionaryLiteral, ExpressibleByArrayLiteral, ExpressibleByStringLiteral,
    ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral, ExpressibleByNilLiteral
{
    init(dictionaryLiteral elements: (String, JSON)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
    init(arrayLiteral elements: JSON...) { self = .array(elements) }
    init(stringLiteral value: String) { self = .string(value) }
    init(integerLiteral value: Int) { self = .number(Double(value)) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
    init(nilLiteral: ()) { self = .null }

    init(_ value: String?) { self = value.map(JSON.string) ?? .null }

    /// Sets `value` under `key` when this is an object (or `.null`, which becomes an object).
    mutating func set(_ key: String, _ value: JSON) {
        var object = self.object ?? [:]
        object[key] = value
        self = .object(object)
    }
}
