# Applied only with -PinstrumentationBuildType=preview. Production release and ordinary preview
# APKs do not get this entry point or these rules. referenced-api.pro is generated from the test
# APK's complete bytecode; keep the additional Hilt interface accessed through EntryPoints.get().
-keep,allowoptimization,includedescriptorclasses interface space.einundzwanzig.meetup.testing.DeviceParityEntryPoint {
    public *;
}

# The device test reflectively calls the same internal factory used by CameraXSession. Keep only
# this boundary; scanner construction, component discovery and decoding still run production code.
-keep,allowoptimization class space.einundzwanzig.meetup.core.camera.QrCameraBackendKt {
    public static com.google.mlkit.vision.barcode.BarcodeScanner newQrBarcodeScanner();
}
