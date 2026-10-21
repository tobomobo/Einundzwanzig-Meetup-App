import Foundation

/// String lookups outside SwiftUI Text must honor the same user-selected language.
enum AppLocalization {
    static func text(_ key: String, language: String, bundle: Bundle = .main) -> String {
        guard language != "system", ["de", "en", "es"].contains(language),
              let path = bundle.path(forResource: language, ofType: "lproj"),
              let localizedBundle = Bundle(path: path) else {
            return bundle.localizedString(forKey: key, value: nil, table: nil)
        }
        return localizedBundle.localizedString(forKey: key, value: nil, table: nil)
    }
}
