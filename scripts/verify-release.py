#!/usr/bin/env python3
"""Validate public release metadata and matching APK/AAB signing certificates."""
import hashlib,json,pathlib,re,sys,xml.etree.ElementTree as ET
folder=pathlib.Path("release-report")
apk=pathlib.Path("app/build/outputs/apk/release/app-release.apk")
aab=pathlib.Path("app/build/outputs/bundle/release/app-release.aab")
expected_code=int(re.search(r"versionCode\s*=\s*(\d+)",pathlib.Path("app/build.gradle.kts").read_text())[1])
expected_cert=hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
badging=(folder/"apk-badging.txt").read_text()
m=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
assert m and m[1]=="com.mawja.player" and int(m[2])==expected_code,"Wrong package/version"
assert "application-debuggable" not in badging,"APK is debuggable"
apk_info=(folder/"apk-signature.txt").read_text()
apk_certs=re.findall(r"(?:Signer #\d+|V[23](?:\.\d+)? Signer):? certificate SHA-256 digest: ([0-9a-fA-F]+)",apk_info)
assert apk_certs,"Unrecognized apksigner certificate output"
assert {value.lower() for value in apk_certs}=={expected_cert},"APK signer mismatch"
bundle_info=(folder/"bundle-certificate.txt").read_text()
bundle_cert=re.search(r"SHA256:\s*([0-9A-Fa-f:]+)",bundle_info)
assert bundle_cert and bundle_cert[1].replace(":","").lower()==expected_cert,"AAB signer mismatch"
assert "jar verified" in (folder/"bundle-signature.txt").read_text(),"AAB signature failed"
manifest=ET.parse(folder/"bundle-manifest.xml").getroot()
android="{http://schemas.android.com/apk/res/android}"
assert manifest.get("package")==m[1] and int(manifest.get(android+"versionCode"))==expected_code,"Bundle package/version mismatch"
application=manifest.find("application")
assert application is not None and application.get(android+"debuggable","false")=="false","Bundle is debuggable"
def info(p):
    return {"file":p.name,"bytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest()}
result={"applicationId":m[1],"versionCode":int(m[2]),"versionName":m[3],
        "debuggable":False,"certificateSha256":expected_cert,
        "apk":info(apk),"aab":info(aab),"apkAndBundleCertificateMatch":True}
(folder/"release-verification.json").write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps(result,indent=2))
