#!/usr/bin/env python3
"""Exercise release-verification rejection paths without private keys or Android tooling."""
import hashlib,pathlib,subprocess,sys,tempfile,unittest
SCRIPT=pathlib.Path(__file__).with_name("verify-release.py").resolve()
class ReleaseVerificationTest(unittest.TestCase):
    def run_check(self,change=None):
        with tempfile.TemporaryDirectory() as folder:
            p=pathlib.Path(folder);(p/"release-report").mkdir();(p/"app").mkdir()
            (p/"app/build.gradle.kts").write_text("versionCode = 47")
            cert=b"public-test-certificate";(p/"cert.der").write_bytes(cert);digest=hashlib.sha256(cert).hexdigest()
            for path in ["app/build/outputs/apk/release/app-release.apk","app/build/outputs/bundle/release/app-release.aab"]:
                f=p/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(b"artifact")
            r=p/"release-report"
            (r/"apk-badging.txt").write_text("package: name='com.mawja.player' versionCode='47' versionName='1.0.0'")
            (r/"apk-signature.txt").write_text("Signer #1 certificate SHA-256 digest: "+digest)
            (r/"bundle-certificate.txt").write_text("SHA256: "+":".join(digest[i:i+2] for i in range(0,len(digest),2)))
            (r/"bundle-signature.txt").write_text("jar verified.")
            (r/"bundle-manifest.xml").write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mawja.player" android:versionCode="47"><application android:debuggable="false"/></manifest>')
            if change:change(r)
            result=subprocess.run([sys.executable,str(SCRIPT),str(p/"cert.der")],cwd=p,capture_output=True,text=True)
            return result.returncode
    def test_accepts_matching_release(self):self.assertEqual(0,self.run_check())
    def test_accepts_modern_v2_signer(self):
        def modern(r):
            p=r/"apk-signature.txt"
            p.write_text(p.read_text().replace("Signer #1 certificate","V2 Signer: certificate"))
        self.assertEqual(0,self.run_check(modern))
    def test_rejects_additional_mismatched_signer(self):
        self.assertNotEqual(0,self.run_check(lambda r:(r/"apk-signature.txt").write_text((r/"apk-signature.txt").read_text()+"\nV3 Signer: certificate SHA-256 digest: "+"0"*64)))
    def test_rejects_wrong_apk_signer(self):self.assertNotEqual(0,self.run_check(lambda r:(r/"apk-signature.txt").write_text("Signer #1 certificate SHA-256 digest: "+"0"*64)))
    def test_rejects_wrong_bundle_signer(self):self.assertNotEqual(0,self.run_check(lambda r:(r/"bundle-certificate.txt").write_text("SHA256: "+"0"*64)))
    def test_rejects_debug_apk(self):self.assertNotEqual(0,self.run_check(lambda r:(r/"apk-badging.txt").write_text((r/"apk-badging.txt").read_text()+"\napplication-debuggable")))
    def test_rejects_debug_bundle(self):self.assertNotEqual(0,self.run_check(lambda r:(r/"bundle-manifest.xml").write_text((r/"bundle-manifest.xml").read_text().replace('debuggable="false"','debuggable="true"'))))
    def test_rejects_wrong_bundle_version(self):self.assertNotEqual(0,self.run_check(lambda r:(r/"bundle-manifest.xml").write_text((r/"bundle-manifest.xml").read_text().replace('versionCode="47"','versionCode="46"'))))
if __name__=="__main__":unittest.main()
