import SwiftUI

#if os(iOS) && !targetEnvironment(simulator)
import AVFoundation
import UIKit
import Vision
import VisionKit
#endif

/// Returns an unmodified QR payload. Parsing and trust decisions belong to the caller.
@MainActor
struct NativeQRScanner: View {
    private let onScan: (String) -> Void
    @State private var didDeliver = false

    #if os(iOS) && !targetEnvironment(simulator)
    @Environment(\.scenePhase) private var scenePhase
    @State private var availability: ScannerAvailability = .checking
    #endif

    init(onScan: @escaping (String) -> Void) {
        self.onScan = onScan
    }

    var body: some View {
        VStack(spacing: 16) {
            #if os(iOS) && !targetEnvironment(simulator)
            switch availability {
            case .checking:
                ProgressView("scanner.checking")
            case .ready:
                QRScannerCamera(
                    isActive: scenePhase == .active && !didDeliver,
                    onScan: { payload in
                        guard !didDeliver else { return }
                        didDeliver = true
                        onScan(payload)
                    },
                    onUnavailable: { reason in availability = .unavailable(reason) }
                )
                .frame(minHeight: 280)
                .accessibilityLabel("scanner.camera.accessibility")
                Text("scanner.instructions")
                    .font(.callout)
            case .unavailable(let reason):
                unavailableView(reason.message)
            }
            #elseif targetEnvironment(simulator)
            unavailableView("scanner.unavailable.simulator")
            #else
            unavailableView("scanner.unavailable.macos")
            #endif

            Text("scanner.manual_entry_hint")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        #if os(iOS) && !targetEnvironment(simulator)
        .task(id: scenePhase) {
            guard scenePhase == .active, !didDeliver else { return }
            await checkAvailability()
        }
        #endif
    }

    private func unavailableView(_ message: LocalizedStringKey) -> some View {
        ContentUnavailableView(
            "scanner.unavailable.title",
            systemImage: "qrcode.viewfinder",
            description: Text(message)
        )
    }

    #if os(iOS) && !targetEnvironment(simulator)
    private func checkAvailability() async {
        guard DataScannerViewController.isSupported else {
            availability = .unavailable(.unsupported)
            return
        }
        guard let usage = Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription")
            as? String, !usage.isEmpty else {
            availability = .unavailable(.configuration)
            return
        }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined:
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard !Task.isCancelled else { return }
            guard allowed else {
                availability = .unavailable(.permissionDenied)
                return
            }
        case .denied:
            availability = .unavailable(.permissionDenied)
            return
        case .restricted:
            availability = .unavailable(.restricted)
            return
        case .authorized:
            break
        @unknown default:
            availability = .unavailable(.unavailable)
            return
        }
        guard !Task.isCancelled else { return }
        // Recheck both immediately before constructing the camera controller.
        availability = DataScannerViewController.isSupported && DataScannerViewController.isAvailable
            ? .ready : .unavailable(.unavailable)
    }
    #endif
}

#if os(iOS) && !targetEnvironment(simulator)
private enum ScannerAvailability {
    case checking, ready, unavailable(ScannerUnavailableReason)
}

private enum ScannerUnavailableReason {
    case unsupported, permissionDenied, restricted, configuration, unavailable, payloadTooLong

    var message: LocalizedStringKey {
        switch self {
        case .unsupported: "scanner.unavailable.unsupported"
        case .permissionDenied: "scanner.unavailable.permission_denied"
        case .restricted: "scanner.unavailable.restricted"
        case .configuration: "scanner.unavailable.configuration"
        case .unavailable: "scanner.unavailable.temporary"
        case .payloadTooLong: "scanner.unavailable.payload_too_long"
        }
    }
}

@MainActor
private struct QRScannerCamera: UIViewControllerRepresentable {
    let isActive: Bool
    let onScan: (String) -> Void
    let onUnavailable: (ScannerUnavailableReason) -> Void

    func makeUIViewController(context: Context) -> QRScannerHostController {
        QRScannerHostController(onScan: onScan, onUnavailable: onUnavailable)
    }

    func updateUIViewController(_ controller: QRScannerHostController, context: Context) {
        controller.onScan = onScan
        controller.onUnavailable = onUnavailable
        controller.setActive(isActive)
    }

    static func dismantleUIViewController(_ controller: QRScannerHostController, coordinator: ()) {
        controller.tearDown()
    }
}

/// Camera creation, scanning, delegates, and delivery all stay on the main actor.
@MainActor
private final class QRScannerHostController: UIViewController, DataScannerViewControllerDelegate {
    // A QR byte payload is at most 2,953 bytes; this also bounds decoded UTF-8 output.
    private static let maximumPayloadBytes = 4_096
    var onScan: (String) -> Void
    var onUnavailable: (ScannerUnavailableReason) -> Void
    private var scanner: DataScannerViewController?
    private var isActive = true
    private var isVisible = false
    private var didFinish = false

    init(
        onScan: @escaping (String) -> Void,
        onUnavailable: @escaping (ScannerUnavailableReason) -> Void
    ) {
        self.onScan = onScan
        self.onUnavailable = onUnavailable
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        isVisible = true
        resumeScanning()
    }

    override func viewWillDisappear(_ animated: Bool) {
        isVisible = false
        scanner?.stopScanning()
        super.viewWillDisappear(animated)
    }

    func setActive(_ active: Bool) {
        isActive = active
        guard active else {
            scanner?.stopScanning()
            return
        }
        // Avoid changing SwiftUI state from a representable update if start fails.
        Task { @MainActor [weak self] in self?.resumeScanning() }
    }

    func tearDown() {
        isActive = false
        isVisible = false
        didFinish = true
        scanner?.stopScanning()
        scanner?.delegate = nil
    }

    private func resumeScanning() {
        guard isActive, isVisible, !didFinish else { return }
        guard DataScannerViewController.isSupported, DataScannerViewController.isAvailable else {
            fail(.unavailable)
            return
        }
        if scanner == nil {
            let scanner = DataScannerViewController(
                recognizedDataTypes: [.barcode(symbologies: [.qr])],
                qualityLevel: .balanced,
                recognizesMultipleItems: false,
                isHighFrameRateTrackingEnabled: false,
                isPinchToZoomEnabled: true,
                isGuidanceEnabled: true,
                isHighlightingEnabled: true
            )
            scanner.delegate = self
            addChild(scanner)
            view.addSubview(scanner.view)
            scanner.view.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                scanner.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                scanner.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                scanner.view.topAnchor.constraint(equalTo: view.topAnchor),
                scanner.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
            ])
            scanner.didMove(toParent: self)
            self.scanner = scanner
        }
        guard let scanner, !scanner.isScanning else { return }
        do {
            try scanner.startScanning()
        } catch {
            fail(AVCaptureDevice.authorizationStatus(for: .video) == .denied
                 ? .permissionDenied : .unavailable)
        }
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController,
        didAdd addedItems: [RecognizedItem],
        allItems: [RecognizedItem]
    ) {
        receive(addedItems)
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController,
        didUpdate updatedItems: [RecognizedItem],
        allItems: [RecognizedItem]
    ) {
        receive(updatedItems)
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
        receive([item])
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController,
        becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable
    ) {
        fail(AVCaptureDevice.authorizationStatus(for: .video) == .denied
             ? .permissionDenied : .unavailable)
    }

    private func receive(_ items: [RecognizedItem]) {
        guard isActive, isVisible, !didFinish else { return }
        for item in items {
            guard case .barcode(let barcode) = item,
                  barcode.observation.symbology == .qr,
                  let payload = barcode.payloadStringValue, !payload.isEmpty else { continue }
            guard payload.utf8.count <= Self.maximumPayloadBytes else {
                fail(.payloadTooLong)
                return
            }
            didFinish = true
            scanner?.stopScanning()
            onScan(payload)
            return
        }
    }

    private func fail(_ reason: ScannerUnavailableReason) {
        guard !didFinish else { return }
        didFinish = true
        scanner?.stopScanning()
        onUnavailable(reason)
    }
}
#endif
