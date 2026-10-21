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

    private static func itemQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String:
                (Bundle.main.bundleIdentifier ?? "org.einundzwanzig.meetup.native") + ".local-profile",
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            // Use the data protection keychain on macOS too; do not enable iCloud sync.
            kSecUseDataProtectionKeychain as String: true
        ]
    }
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
