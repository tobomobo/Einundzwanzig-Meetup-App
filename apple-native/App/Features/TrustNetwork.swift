import SwiftUI
import CoreTransferable
import UniformTypeIdentifiers
import ImageIO
import MeetupNostr

struct ReputationView: View {
    @Environment(AppStore.self) private var app
    @Environment(BadgeStore.self) private var wallet
    var body: some View {
        List {
            Section {
                Label(LocalizedStringKey(wallet.trust.level), systemImage: "shield.lefthalf.filled").font(.title.bold())
                LabeledContent("Verified trust score", value: wallet.trust.total.formatted(.number.precision(.fractionLength(1))))
                LabeledContent("Verified badges", value: wallet.trust.badgeCount.formatted())
                LabeledContent("Local meetups", value: wallet.trust.meetupCount.formatted())
                LabeledContent("Independent issuers", value: wallet.trust.signerCount.formatted())
                LabeledContent("Account age") { Text("\(wallet.trust.accountAgeDays) days") }
                Text("Only source signatures, collector claims and verified issuer authority contribute. Event badges do not count as local meetups.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Organizer progression · \(AppLocalization.text(wallet.trust.thresholds.phase, language: app.preferences.language))") {
                LabeledContent("Badges", value: "\(wallet.trust.badgeCount) / \(wallet.trust.thresholds.badges)")
                LabeledContent("Meetups", value: "\(wallet.trust.meetupCount) / \(wallet.trust.thresholds.meetups)")
                LabeledContent("Issuers", value: "\(wallet.trust.signerCount) / \(wallet.trust.thresholds.signers)")
                LabeledContent("Age in days", value: "\(wallet.trust.accountAgeDays) / \(wallet.trust.thresholds.days)")
                LabeledContent("Score", value: "\(Int(wallet.trust.total.rounded())) / \(Int(wallet.trust.thresholds.score))")
                if wallet.trust.canOrganize { Label("Organizer threshold reached", systemImage: "checkmark.seal") }
            }
            Section("Signed reputation card") {
                NavigationLink { ReputationImageShareView() } label: { Label("Share reputation as an image", systemImage: "photo") }
                BadgeActionButton("Generate reputation QR", systemImage: "qrcode") { try await wallet.generateReputationQR(nickname: app.profile.nickname) }
                if !wallet.reputationQR.isEmpty {
                    BadgeQRView(payload: wallet.reputationQR)
                    ShareLink(item: wallet.reputationQR) { Label("Share signed reputation", systemImage: "square.and.arrow.up") }
                }
                BadgeActionButton("Publish reputation to relays", systemImage: "antenna.radiowaves.left.and.right") { try await wallet.publishReputation(nickname: app.profile.nickname) }
                NavigationLink { ReputationVerifyView() } label: { Label("Verify a reputation card", systemImage: "checkmark.shield") }
            }
            Section {
                BadgeActionButton("Refresh issuer authority", systemImage: "arrow.clockwise") { try await wallet.refreshRegistry() }
                BadgeActionButton("Claim old unbound badges", systemImage: "signature") { try await wallet.claimUnbound() }
                NavigationLink { TrustRegistryView() } label: { Label("Registry and admin vouches", systemImage: "person.badge.shield.checkmark") }
                NavigationLink { TrustNetworkView() } label: { Label("Trust and social network", systemImage: "person.3.sequence") }
                NavigationLink { TrustProofsView() } label: { Label("Identity and humanity proofs", systemImage: "person.text.rectangle") }
                NavigationLink { OrganizerHubView() } label: { Label("Organizer sessions", systemImage: "qrcode") }
                if !wallet.status.isEmpty { Text(LocalizedStringKey(wallet.status)).font(.caption).foregroundStyle(.secondary) }
            }
        }.navigationTitle("Reputation")
    }
}

struct ReputationVerifyView: View {
    @Environment(BadgeStore.self) private var wallet
    @State private var input = ""
    @State private var result: VerifiedReputationCard?
    @State private var camera = false
    @State private var evidenceJSON = ""
    var body: some View {
        Form {
            Section("Signed QR or public npub") {
                TextEditor(text: $input).font(.system(.body, design: .monospaced)).frame(minHeight: 140)
                Button("Scan reputation QR") { camera = true }
                DisclosureGroup("Optional full badge evidence") {
                    TextEditor(text: $evidenceJSON).font(.caption.monospaced()).frame(minHeight: 120)
                    Text("Paste a JSON array of the collector's full badge proofs to verify the proof hash, both signatures and current issuer authority.").font(.caption)
                }
                BadgeActionButton("Verify reputation and available evidence", systemImage: "checkmark.shield") {
                    var evidence: [CollectedBadge]?
                    if !evidenceJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        let value = try BadgeWire.parse(evidenceJSON)
                        guard case .array(let raw) = value else { throw BadgeProtocolError.malformed }
                        evidence = try raw.map { try CollectedBadge(json: $0.object) }
                    }
                    result = try await wallet.verifyReputation(input, badgeEvidence: evidence)
                }.disabled(input.isEmpty)
            }
            if let result {
                Section("Authenticated statement") {
                    Text(result.nickname).font(.headline)
                    Text(result.event.pubkey).font(.caption.monospaced()).textSelection(.enabled)
                    LabeledContent("Advertised badges", value: result.advertisedBadges.formatted())
                    LabeledContent("Advertised score", value: result.advertisedScore.formatted())
                    Label("Author signature verified", systemImage: "checkmark.seal")
                    Text(LocalizedStringKey(result.badgeEvidenceVerified ? "Presented badge evidence independently verified." : "These counts are the author's signed statement. The underlying badge and issuer evidence has not been presented for independent verification.")).font(.caption).foregroundStyle(.secondary)
                    Text("Proof v\(result.proofVersion): \(result.badgeProof)").font(.caption.monospaced()).textSelection(.enabled)
                    NavigationLink { TrustPersonView(target: result.event.pubkey, signedReputation: result) } label: { Label("Verify observed connection", systemImage: "person.2") }
                }
            }
        }.navigationTitle("Verify reputation")
        .sheet(isPresented: $camera) { NavigationStack { NativeQRScanner { input = $0; camera = false } } }
        .onChange(of: input) { result = nil }
        .onChange(of: evidenceJSON) { result = nil }
        .onChange(of: wallet.generation) { result = nil }
    }
}

struct TrustRegistryView: View {
    @Environment(BadgeStore.self) private var wallet
    @State private var npub = ""
    @State private var meetup = ""
    @State private var name = ""
    @State private var revokeAll = false
    @State private var revokeError: String?
    var body: some View {
        List {
            Section("Verified registry") {
                LabeledContent("Bootstrap sunset") { Text(LocalizedStringKey(wallet.registry.sunsetActive ? "Active" : "Bootstrap")) }
                LabeledContent("Verified list authors", value: Set(wallet.registry.sunsetAuthorEvidence.map(\.pubkey)).count.formatted())
                BadgeActionButton("Refresh signed registry", systemImage: "arrow.clockwise") { try await wallet.refreshRegistry() }
                ForEach(wallet.registry.admins + wallet.registry.organicAdmins) { admin in
                    VStack(alignment: .leading) { Text(admin.name.isEmpty ? admin.meetup : admin.name).font(.headline); Text(admin.npub).font(.caption.monospaced()).textSelection(.enabled) }
                }
                Text("The sunset threshold counts distinct verified list signers, never the number of names listed by one signer.").font(.caption).foregroundStyle(.secondary)
            }
            Section("My personal vouches") {
                ForEach(wallet.myVouches) { admin in
                    VStack(alignment: .leading) {
                        Text(admin.name.isEmpty ? admin.meetup : admin.name)
                        Text(admin.npub).font(.caption.monospaced())
                        BadgeActionButton("Remove local vouch", systemImage: "minus.circle") { try await wallet.removeVouch(admin.npub) }
                    }
                }
                TextField("Public npub", text: $npub).autocorrectionDisabled()
                TextField("Meetup", text: $meetup)
                TextField("Display name", text: $name)
                BadgeActionButton("Add personal vouch", systemImage: "person.badge.plus") { try await wallet.addVouch(npub: npub, meetup: meetup, name: name); npub = ""; name = "" }.disabled(npub.isEmpty)
                BadgeActionButton("Publish my signed vouch list", systemImage: "antenna.radiowaves.left.and.right") { try await wallet.publishVouches() }
                BadgeActionButton("Recover my list from relays", systemImage: "arrow.down.doc") { try await wallet.recoverVouches() }
                Button("Revoke all published vouches", role: .destructive) { revokeAll = true }
                if let revokeError { Text(LocalizedStringKey(revokeError)).font(.caption).foregroundStyle(.red) }
                Text("Editing this list stays local until you publish. An empty published list revokes your earlier vouches.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Promotion proof") {
                TextField("Meetup for organizer claim", text: $meetup)
                BadgeActionButton("Publish verified organizer claim", systemImage: "checkmark.seal") { try await wallet.publishPromotion(meetup: meetup) }.disabled(!wallet.trust.canOrganize)
            }
            if !wallet.status.isEmpty { Text(LocalizedStringKey(wallet.status)).font(.caption) }
        }.navigationTitle("Issuer registry")
        .confirmationDialog("Revoke every published vouch?", isPresented: $revokeAll, titleVisibility: .visible) {
            Button("Publish empty signed list", role: .destructive) { Task {
                do { try await wallet.publishVouches(revokeAll: true); revokeError = nil }
                catch { revokeError = error.localizedDescription }
            } }
        }
    }
}

struct TrustNetworkView: View {
    @Environment(BadgeStore.self) private var wallet
    @State private var target = ""
    @State private var query = ""
    @State private var degreeFilter = 0
    @State private var camera = false
    private var network: SocialObservedNetwork { wallet.social.network() }
    var body: some View {
        List {
            Section("My observed meetup network") {
                BadgeActionButton("Build or refresh my network", systemImage: "person.3.sequence") { try await wallet.refreshSocial() }
                BadgeActionButton("Reload all three degrees", systemImage: "arrow.clockwise") { try await wallet.refreshSocial(forceFull: true) }
                LabeledContent("First degree", value: (network.byDegree[1]?.count ?? 0).formatted())
                LabeledContent("Second degree", value: (network.byDegree[2]?.count ?? 0).formatted())
                LabeledContent("Third degree", value: (network.byDegree[3]?.count ?? 0).formatted())
                if wallet.busy { ProgressView("Loading network stage \(wallet.socialLoadingDepth) of 3") }
                if network.stale { Label("Showing cached or incomplete relay observations", systemImage: "wifi.exclamationmark") }
                if network.truncated { Label("The bounded search reached its record limit", systemImage: "ellipsis.circle") }
                Text("Edges show matching signed self-attestations at local meetup sessions. Large-event coattendance stays in a separate category. Each badge's source and ownership evidence is verified separately.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Look up a person") {
                TextField("Public npub or signed reputation QR", text: $target).autocorrectionDisabled()
                Button("Scan person QR") { camera = true }
                NavigationLink { TrustPersonView(target: target) } label: { Label("Verify observed connection", systemImage: "person.crop.circle.badge.checkmark") }.disabled(target.isEmpty)
            }
            Picker("Network degree", selection: $degreeFilter) {
                Text("All degrees").tag(0)
                ForEach(1...3, id: \.self) { Text("Degree \($0)").tag($0) }
            }
            ForEach((1...3).filter { degreeFilter == 0 || $0 == degreeFilter }, id: \.self) { degree in
                Section("Degree \(degree)") {
                    ForEach((network.byDegree[degree] ?? []).filter { contact in
                        query.isEmpty || contact.publicKey.localizedCaseInsensitiveContains(query) ||
                        ((try? NIP19.encodePublicKey(contact.publicKey)) ?? "").localizedCaseInsensitiveContains(query)
                    }) { contact in
                        NavigationLink { TrustPersonView(target: contact.publicKey) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text((try? NIP19.encodePublicKey(contact.publicKey)) ?? contact.publicKey).font(.caption.monospaced()).lineLimit(1)
                                if degree == 1 { Text("\(contact.sharedMeetups.count) shared local sessions").font(.caption).foregroundStyle(.secondary) }
                                else { Text("\(contact.bridges.count) direct bridge contacts").font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
            if !wallet.status.isEmpty { Text(LocalizedStringKey(wallet.status)).font(.caption) }
        }.navigationTitle("Trust network")
        .searchable(text: $query, prompt: "Search public identities")
        .sheet(isPresented: $camera) { NavigationStack { NativeQRScanner { target = $0; camera = false }.navigationTitle("Scan person QR") } }
        .onChange(of: wallet.generation) { target = ""; camera = false }
    }
}

struct TrustPersonView: View {
    @Environment(BadgeStore.self) private var wallet
    let target: String
    var signedReputation: VerifiedReputationCard? = nil
    @State private var reputationEvidence: VerifiedReputationCard?
    @State private var resolved = ""
    @State private var lookupError: String?
    @State private var identityEvidence: PersonIdentityEvidence?
    @State private var zapActivity: PersonZapActivity?
    @State private var activityError: String?
    private var network: SocialObservedNetwork { wallet.social.network() }
    private var connection: SocialPersonConnection { network.verifyPerson(resolved) }
    var body: some View {
        List {
            Section("Person lookup") {
                Text(target).font(.caption.monospaced()).textSelection(.enabled)
                BadgeActionButton("Load this person's observed network", systemImage: "person.crop.circle.badge.checkmark") {
                    try await loadPerson()
                }
                Text("The search expands from both public identities through at most three stages of local meetup records.").font(.caption).foregroundStyle(.secondary)
                if wallet.busy { ProgressView() }
                if let lookupError { Text(LocalizedStringKey(lookupError)).foregroundStyle(.red) }
            }
            if !resolved.isEmpty {
                if let identityEvidence {
                    Section("Public identity") {
                        if let name = identityEvidence.displayName(for: resolved, reputation: reputationEvidence) { Text(name).font(.headline) }
                        if let identifier = identityEvidence.profile?.nip05 { Text(identifier).textSelection(.enabled) }
                        switch identityEvidence.nip05Status {
                        case .verified: Label("NIP-05 domain mapping verified", systemImage: "checkmark.seal")
                        case .mismatch: Label("NIP-05 domain maps to a different public key", systemImage: "exclamationmark.shield")
                        case .unavailable: Label("NIP-05 domain could not be checked", systemImage: "wifi.exclamationmark")
                        case .absent:
                            Text(LocalizedStringKey(identityEvidence.profile?.profile == nil ? "No verified public profile was found." : "No NIP-05 identifier in the signed public profile.")).foregroundStyle(.secondary)
                        }
                        if identityEvidence.profile?.profile != nil { Text("Display name comes from this person's signed Nostr profile.").font(.caption).foregroundStyle(.secondary) }
                        else if identityEvidence.displayName(for: resolved, reputation: reputationEvidence) != nil { Text("Display name comes from this person's signed reputation card.").font(.caption).foregroundStyle(.secondary) }
                    }
                }
                Section("Observed connection") {
                    if let npub = try? NIP19.encodePublicKey(resolved), let url = URL(string: "https://njump.me/" + npub) {
                        ShareLink(item: "nostr:" + npub) { Label("Share public identity", systemImage: "square.and.arrow.up") }
                        Link(destination: url) { Label("Open Nostr profile", systemImage: "person.crop.circle") }
                    }
                    if connection.degree >= 0 { LabeledContent("Degrees of separation", value: connection.degree.formatted()) }
                    else { Text("No path within three degrees was observed.") }
                    LabeledContent("Shared local sessions", value: connection.sharedMeetups.count.formatted())
                    LabeledContent("Shared large events", value: wallet.social.sharedMeetups(wallet.publicKey, resolved, events: true).count.formatted())
                    LabeledContent("Common follows", value: wallet.social.mutualContacts(wallet.publicKey, resolved).count.formatted())
                    LabeledContent("Social score", value: wallet.social.score(wallet.publicKey, resolved, organizers: wallet.registry.publicKeys).formatted(.number.precision(.fractionLength(1))))
                    if network.stale { Text("Relay observations are incomplete. Cached signed records are shown.").font(.caption) }
                }
                Section("Public follow details") {
                    LabeledContent("You follow this person") { Text(LocalizedStringKey(wallet.social.follows[wallet.publicKey]?.contains(resolved) == true ? "Yes" : "No")) }
                    LabeledContent("This person follows you") { Text(LocalizedStringKey(wallet.social.follows[resolved]?.contains(wallet.publicKey) == true ? "Yes" : "No")) }
                    LabeledContent("Organizers following this person", value: wallet.registry.publicKeys.filter { wallet.social.follows[$0]?.contains(resolved) == true }.count.formatted())
                    DisclosureGroup("Shared local sessions") {
                        ForEach(connection.sharedMeetups.sorted(), id: \.self) { Text($0).font(.caption).textSelection(.enabled) }
                    }
                    DisclosureGroup("Shared large events") {
                        ForEach(wallet.social.sharedMeetups(wallet.publicKey, resolved, events: true).sorted(), id: \.self) { Text($0).font(.caption).textSelection(.enabled) }
                    }
                    DisclosureGroup("Common follows") {
                        ForEach(wallet.social.mutualContacts(wallet.publicKey, resolved).sorted(), id: \.self) { key in
                            Text((try? NIP19.encodePublicKey(key)) ?? key).font(.caption.monospaced()).textSelection(.enabled)
                        }
                    }
                    Text("Follows are signed public observations. They do not establish badge ownership or organizer authority.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Lightning activity · last 180 days") {
                    BadgeActionButton("Refresh public zap activity", systemImage: "bolt.shield") { try await loadActivity(resolved) }
                    if let zapActivity, zapActivity.publicKey == resolved {
                        LabeledContent("Provider-verified zaps sent", value: zapActivity.sentCount.formatted())
                        LabeledContent("Provider-verified zaps received", value: zapActivity.receivedCount.formatted())
                        LabeledContent("Distinct recipients", value: zapActivity.uniqueRecipients.formatted())
                        LabeledContent("Distinct senders", value: zapActivity.uniqueSenders.formatted())
                        LabeledContent("Activity span in months", value: zapActivity.activeMonths.formatted())
                        if zapActivity.totalCount == 0 { Text("No provider-verified zaps were observed.") }
                        if zapActivity.incomplete { Label("Some relay or provider checks were unavailable. Counts may be incomplete.", systemImage: "wifi.exclamationmark") }
                        if zapActivity.truncated { Label("Activity lookup reached its record or provider limit. Counts are a bounded sample.", systemImage: "ellipsis.circle") }
                        Text("Checked at \(Date(timeIntervalSince1970: Double(zapActivity.checkedAt)).formatted())").font(.caption)
                    }
                    if let activityError { Text(LocalizedStringKey(activityError)).foregroundStyle(.red) }
                    Text("Counts require signed receipts and requests, invoice binding and the recipient's LNURL provider key. The sent-receipt index can omit activity. No payment is made, and these counts do not change badge trust.").font(.caption).foregroundStyle(.secondary)
                }
                if !connection.path.isEmpty {
                    Section("Shortest observed meetup path") {
                        ForEach(Array(connection.path.enumerated()), id: \.offset) { step in
                            VStack(alignment: .leading, spacing: 6) {
                                if step.offset == 0 { Text("You").font(.headline) }
                                else { Text("Person \(step.offset)").font(.headline) }
                                Text((try? NIP19.encodePublicKey(step.element)) ?? step.element).font(.caption.monospaced()).textSelection(.enabled)
                                if step.offset > 0 {
                                    ForEach(network.sharedBetween(connection.path[step.offset - 1], step.element).sorted(), id: \.self) { key in
                                        Label(key, systemImage: "person.2").font(.caption)
                                    }
                                }
                            }.padding(.vertical, 4)
                        }
                        Text("This chain is based on signed attendance statements. It does not establish verified badge ownership or organizer authority.").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }.navigationTitle("Person connection")
        .onChange(of: wallet.generation) { resolved = ""; identityEvidence = nil; reputationEvidence = nil; zapActivity = nil; activityError = nil; lookupError = nil }
        .task(id: target) {
            let epoch = wallet.generation
            do { try await loadPerson() }
            catch { if epoch == wallet.generation, !Task.isCancelled { lookupError = error.localizedDescription } }
        }
    }
    private func loadPerson() async throws {
        lookupError = nil
        let epoch = wallet.generation
        let person = try await SocialNetwork.personPublicKey(target, now: Int(Date().timeIntervalSince1970))
        let identity = try await wallet.personIdentity(person)
        guard epoch == wallet.generation else { throw BadgeStoreError.identityChanged }
        var reputation: VerifiedReputationCard?
        if identity.profile?.profile == nil {
            if signedReputation?.event.pubkey == person { reputation = signedReputation }
            else { reputation = try? await wallet.verifyReputation(target) }
        }
        guard epoch == wallet.generation, !Task.isCancelled else { throw BadgeStoreError.identityChanged }
        resolved = person; identityEvidence = identity; reputationEvidence = reputation; zapActivity = nil; activityError = nil
        // A missing graph must not hide independently available Lightning data.
        do { try await wallet.refreshSocial(target: person) }
        catch {
            guard epoch == wallet.generation else { throw BadgeStoreError.identityChanged }
            lookupError = error.localizedDescription
        }
        do { try await loadActivity(person) }
        catch {
            guard epoch == wallet.generation else { throw BadgeStoreError.identityChanged }
            activityError = error.localizedDescription
        }
        guard epoch == wallet.generation else { throw BadgeStoreError.identityChanged }
    }
    private func loadActivity(_ person: String) async throws {
        let epoch = wallet.generation
        activityError = nil
        let activity = try await wallet.personZapActivity(person)
        guard epoch == wallet.generation, resolved == person, !Task.isCancelled else { throw BadgeStoreError.identityChanged }
        zapActivity = activity
    }
}

struct TrustProofsView: View {
    @Environment(AppStore.self) private var app
    @Environment(BadgeStore.self) private var wallet
    @State private var platform = "telegram"
    @State private var customPlatform = ""
    private var proofPlatform: String { platform == "other" ? customPlatform.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: " ", with: "_") : platform }
    @State private var username = ""
    @State private var selectedProofID = ""
    private var proofString: String { (try? wallet.platformProofString(selectedProofID)) ?? "" }
    @State private var verificationInput = ""
    @State private var verificationResult: PlatformIdentityProof?
    @State private var nip05 = ""
    @State private var nip05Result = ""
    var body: some View {
        Form {
            Section("Platform identity proof") {
                Picker("Platform", selection: $platform) {
                    Text("Satoshi-Kleinanzeigen").tag("satoshikleinanzeigen")
                    Text("Telegram").tag("telegram"); Text("RoboSats").tag("robosats"); Text("Nostr").tag("nostr")
                    Text("X / Twitter").tag("twitter"); Text("GitHub").tag("github"); Text("Signal").tag("signal"); Text("Website").tag("website")
                    Text("Other platform").tag("other")
                }
                if platform == "other" { TextField("Platform name", text: $customPlatform).autocorrectionDisabled() }
                TextField("Username", text: $username).autocorrectionDisabled()
                BadgeActionButton("Sign platform proof", systemImage: "signature") {
                    let selected = proofPlatform
                    _ = try await wallet.createPlatformProof(platform: selected, username: username); selectedProofID = selected
                }.disabled(username.isEmpty || proofPlatform.isEmpty)
                if !proofString.isEmpty { Text(proofString).font(.caption.monospaced()).textSelection(.enabled); ShareLink(item: proofString) { Label("Share verification string", systemImage: "square.and.arrow.up") } }
                Text("Place the string on that platform and publish your reputation card so another client can verify the nested signature and username.").font(.caption).foregroundStyle(.secondary)
                ForEach(wallet.platformProofs) { proof in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(proof.platform): \(proof.username)")
                        if let text = try? wallet.platformProofString(proof.id) {
                            ShareLink(item: text) { Label("Share verification string", systemImage: "square.and.arrow.up") }
                        }
                        BadgeActionButton("Remove proof locally", systemImage: "minus.circle") { try await wallet.removePlatformProof(proof.id) }
                    }
                }
            }
            Section {
                BadgeActionButton("Publish current identity proofs", systemImage: "antenna.radiowaves.left.and.right") {
                    try await wallet.publishReputation(nickname: app.profile.nickname, force: true)
                }
                Text("Publish after adding or removing a proof to update your public reputation card. This also removes revoked platform bindings from the replacement card.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Verify a platform string") {
                TextEditor(text: $verificationInput).frame(minHeight: 90).font(.caption.monospaced())
                TextField("Expected username", text: $username)
                BadgeActionButton("Verify against signed relay reputation", systemImage: "checkmark.shield") {
                    let proof = try await wallet.verifyPlatformProof(verificationInput, expectedUsername: username.isEmpty ? nil : username)
                    verificationResult = proof
                }.disabled(verificationInput.isEmpty)
                if let verificationResult { Text("Verified signed binding: \(verificationResult.platform) · \(verificationResult.username)") }
            }
            Section("NIP-05") {
                TextField("name@example.com", text: $nip05).autocorrectionDisabled()
                BadgeActionButton("Check my public-key mapping", systemImage: "at") { nip05Result = try await wallet.checkNIP05(nip05) ? "Domain mapping matches your public key." : "Domain mapping does not match your public key." }.disabled(nip05.isEmpty)
                if !nip05Result.isEmpty { Text(LocalizedStringKey(nip05Result)) }
            }
            Section("Humanity receipt") {
                if let humanity = wallet.humanity { Label("Recipient provider and signed zap receipt verified", systemImage: "checkmark.seal"); Text(humanity.receipt.id).font(.caption.monospaced()) }
                else { Text("No verified provider receipt is currently cached.") }
                BadgeActionButton("Recheck received and sent zap receipts", systemImage: "bolt.shield") { try await wallet.checkHumanity() }
                Text("Checks signed receipt and request, matching recipient, invoice description hash and the recipient's LNURL provider key. This action does not make a payment.").font(.caption).foregroundStyle(.secondary)
                if !wallet.pendingBackup.isEmpty { Text("Restored proof metadata awaits independent revalidation.").font(.caption) }
            }
            if !wallet.status.isEmpty { Text(LocalizedStringKey(wallet.status)).font(.caption) }
        }.navigationTitle("Identity proofs")
    }
}

/// Sharing uses an in-memory PNG. Only the visible immutable summary is
/// rendered; no files, remote images, raw badge proofs or private profile data.
private struct ReputationPNG: Transferable {
    let data: Data
    let summary: ReputationImageSummary
    let generation: Int
    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .png) { $0.data }
            .suggestedFileName("einundzwanzig_reputation_card.png")
    }
}

struct ReputationImageShareView: View {
    @Environment(AppStore.self) private var app
    @Environment(BadgeStore.self) private var wallet
    @Environment(\.locale) private var locale
    @State private var prepared: ReputationPNG?
    @State private var renderError = false
    private var summary: ReputationImageSummary? { try? wallet.reputationImageSummary(nickname: app.profile.nickname) }
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                if let summary {
                    ReputationImageCard(summary: summary)
                        .frame(maxWidth: 380)
                    Text("Only this public summary is included in the image. Full badge evidence and private profile fields are excluded.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button { prepare(summary) } label: { Label("Prepare PNG image", systemImage: "photo") }
                        .disabled(wallet.busy)
                    if let prepared, prepared.generation == wallet.generation, prepared.summary == summary {
                        ShareLink(item: prepared, preview: SharePreview(Text("Reputation card"))) {
                            Label("Share PNG image", systemImage: "square.and.arrow.up")
                        }
                    }
                    if renderError { Text("The reputation image could not be rendered. Please try again.").foregroundStyle(.red) }
                }
            }.padding()
        }.navigationTitle("Reputation card")
        .onChange(of: wallet.generation) { prepared = nil; renderError = false }
        .onChange(of: summary) { prepared = nil; renderError = false }
    }
    @MainActor private func prepare(_ summary: ReputationImageSummary) {
        prepared = nil; renderError = false
        let epoch = wallet.generation
        let renderer = ImageRenderer(content: ReputationImageCard(summary: summary)
            .frame(width: 380).environment(\.locale, locale).environment(\.colorScheme, .dark))
        renderer.scale = 3; renderer.isOpaque = true
        guard let image = renderer.cgImage else { renderError = true; return }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, UTType.png.identifier as CFString, 1, nil) else { renderError = true; return }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination), data.length <= 10_485_760,
              epoch == wallet.generation, self.summary == summary else { renderError = true; return }
        prepared = ReputationPNG(data: data as Data, summary: summary, generation: epoch)
    }
}

private struct ReputationImageCard: View {
    let summary: ReputationImageSummary
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Label("Einundzwanzig", systemImage: "shield.lefthalf.filled").font(.headline).foregroundStyle(.orange)
            Text(summary.nickname.isEmpty ? "Anon" : summary.nickname).font(.largeTitle.bold()).lineLimit(3)
            Text(LocalizedStringKey(summary.level)).font(.title2.bold()).foregroundStyle(.orange)
            Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 14) {
                GridRow {
                    metric("Verified trust score", value: summary.score.formatted(.number.precision(.fractionLength(1))))
                    metric("Verified badges", value: summary.badgeCount.formatted())
                }
                GridRow {
                    metric("Local meetups", value: summary.meetupCount.formatted())
                    metric("Independent issuers", value: summary.signerCount.formatted())
                }
                GridRow { metric("Age in days", value: summary.accountAgeDays.formatted()); Spacer(minLength: 0) }
            }
            Text((try? NIP19.encodePublicKey(summary.publicKey)) ?? summary.publicKey)
                .font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
            Text("Visual summary. Verify signed reputation and badge evidence separately.")
                .font(.caption).foregroundStyle(.white.opacity(0.7))
        }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .background(LinearGradient(colors: [Color(red: 0.16, green: 0.09, blue: 0.03), Color(red: 0.06, green: 0.06, blue: 0.07)], startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .background(.black)
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(.orange.opacity(0.5)))
    }
    private func metric(_ label: LocalizedStringKey, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.title2.bold())
            Text(label).font(.caption).foregroundStyle(.white.opacity(0.7))
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
