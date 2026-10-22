import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location("verify_native_android", Path(__file__).parents[1] / "verify_native_android.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
A = verify.ANDROID


class ReleaseValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.manifest = Path(self.directory.name) / "AndroidManifest.xml"
        self.root = ET.Element("manifest", {"package": verify.APP})
        self.app = ET.SubElement(self.root, "application", {A + "allowBackup": "false"})
        for tag, name, exported in [
            ("activity", verify.APP + ".MainActivity", "true"),
            ("activity", verify.APP + ".WidgetRouterActivity", "false"),
            ("receiver", verify.APP + ".MeetupWidgetProvider", "false"),
            ("service", verify.APP + ".data.community.plebrap.PlebrapPlaybackService", "true"),
        ]:
            node = ET.SubElement(self.app, tag, {A + "name": name, A + "exported": exported})
            if tag == "service":
                node.set(A + "foregroundServiceType", "mediaPlayback")
                ET.SubElement(ET.SubElement(node, "intent-filter"), "action",
                              {A + "name": "androidx.media3.session.MediaSessionService"})

    def check_manifest(self):
        ET.ElementTree(self.root).write(self.manifest)
        verify.verify_manifest(self.manifest, verify.APP)

    def test_required_app_and_media_components_are_allowed(self):
        self.check_manifest()

    def test_framework_services_require_the_exact_permission(self):
        name = "androidx.glance.appwidget.GlanceRemoteViewsService"
        node = ET.SubElement(self.app, "service", {
            A + "name": name, A + "exported": "true", A + "permission": "android.permission.BIND_REMOTEVIEWS",
        })
        self.check_manifest()
        node.attrib.pop(A + "permission")
        with self.assertRaisesRegex(ValueError, "Wrong permission"):
            self.check_manifest()

    def test_new_exported_components_fail_closed(self):
        ET.SubElement(self.app, "activity", {A + "name": ".UnexpectedActivity", A + "exported": "true"})
        with self.assertRaisesRegex(ValueError, "Unexpected exported"):
            self.check_manifest()

    def test_widget_router_cannot_be_exported(self):
        self.app[1].set(A + "exported", "true")
        with self.assertRaisesRegex(ValueError, "Unexpected exported"):
            self.check_manifest()

    def test_implicit_exports_are_rejected(self):
        node = ET.SubElement(self.app, "receiver", {A + "name": ".ImplicitReceiver"})
        ET.SubElement(node, "intent-filter")
        with self.assertRaisesRegex(ValueError, "lacks explicit exported"):
            self.check_manifest()

    def test_debuggable_and_backup_enabled_manifests_are_rejected(self):
        for attribute in ("debuggable", "allowBackup"):
            with self.subTest(attribute=attribute):
                self.app.set(A + attribute, "true")
                with self.assertRaises(ValueError):
                    self.check_manifest()
                self.app.set(A + attribute, "false")

    def test_media_intent_filter_cannot_expand(self):
        media = self.app[-1]
        ET.SubElement(media.find("intent-filter"), "action", {A + "name": "unexpected.action"})
        with self.assertRaisesRegex(ValueError, "Unexpected PlebRap"):
            self.check_manifest()

    def test_manifest_must_match_the_apk_package(self):
        self.root.set("package", verify.APP + ".preview")
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.check_manifest()

    @staticmethod
    def certificate_output(pin="a" * 64):
        return (f"Signer #1 certificate SHA-256 digest: {pin}\n"
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Verified using v3 scheme (APK Signature Scheme v3): true\n")

    def test_certificate_pin_accepts_colons_and_case(self):
        self.assertEqual("a" * 64, verify.verify_certificate(self.certificate_output(), ":".join(["AA"] * 32)))

    def test_sdk37_certificate_reports_allow_the_same_signer_across_schemes(self):
        output = self.certificate_output().replace("Signer #1", "V2 Signer:")
        output += "Number of signers: 1\nV3 Signer: certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.assertEqual("a" * 64, verify.verify_certificate(output, "a" * 64))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify.verify_certificate(output.replace("Number of signers: 1", "Number of signers: 2"), "a" * 64)

    def test_certificate_pin_is_mandatory_and_must_match(self):
        for pin in (None, "", "invalid", "b" * 64):
            with self.subTest(pin=pin):
                with self.assertRaises(ValueError):
                    verify.verify_certificate(self.certificate_output(), pin)

    def test_additional_signers_and_missing_v3_are_rejected(self):
        extra = "Signer #2 certificate SHA-256 digest: " + "b" * 64 + "\n"
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify.verify_certificate(self.certificate_output() + extra, "a" * 64)
        with self.assertRaisesRegex(ValueError, "scheme v3"):
            verify.verify_certificate(self.certificate_output().replace("v3): true", "v3): false"), "a" * 64)

    def test_apk_package_version_and_minimum_upgrade_code(self):
        valid = f"package: name='{verify.APP}' versionCode='20000' versionName='2.0.0'\nsdkVersion:'26'\n"
        self.assertEqual((verify.APP, "2.0.0"), verify.verify_badging(valid, "release"))
        for invalid in (
            valid.replace("20000", "200"), valid.replace("20000", "4031"),
            valid.replace("2.0.0", "2.0.0-preview"), valid.replace("sdkVersion:'26'", "sdkVersion:'23'"),
            valid + "application-debuggable\n", valid.replace(verify.APP, verify.APP + ".preview"),
        ):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    verify.verify_badging(invalid, "release")

    def test_preview_has_a_distinct_package(self):
        valid = f"package: name='{verify.APP}.preview' versionCode='20000' versionName='2.0.0-preview'\nminSdkVersion:'26'\n"
        self.assertEqual((verify.APP + ".preview", "2.0.0-preview"), verify.verify_badging(valid, "preview"))


if __name__ == "__main__":
    unittest.main()
