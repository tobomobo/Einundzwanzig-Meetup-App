import SwiftUI
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
                }
            }
        }.navigationTitle("Verify reputation")
        .sheet(isPresented: $camera) { NavigationStack { NativeQRScanner { input = $0; camera = false } } }
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
                TextField("Public npub or hex key", text: $target).autocorrectionDisabled()
                NavigationLink { TrustPersonView(target: target) } label: { Label("Verify observed connection", systemImage: "person.crop.circle.badge.checkmark") }.disabled(target.isEmpty)
            }
            ForEach(1...3, id: \.self) { degree in
                Section("Degree \(degree)") {
                    ForEach(network.byDegree[degree] ?? []) { contact in
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
    }
}

struct TrustPersonView: View {
    @Environment(BadgeStore.self) private var wallet
    let target: String
    @State private var resolved = ""
    private var network: SocialObservedNetwork { wallet.social.network() }
    private var connection: SocialPersonConnection { network.verifyPerson(resolved) }
    var body: some View {
        List {
            Section("Person lookup") {
                Text(target).font(.caption.monospaced()).textSelection(.enabled)
                BadgeActionButton("Load this person's observed network", systemImage: "person.crop.circle.badge.checkmark") {
                    try await wallet.refreshSocial(target: target)
                    resolved = target.hasPrefix("npub1") ? try NIP19.decodePublicKey(target) : target
                }
                Text("The search expands from both public identities through at most three stages of local meetup records.").font(.caption).foregroundStyle(.secondary)
            }
            if !resolved.isEmpty {
                Section("Observed connection") {
                    if connection.degree >= 0 { LabeledContent("Degrees of separation", value: connection.degree.formatted()) }
                    else { Text("No path within three degrees was observed.") }
                    LabeledContent("Shared local sessions", value: connection.sharedMeetups.count.formatted())
                    LabeledContent("Shared large events", value: wallet.social.sharedMeetups(wallet.publicKey, resolved, events: true).count.formatted())
                    LabeledContent("Common follows", value: wallet.social.mutualContacts(wallet.publicKey, resolved).count.formatted())
                    LabeledContent("Social score", value: wallet.social.score(wallet.publicKey, resolved, organizers: wallet.registry.publicKeys).formatted(.number.precision(.fractionLength(1))))
                    if network.stale { Text("Relay observations are incomplete. Cached signed records are shown.").font(.caption) }
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
        .task { resolved = (try? NIP19.decodePublicKey(target)) ?? target }
    }
}

struct TrustProofsView: View {
    @Environment(BadgeStore.self) private var wallet
    @State private var platform = "telegram"
    @State private var username = ""
    @State private var proofString = ""
    @State private var verificationInput = ""
    @State private var verificationResult: PlatformIdentityProof?
    @State private var nip05 = ""
    @State private var nip05Result = ""
    var body: some View {
        Form {
            Section("Platform identity proof") {
                Picker("Platform", selection: $platform) { Text("Telegram").tag("telegram"); Text("X / Twitter").tag("twitter"); Text("GitHub").tag("github"); Text("Signal").tag("signal"); Text("Website").tag("website") }
                TextField("Username", text: $username).autocorrectionDisabled()
                BadgeActionButton("Sign platform proof", systemImage: "signature") { proofString = try await wallet.createPlatformProof(platform: platform, username: username) }.disabled(username.isEmpty)
                if !proofString.isEmpty { Text(proofString).font(.caption.monospaced()).textSelection(.enabled); ShareLink(item: proofString) { Label("Share verification string", systemImage: "square.and.arrow.up") } }
                Text("Place the string on that platform and publish your reputation card so another client can verify the nested signature and username.").font(.caption).foregroundStyle(.secondary)
                ForEach(wallet.platformProofs) { proof in
                    VStack(alignment: .leading) { Text("\(proof.platform): \(proof.username)"); BadgeActionButton("Remove proof locally", systemImage: "minus.circle") { try await wallet.removePlatformProof(proof.id) } }
                }
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
