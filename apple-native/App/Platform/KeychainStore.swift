import Foundation
import Security

/// Local profile storage. Callers must present errors; this API never logs stored data.
enum KeychainStore {
    static func read(account: String) throws -> Data? {
        var query = itemQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw KeychainStoreError(operation: .read, status: status)
        }
        guard let data = result as? Data else {
            throw KeychainStoreError(operation: .read, status: errSecDecode)
        }
        return data
    }

    static func write(_ data: Data, account: String) throws {
        let query = itemQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let newItem = query.merging(attributes) { _, new in new }
            status = SecItemAdd(newItem as CFDictionary, nil)
            // Another writer may have inserted the same account in the meantime.
            if status == errSecDuplicateItem {
                status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            }
        }
        guard status == errSecSuccess else {
            throw KeychainStoreError(operation: .write, status: status)
        }
    }

    static func delete(account: String) throws {
        let status = SecItemDelete(itemQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainStoreError(operation: .delete, status: status)
        }
    }

    /// Read only the app's accessible Flutter keychain group. No fallback to
    /// plaintext preferences, filesystem secrets, or another app's access group.
    static func readFlutter(account: String) throws -> Data? {
        guard !UITestFixtureMode.enabled else { return nil }
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "flutter_secure_storage_service",
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainStoreError(operation: .read, status: status == errSecSuccess ? errSecDecode : status)
        }
        return data
    }

    static func deleteFlutter(account: String) throws {
        guard !UITestFixtureMode.enabled else {
            throw KeychainStoreError(operation: .delete, status: errSecInteractionNotAllowed)
        }
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "flutter_secure_storage_service",
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false
        ]
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainStoreError(operation: .delete, status: status)
        }
    }

    /// Exact bytes preserve Flutter companion wrapped-key and backup entries.
    /// Migration never unwraps or rewrites an unknown platform key format.
    static func readAllFlutter() throws -> [String: Data] {
        guard !UITestFixtureMode.enabled else { return [:] }
        var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "flutter_secure_storage_service", kSecAttrSynchronizable as String: false,
            kSecReturnData as String: true, kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll]
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [:] }
        guard status == errSecSuccess, let items = result as? [[String: Any]] else {
            throw KeychainStoreError(operation: .read, status: status == errSecSuccess ? errSecDecode : status)
        }
        var values: [String: Data] = [:]
        for item in items {
            guard let name = item[kSecAttrAccount as String] as? String, let data = item[kSecValueData as String] as? Data,
                  values[name] == nil else { throw KeychainStoreError(operation: .read, status: errSecDecode) }
            values[name] = data
        }
        return values
    }

    private static func itemQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String:
                (Bundle.main.bundleIdentifier ?? "org.einundzwanzig.meetup.native") + ".local-profile" +
                (UITestFixtureMode.enabled ? ".ui-test" : ""),
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            // Use the data protection keychain on macOS too; do not enable iCloud sync.
            kSecUseDataProtectionKeychain as String: true
        ]
    }

    #if DEBUG
    /// Reset only the dedicated fixture service, never ordinary app/Flutter data.
    static func resetUITestScope() throws {
        guard UITestFixtureMode.enabled else {
            throw KeychainStoreError(operation: .delete, status: errSecInteractionNotAllowed)
        }
        var query = itemQuery(account: "")
        guard let service = query[kSecAttrService as String] as? String, service.hasSuffix(".local-profile.ui-test") else {
            throw KeychainStoreError(operation: .delete, status: errSecParam)
        }
        query.removeValue(forKey: kSecAttrAccount as String)
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainStoreError(operation: .delete, status: status)
        }
    }
    #endif
}

struct KeychainStoreError: LocalizedError {
    enum Operation: Sendable { case read, write, delete }

    let operation: Operation
    let status: OSStatus

    var errorDescription: String? {
        let message: String
        switch operation {
        case .read: message = String(localized: "keychain.error.read")
        case .write: message = String(localized: "keychain.error.write")
        case .delete: message = String(localized: "keychain.error.delete")
        }
        let systemMessage = SecCopyErrorMessageString(status, nil) as String?
        return "\(message) (\(status))" + (systemMessage.map { ": \($0)" } ?? "")
    }
}
