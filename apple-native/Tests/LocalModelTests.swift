#if LOCAL_MODEL_TESTS
import Foundation
import MeetupCore

@main
enum LocalModelTests {
    struct Failure: Error, CustomStringConvertible { let description: String }
    static func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
        if !condition() { throw Failure(description: message) }
    }
    static func rejected(_ text: String) throws {
        do { _ = try PreviewImport.parse(text) }
        catch is LocalDataError { return }
        throw Failure(description: "Unsafe/invalid import accepted")
    }
    static func main() throws {
        let tests: [(String, () throws -> Void)] = [
            ("selected language resolves search text from actual translated resources", {
                guard CommandLine.arguments.count > 1, let bundle = Bundle(path: CommandLine.arguments[1]) else {
                    throw Failure(description: "Missing real app resource bundle")
                }
                let english = AppLocalization.text("glHomeMeetupTitle", language: "en", bundle: bundle)
                let german = AppLocalization.text("glHomeMeetupTitle", language: "de", bundle: bundle)
                let spanish = AppLocalization.text("glHomeMeetupTitle", language: "es", bundle: bundle)
                try require(english != "glHomeMeetupTitle" && english != german && english != spanish, "Selected language ignored")
                try require(english.localizedStandardContains("home meetup"), "Displayed English glossary would not match search")
            }),
            ("source compact QR fields including country are previewable", {
                let value = try PreviewImport.parse(#"{"v":2,"t":"meetup","m":"vienna_AT","c":"AT","b":900000,"x":2000000000,"s":"unverified","p":"unverified","e":"unverified","n":"00","ts":123,"d":"rolling_qr"}"#)
                try require(value.name == "vienna_AT" && value.blockHeight == 900000 && !value.isDemo, "Lost compact metadata")
            }),
            ("claims and proof-shaped fields cannot become trust", {
                let value = try PreviewImport.parse(#"{"meetupName":"Example","blockHeight":900001,"presenceVerified":true,"sigVersion":2,"sig":"not-a-signature","claimSig":"not-a-claim","claimPubkey":"not-a-key"}"#)
                let fields = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as! [String: Any]
                try require(Set(fields.keys) == ["id", "name", "importedAt", "blockHeight", "isDemo"], "Retained credential/trust input")
            }),
            ("private key and unknown field imports rejected", {
                try rejected(#"{"meetupName":"Example","nsec":"secret"}"#)
                try rejected(#"{"m":"Example","privateKey":"secret"}"#)
                try rejected(#"{"m":"Example","verified":true}"#)
            }),
            ("malformed, array and arbitrary text imports rejected", {
                try rejected("nsec1do-not-import")
                try rejected(#"[{"m":"Example"}]"#)
                try rejected(#"{"m":{"name":"Example"}}"#)
                try rejected(#"{"m":"   "}"#)
            }),
            ("bounded payload and display name", {
                try rejected(String(repeating: "x", count: 65_537))
                try rejected("{\"m\":\"" + String(repeating: "x", count: 201) + "\"}")
                try rejected(#"{"m":"Example","b":-1}"#)
            }),
            ("preview metadata roundtrips without original payload", {
                let value = try PreviewImport.parse(#"{"m":"Example","sigContent":"discard me"}"#)
                let encoded = try JSONEncoder().encode(value)
                try require(!String(decoding: encoded, as: UTF8.self).contains("discard me"), "Persisted raw content")
                let decoded = try JSONDecoder().decode(BadgePreview.self, from: encoded)
                try require(decoded == value, "Preview metadata lost")
            }),
            ("demo and ordinary planning domains remain independent after persistence", {
                var preferences = LocalPreferences()
                let real = MeetupEvent(id: "real-1", title: "Real", start: Date(timeIntervalSince1970: 100))
                let demo = MeetupEvent(id: "demo-1", title: "Preview", start: Date(timeIntervalSince1970: 200))
                preferences.favoriteIDs = ["real-group"]
                preferences.demoFavoriteIDs = ["demo-group"]
                preferences.plans[real.id] = LocalPlan(event: real, status: .going, note: "private local note")
                preferences.demoPlans[demo.id] = LocalPlan(event: demo, status: .maybe, note: "preview")
                let decoded = try JSONDecoder().decode(LocalPreferences.self, from: JSONEncoder().encode(preferences))
                try require(decoded.plans[demo.id] == nil && decoded.demoPlans[real.id] == nil, "Mixed planning sources")
                try require(decoded.plans[real.id]?.note == "private local note", "Lost saved local note")
                try require(decoded.favoriteIDs.isDisjoint(with: decoded.demoFavoriteIDs), "Mixed favorite sources")
            }),
            ("prototype cannot advertise cryptography", {
                try require(!SecurityCapabilities.signingAvailable && !SecurityCapabilities.verificationAvailable, "Unexpected enabled crypto")
            })
        ]
        for (name, test) in tests { try test(); print("PASS \(name)") }
        print("LocalModels: \(tests.count) tests passed")
    }
}
#endif
