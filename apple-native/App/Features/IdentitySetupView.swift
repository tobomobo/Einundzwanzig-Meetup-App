import SwiftUI
import UniformTypeIdentifiers
import MeetupNostr

struct IdentitySetupView: View {
    @Environment(IdentityStore.self) private var identity
    @Environment(\.scenePhase) private var scenePhase
    @State private var input = ""
    @State private var bunkerURI = ""
    @State private var vaultPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var keyPassword = ""
    @State private var backupPassword = ""
    @State private var result = ""
    @State private var message: String?
    @State private var importedPayload = ""
    @State private var reviewed: BackupDocument?
    @State private var importing = false
    @State private var importingLegacyKeys = false
    @State private var legacyKeyJSON: Data?
    @State private var legacyNpub = ""
    @State private var upgradeReview: BackupDocument?
    @State private var removeFlutterOriginals = false
    @State private var exporting = false
    @State private var authBrowserPresented = false
    @State private var exportName = "identity.ncryptsec"
    @State private var replacing = false
    @State private var rawExport = false
    @State private var discarding = false
    @State private var pendingAction: (@MainActor () async throws -> Void)?

    var body: some View {
        Form {
            Section("Signing identity") {
                LabeledContent("Vault") { Text(LocalizedStringKey(status)) }
                if !identity.npub.isEmpty {
                    Text(identity.npub).font(.caption.monospaced()).textSelection(.enabled).accessibilityIdentifier("identity.npub")
                }
                if identity.busy { ProgressView("Working… Keep the remote signer available if requested.") }
                if identity.authURL != nil { Button("Authorize in your signer") { authBrowserPresented = true } }
                if let issue = message ?? identity.issue { Text(LocalizedStringKey(issue)).foregroundStyle(.orange) }
                if identity.state == .unlocked {
                    Button("Lock vault") { run { await identity.lock(); clearSecrets() } }.accessibilityIdentifier("identity.lock")
                } else if identity.state == .locked {
                    SecureField("Vault password", text: $vaultPassword).accessibilityIdentifier("identity.password")
                    Button("Unlock") { run { try await identity.unlock(password: vaultPassword); vaultPassword = "" } }.accessibilityIdentifier("identity.unlock")
                }
            }
            if identity.state == .recoveryRequired {
                Section("Protected recovery") {
                    Text("An import or migration is pending. Its encrypted recovery copy remains in Keychain until it completes or you discard it.")
                    SecureField("Backup password", text: $backupPassword)
                    SecureField("Vault password", text: $vaultPassword)
                    if identity.legacyAvailable {
                        Text("Existing Flutter identity, profile, or wallet data was found. Review and migrate it before creating a replacement identity. Unreadable originals are kept intact.")
                        if identity.legacyMigrationPending {
                            Button("Resume complete Flutter migration") {
                                run { try await identity.resumeFlutterInstallation(vaultPassword: vaultPassword); clearSecrets(); message = "Flutter migration completed." }
                            }
                        } else {
                            Button("Review existing Flutter installation") { run { upgradeReview = try identity.reviewFlutterInstallation() } }
                            if let upgradeReview {
                                Text("Profile: \(upgradeReview.fields["user"]?.object?["nickname"]?.string ?? "Anon") · \(upgradeReview.fields["badges"]?.array?.count ?? 0) badge records")
                                Text(upgradeReview.identityNpub.isEmpty ? "A new native identity will protect this keyless collection." : upgradeReview.identityNpub).font(.caption).textSelection(.enabled)
                                Toggle("Remove originals only after native data is saved", isOn: $removeFlutterOriginals)
                                Text("By default originals remain. A password-encrypted source archive also preserves Flutter key backups and wrapped-key bytes.").font(.caption)
                                Button("Migrate reviewed profile, identity, and wallet") {
                                    run { try await identity.migrateFlutterInstallation(vaultPassword: vaultPassword, removeOriginals: removeFlutterOriginals); clearSecrets(); message = "Flutter data migrated and saved." }
                                }
                            }
                            Button("Choose a complete Flutter backup for recovery…") { importingLegacyKeys = false; importing = true }
                            if reviewed != nil {
                                Button("Recover from reviewed backup; retain originals") {
                                    run { try await identity.recoverFlutterInstallation(importedPayload, backupPassword: backupPassword, vaultPassword: vaultPassword); clearSecrets(); message = "Backup recovered. Original Flutter data remains intact." }
                                }
                            }
                        }
                    }
                    Button("Retry protected storage") { run { try await identity.retryVaultRead() } }
                    Button("Resume backup restore") {
                        run { try await identity.resumePendingRestore(password: backupPassword, vaultPassword: vaultPassword); clearSecrets(); message = "Backup restored." }
                    }
                    Button("Resume Flutter key migration") {
                        run { try await identity.resumePendingMigration(vaultPassword: vaultPassword); clearSecrets(); message = "Migration completed." }
                    }
                    Button("Discard pending recovery copy", role: .destructive) { discarding = true }.disabled(identity.legacyAvailable)
                }
            } else {
                Section("Create or import a local identity") {
                    SecureField("New vault password (at least 8 characters)", text: $newPassword).accessibilityIdentifier("identity.newPassword")
                    SecureField("Confirm new vault password", text: $confirmPassword).accessibilityIdentifier("identity.confirmPassword")
                    Button("Create secure random identity") {
                        confirmReplacement { try requireNewPassword(); try await identity.create(vaultPassword: newPassword); clearSecrets(); message = "Identity created. Export an encrypted recovery key before changing devices." }
                    }.accessibilityIdentifier("identity.create")
                    SecureField("Private key: hex, nsec, or ncryptsec", text: $input)
                        .privacySensitive()
                        .autocorrectionDisabled()
                    SecureField("ncryptsec password, if encrypted", text: $keyPassword)
                    Button("Import private key") {
                        confirmReplacement { try requireNewPassword(); try await identity.importKey(input, keyPassword: keyPassword, vaultPassword: newPassword); clearSecrets(); message = "Identity imported." }
                    }.disabled(input.isEmpty).accessibilityIdentifier("identity.import")
                }
                Section("Remote signer") {
                    Text("Paste the bunker address from your signer. Approve its requests there. Its signing key remains with that signer; this device stores an encrypted session key in Keychain.")
                    SecureField("bunker://…", text: $bunkerURI).autocorrectionDisabled().privacySensitive()
                    Button("Connect bunker") {
                        confirmReplacement { try requireNewPassword(); try await identity.connectBunker(bunkerURI, vaultPassword: newPassword); clearSecrets(); message = "Remote signer connected." }
                    }.disabled(bunkerURI.isEmpty)
                }
                if !identity.publicKey.isEmpty {
                    Section("Export and password") {
                        SecureField("Current vault password", text: $vaultPassword)
                        SecureField("Export / backup password", text: $backupPassword)
                        Button("Export encrypted recovery key (NIP-49)") {
                            run {
                                result = try await identity.exportNIP49(vaultPassword: vaultPassword, exportPassword: backupPassword)
                                exportName = "identity.ncryptsec"; exporting = true; vaultPassword = ""; backupPassword = ""
                            }
                        }
                        Button("Export complete encrypted backup (.21bkp)") {
                            run {
                                result = try await identity.exportBackup(password: backupPassword, vaultPassword: vaultPassword)
                                exportName = "meetup-\(Date().formatted(.iso8601.year().month().day())).21bkp"; exporting = true
                                vaultPassword = ""; backupPassword = ""
                            }
                        }
                        Button("Reveal unencrypted nsec…", role: .destructive) { rawExport = true }
                        Button("Change vault password") {
                            run { try requireNewPassword(); try await identity.changePassword(current: vaultPassword, replacement: newPassword); clearSecrets(); message = "Vault password changed." }
                        }
                    }
                }
                Section("Restore or migrate") {
                    Text("Open an encrypted .21bkp or a Flutter JSON backup. Review the identity before restoring. A password also protects the pending recovery copy when importing JSON.")
                    SecureField("Backup password", text: $backupPassword)
                    SecureField("Password for the restored vault", text: $vaultPassword)
                    Button("Choose backup or Flutter JSON backup…") { importingLegacyKeys = false; importing = true }
                    if let reviewed {
                        let decision = reviewed.decision(existingNpub: identity.npub)
                        Text("Backup identity: \(reviewed.identityNpub.isEmpty ? "No signing identity" : reviewed.identityNpub)")
                            .font(.caption).textSelection(.enabled)
                        Text(LocalizedStringKey(restoreExplanation(decision))).foregroundStyle(decision == .replacesIdentity ? .orange : .primary)
                        Button(LocalizedStringKey(decision == .replacesIdentity ? "Confirm replacement and restore" : "Confirm restore")) {
                            run {
                                try await identity.restoreBackup(importedPayload, password: backupPassword, vaultPassword: vaultPassword,
                                                                 allowReplacement: true, allowUnidentified: true)
                                self.reviewed = nil; importedPayload = ""; clearSecrets(); message = "Backup restored. Imported proofs require relay verification."
                            }
                        }
                    }
                    Button("Migrate this app's Flutter Keychain identity") {
                        confirmReplacement { try requireNewPassword(); try await identity.migrateFlutterKeychain(vaultPassword: newPassword); clearSecrets(); message = "Flutter identity migrated. The original entries were removed only after the protected write succeeded." }
                    }
                    Button("Choose legacy Flutter key JSON…") { importingLegacyKeys = true; importing = true }
                    if let legacyKeyJSON {
                        Text("Legacy identity: \(legacyNpub)").font(.caption.monospaced()).textSelection(.enabled)
                        Text("This migration imports the signing key. Use a complete .21bkp backup to restore profile and badge data.")
                        Button("Migrate reviewed Flutter key") {
                            confirmReplacement {
                                try requireNewPassword(); try await identity.migrateFlutterJSON(legacyKeyJSON, vaultPassword: newPassword)
                                self.legacyKeyJSON = nil; legacyNpub = ""; clearSecrets(); message = "Legacy Flutter key migrated."
                            }
                        }
                    }
                }
                Section("Passkeys") {
                    Text("Passkeys require a verified relying party, associated domain, and provisioning. This build keeps passkey sign-in unavailable.")
                    Button("Use a passkey") {}.disabled(true)
                }
                if !identity.publicKey.isEmpty {
                    Section {
                        Button("Remove signing identity from this device", role: .destructive) {
                            confirmReplacement { try await identity.reset(); clearSecrets(); message = "Signing identity removed from this device." }
                        }
                    }
                }
            }
            if !result.isEmpty && !exporting {
                Section("Export") {
                    Text(result).font(.caption.monospaced()).textSelection(.enabled).privacySensitive()
                    Button("Hide export") { result = "" }
                }
            }
        }
        .navigationTitle("Identity and recovery")
        .sheet(isPresented: $authBrowserPresented) {
            if let url = identity.authURL { IdentityAuthenticationView(url: url) }
        }
        .disabled(identity.busy && identity.authURL == nil)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .json, .plainText]) { response in
            run {
                let url = try response.get(), access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 12 * 1024 * 1024 else { throw IdentityError.resourceLimit }
                let bytes = try Data(contentsOf: url)
                guard bytes.count <= 12 * 1024 * 1024, let text = String(data: bytes, encoding: .utf8) else { throw IdentityError.invalidBackup }
                if importingLegacyKeys {
                    let key = try FlutterIdentityMigration.privateKey(json: bytes)
                    legacyNpub = try NIP19.encodePublicKey(IdentityKey.publicKey(key)); legacyKeyJSON = bytes
                    return
                }
                let preview = try await identity.previewBackup(text, password: backupPassword)
                importedPayload = text; reviewed = preview; message = nil
            }
        }
        .fileExporter(isPresented: $exporting, document: IdentityExportDocument(text: result), contentType: .data, defaultFilename: exportName) { response in
            switch response { case .success: message = "Encrypted export saved."; result = ""
            case .failure(let error): message = error.localizedDescription }
        }
        .confirmationDialog("Replace or remove the current signing identity?", isPresented: $replacing, titleVisibility: .visible) {
            Button("Continue", role: .destructive) { if let action = pendingAction { run(action) }; pendingAction = nil }
            Button("Cancel", role: .cancel) { pendingAction = nil }
        } message: { Text("Current identity proofs, portal access, and the local badge wallet are cleared before the change. Keep an encrypted backup if you need them.") }
        .alert("Reveal your private key?", isPresented: $rawExport) {
            Button("Reveal nsec", role: .destructive) { run { result = try await identity.exportNsec(vaultPassword: vaultPassword); vaultPassword = "" } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Anyone with this unencrypted key can use your identity. Prefer an encrypted NIP-49 export.") }
        .alert("Discard pending recovery?", isPresented: $discarding) {
            Button("Discard", role: .destructive) { run { try await identity.discardPendingRecovery(); reviewed = nil; importedPayload = ""; clearSecrets() } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This removes only the protected pending copy. Keep the original backup or Flutter identity until recovery is complete.") }
        .onChange(of: scenePhase) { _, phase in if phase == .background { clearSecrets(); Task { await identity.lock() } } }
        .onChange(of: identity.generation) { _, _ in clearSecrets() }
        .onChange(of: identity.authURL) { _, url in if url == nil { authBrowserPresented = false } }
        .onDisappear { clearSecrets(); importedPayload = ""; reviewed = nil; legacyKeyJSON = nil; legacyNpub = ""; pendingAction = nil }
    }
    private var status: String {
        switch identity.state { case .empty: "No identity"; case .locked: "Locked"; case .unlocked: "Unlocked"
        case .connecting: "Connecting"; case .recoveryRequired: "Recovery required" }
    }
    private func requireNewPassword() throws {
        guard newPassword.count >= 8, newPassword == confirmPassword else { throw IdentityError.invalidEncoding }
    }
    private func confirmReplacement(_ action: @escaping @MainActor () async throws -> Void) {
        if identity.publicKey.isEmpty { run(action) } else { pendingAction = action; replacing = true }
    }
    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        message = nil
        Task { do { try await action() } catch { message = error.localizedDescription } }
    }
    private func clearSecrets() {
        input = ""; bunkerURI = ""; vaultPassword = ""; newPassword = ""; confirmPassword = ""
        keyPassword = ""; backupPassword = ""; result = ""
        importedPayload = ""; reviewed = nil; legacyKeyJSON = nil; legacyNpub = ""
        upgradeReview = nil
    }
    private func restoreExplanation(_ decision: BackupDocument.RestoreDecision) -> String {
        switch decision {
        case .fresh: "Restore the profile, badge collection, and signing identity."
        case .sameIdentity: "Merge with the same identity. Existing profile fields and collected badges remain."
        case .addToIdentity: "This backup has no identity. Add its data to the current identity after confirming."
        case .replacesIdentity: "This backup belongs to another identity. Restoring replaces the current identity and clears its proofs and portal access."
        }
    }
}

private struct IdentityExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data, .plainText] }
    let text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, let text = String(data: data, encoding: .utf8) else { throw IdentityError.invalidBackup }
        self.text = text
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}
