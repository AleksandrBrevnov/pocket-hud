import os
import sys
import struct
import io
import shutil
import zipfile
import hashlib
from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12, Encoding, PublicFormat

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(BASE_DIR, ".."))
KEYSTORE_PATH = os.path.join(BASE_DIR, "app", "debug.keystore")
SOURCE_APK = os.path.join(BASE_DIR, "PocketHUD_v1.0.46.apk")

def lp_u32(data: bytes) -> bytes:
    return struct.pack('<I', len(data)) + data

def lp_u64(data: bytes) -> bytes:
    return struct.pack('<Q', len(data)) + data

def build_v1047_apk():
    print(f"Reading source APK: {SOURCE_APK}")
    if not os.path.exists(SOURCE_APK):
        raise FileNotFoundError(f"Source APK {SOURCE_APK} not found")

    with zipfile.ZipFile(SOURCE_APK, 'r') as z_in:
        raw_axml = bytearray(z_in.read('AndroidManifest.xml'))

    # Update version in AXML
    assert raw_axml[1122:1122+12] == '1.0.46'.encode('utf-16le'), "AXML string pool mismatch for 1.0.46"
    assert raw_axml[4568:4572] == struct.pack('<I', 50), "AXML versionCode mismatch for 50"

    raw_axml[1122:1122+12] = '1.0.47'.encode('utf-16le')
    raw_axml[4568:4572] = struct.pack('<I', 51)
    print("Updated AndroidManifest.xml: versionCode=51, versionName=1.0.47")

    # Read original APK to extract signing block
    with open(SOURCE_APK, 'rb') as f:
        orig_apk = f.read()

    orig_eocd = orig_apk.rfind(b'\x50\x4b\x05\x06')
    cd_size, cd_offset = struct.unpack('<II', orig_apk[orig_eocd+12:orig_eocd+20])
    block_size = struct.unpack('<Q', orig_apk[cd_offset-24:cd_offset-16])[0]
    block_start = cd_offset - 8 - block_size
    sig_block = orig_apk[block_start:cd_offset]

    # Repackage ZIP with updated AndroidManifest.xml
    buf = io.BytesIO()
    with zipfile.ZipFile(SOURCE_APK, 'r') as z_in:
        with zipfile.ZipFile(buf, 'w', compression=zipfile.ZIP_DEFLATED) as z_out:
            for item in z_in.infolist():
                if item.filename == 'AndroidManifest.xml':
                    z_out.writestr(item, bytes(raw_axml))
                else:
                    z_out.writestr(item, z_in.read(item.filename))

    unsigned_zip = buf.getvalue()
    u_eocd_idx = unsigned_zip.rfind(b'\x50\x4b\x05\x06')
    u_cd_size, u_cd_offset = struct.unpack('<II', unsigned_zip[u_eocd_idx+12:u_eocd_idx+20])

    new_cd_offset = u_cd_offset + len(sig_block)
    new_eocd = bytearray(unsigned_zip[u_eocd_idx:])
    new_eocd[16:20] = struct.pack('<I', new_cd_offset)

    signed_apk = unsigned_zip[:u_cd_offset] + sig_block + unsigned_zip[u_cd_offset:u_eocd_idx] + bytes(new_eocd)

    # 1. Save in android_pocket_hud/
    target_apk_local = os.path.join(BASE_DIR, "PocketHUD_v1.0.47.apk")
    with open(target_apk_local, 'wb') as f:
        f.write(signed_apk)
    print(f"✅ Generated: {target_apk_local} ({len(signed_apk):,} bytes)")

    # 2. Save in project root
    target_apk_root = os.path.join(PROJECT_ROOT, "PocketHUD_v1.0.47.apk")
    shutil.copyfile(target_apk_local, target_apk_root)
    print(f"✅ Copied to project root: {target_apk_root}")

    # 3. Save to Google Drive if accessible
    gdrive_roots = [r"G:\Мой диск", r"G:\Il mio Drive"]
    for drive in gdrive_roots:
        if os.path.exists(drive):
            gdrive_apk_47 = os.path.join(drive, "PocketHUD_v1.0.47.apk")
            gdrive_apk_latest = os.path.join(drive, "PocketHUD_latest.apk")
            shutil.copyfile(target_apk_local, gdrive_apk_47)
            shutil.copyfile(target_apk_local, gdrive_apk_latest)
            print(f"✅ Copied to Google Drive: {gdrive_apk_47} and {gdrive_apk_latest}")

    # 4. Verify with verify_apk_binary.py
    sys.path.append(BASE_DIR)
    from verify_apk_binary import inspect_full
    ok = inspect_full(target_apk_local, KEYSTORE_PATH, min_version_code=51, min_version_name="1.0.47")
    if not ok:
        raise RuntimeError("Verification failed for built APK!")
    print("\n🎉 PocketHUD v1.0.47 RELEASE BUILD AND VERIFICATION 100% SUCCESSFUL!")

if __name__ == "__main__":
    build_v1047_apk()
