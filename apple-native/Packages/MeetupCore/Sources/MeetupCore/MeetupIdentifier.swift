import Foundation

/// Same city-space/country rule as Flutter MeetupKey; resolving a directory
/// entry does not itself establish organizer authority.
public enum MeetupIdentifier {
    public static func slug(_ city: String) -> String { city.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: " ", with: "-") }
    public static func compose(city: String, country: String) -> String {
        let value = slug(city), country = country.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return isoCountries.contains(country) ? value + "-" + country.lowercased() : value
    }
    public static func resolve(_ signedID: String, among meetups: [Meetup]) -> Meetup? {
        let value = signedID.trimmingCharacters(in: .whitespacesAndNewlines)
        if let numeric = Int(value), numeric > 0 { return meetups.first { $0.id == value } }
        let parts = value.split(separator: "-", omittingEmptySubsequences: false)
        let last = parts.last.map(String.init)?.uppercased() ?? ""
        let hasCountry = parts.count > 1 && isoCountries.contains(last)
        let city = hasCountry ? parts.dropLast().joined(separator: "-") : value
        let matches = meetups.filter { slug($0.city) == city && (!hasCountry || $0.country.uppercased() == last) }
        return matches.count == 1 ? matches[0] : nil
    }
    private static let isoCountries: Set<String> = Set("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW".split(separator: " ").map(String.init))
}
