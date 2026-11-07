import os
import sys
import zipfile
import struct
import hashlib
import re
from cryptography import x509
from cryptography.hazmat.primitives.serialization import pkcs12, Encoding

SCHEME_V2_BLOCK_ID = 0x7109871a
SCHEME_V3_BLOCK_ID = 0xf05368c0

def parse_version_tuple(v):
    if not v:
        return (0,)
    parts = [int(p) for p in re.findall(r"\d+", str(v))]
    return tuple(parts) if parts else (0,)

def get_keystore_cert_sha256(keystore_path, password=b"android"):
    with open(keystore_path, "rb") as f:
        data = f.read()
    pkey, cert, add_certs = pkcs12.load_key_and_certificates(data, password)
    if not cert:
        raise ValueError(f"No certificate found in {keystore_path}")
    cert_der = cert.public_bytes(Encoding.DER)
    sha256 = hashlib.sha256(cert_der).hexdigest()
    return sha256, cert.subject.rfc4514_string()

def extract_certs_from_signer_block(pair_val):
    certs = []
    signers_seq_len = struct.unpack("<I", pair_val[:4])[0]
    pos = 4
    while pos < 4 + signers_seq_len:
        signer_len = struct.unpack("<I", pair_val[pos:pos+4])[0]
        pos += 4
        signer_bytes = pair_val[pos:pos+signer_len]
        pos += signer_len
        
        signed_data_len = struct.unpack("<I", signer_bytes[:4])[0]
        signed_data = signer_bytes[4:4+signed_data_len]
        
        # digests sequence
        sd_pos = 0
        digests_len = struct.unpack("<I", signed_data[sd_pos:sd_pos+4])[0]
        sd_pos += 4 + digests_len
        
        # certificates sequence
        certs_len = struct.unpack("<I", signed_data[sd_pos:sd_pos+4])[0]
        sd_pos += 4
        c_end = sd_pos + certs_len
        while sd_pos < c_end:
            c_len = struct.unpack("<I", signed_data[sd_pos:sd_pos+4])[0]
            c_bytes = signed_data[sd_pos+4:sd_pos+4+c_len]
            sd_pos += 4 + c_len
            cert = x509.load_der_x509_certificate(c_bytes)
            certs.append({
                "sha256": hashlib.sha256(c_bytes).hexdigest(),
                "subject": cert.subject.rfc4514_string(),
                "issuer": cert.issuer.rfc4514_string()
            })
    return certs

def get_apk_signature_certs(apk_path):
    with open(apk_path, "rb") as f:
        data = f.read()
    eocd_idx = data.rfind(b"\x50\x4b\x05\x06")
    if eocd_idx == -1:
        raise ValueError("EOCD not found")
    cd_size, cd_offset = struct.unpack("<II", data[eocd_idx+12:eocd_idx+20])
    magic = data[cd_offset-16:cd_offset]
    if magic != b"APK Sig Block 42":
        return {}, "No APK Signing Block 42"
    
    block_size = struct.unpack("<Q", data[cd_offset-24:cd_offset-16])[0]
    block_start = cd_offset - 8 - block_size
    offset = block_start + 8
    end = cd_offset - 24
    
    certs_by_scheme = {"v2": [], "v3": []}
    while offset < end:
        pair_len = struct.unpack("<Q", data[offset:offset+8])[0]
        pair_id = struct.unpack("<I", data[offset+8:offset+12])[0]
        pair_val = data[offset+12:offset+8+pair_len]
        if pair_id == SCHEME_V2_BLOCK_ID: # Scheme v2
            certs_by_scheme["v2"].extend(extract_certs_from_signer_block(pair_val))
        elif pair_id == SCHEME_V3_BLOCK_ID: # Scheme v3
            certs_by_scheme["v3"].extend(extract_certs_from_signer_block(pair_val))
        offset += 8 + pair_len
    return certs_by_scheme, None

def parse_axml_manifest_info(apk_path):
    with zipfile.ZipFile(apk_path, "r") as z:
        axml = z.read("AndroidManifest.xml")
    
    # Header: type (2 bytes), header size (2 bytes), chunk size (4 bytes)
    res_type, header_size, chunk_size = struct.unpack("<HHI", axml[:8])
    
    # String pool
    sp_type, sp_header_size, sp_chunk_size = struct.unpack("<HHI", axml[8:16])
    string_count, style_count, flags, strings_start, styles_start = struct.unpack("<IIIII", axml[16:36])
    is_utf8 = bool(flags & 0x0100)
    offsets = [struct.unpack("<I", axml[36+i*4:40+i*4])[0] for i in range(string_count)]
    strings = []
    for off in offsets:
        pos = 8 + strings_start + off
        if is_utf8:
            b1 = axml[pos]
            pos += 1
            if b1 & 0x80:
                b2 = axml[pos]
                pos += 1
                char_len = ((b1 & 0x7f) << 8) | b2
            else:
                char_len = b1
            b1 = axml[pos]
            pos += 1
            if b1 & 0x80:
                b2 = axml[pos]
                pos += 1
                byte_len = ((b1 & 0x7f) << 8) | b2
            else:
                byte_len = b1
            strings.append(axml[pos:pos+byte_len].decode("utf-8", errors="replace"))
        else:
            u16_len = struct.unpack("<H", axml[pos:pos+2])[0]
            pos += 2
            strings.append(axml[pos:pos+u16_len*2].decode("utf-16le", errors="replace"))

    # Map of resource IDs
    pos = 8 + sp_chunk_size
    c_type, c_hsize, c_size = struct.unpack("<HHI", axml[pos:pos+8])
    if c_type == 0x0180: # RES_XML_RESOURCE_MAP_TYPE
        pos += c_size

    info = {"package": None, "versionCode": None, "versionName": None}
    
    # Traverse XML chunks
    while pos < len(axml):
        c_type, c_hsize, c_size = struct.unpack("<HHI", axml[pos:pos+8])
        if c_type == 0x0102: # RES_XML_START_ELEMENT_TYPE
            elem_body = axml[pos+16:pos+36]
            ns_idx, name_idx, attr_start, attr_size, attr_count, id_idx, class_idx, style_idx = struct.unpack("<IIHHHHHH", elem_body)
            tag_name = strings[name_idx] if name_idx < len(strings) else "unknown"
            
            if tag_name == "manifest":
                attr_base = pos + 16 + attr_start
                for a in range(attr_count):
                    a_off = attr_base + a * attr_size
                    a_ns, a_name, a_val, a_val_size, a_val_res0, a_val_type, a_val_data = struct.unpack(
                        "<IIIHBBI", axml[a_off:a_off+20]
                    )
                    attr_name = strings[a_name] if a_name < len(strings) else ""
                    val_str = strings[a_val] if (a_val != 0xFFFFFFFF and a_val < len(strings)) else None
                    
                    if attr_name == "package":
                        info["package"] = val_str or (strings[a_val_data] if a_val_data < len(strings) else None)
                    elif attr_name == "versionCode":
                        info["versionCode"] = a_val_data
                    elif attr_name == "versionName":
                        info["versionName"] = val_str or (strings[a_val_data] if a_val_data < len(strings) else None)
                break
        pos += c_size
    return info

def inspect_full(apk_path, keystore_path, min_version_code=46, min_version_name="1.0.42"):
    print(f"==================================================")
    print(f"Inspecting APK: {apk_path}")
    if not os.path.exists(apk_path):
        print(f"❌ File does not exist: {apk_path}")
        return False
    
    size = os.path.getsize(apk_path)
    file_sha256 = hashlib.sha256(open(apk_path, "rb").read()).hexdigest()
    print(f"File Size: {size:,} bytes")
    print(f"File SHA256: {file_sha256}")

    # Keystore expected hash
    expected_cert_sha256, ks_subject = get_keystore_cert_sha256(keystore_path)
    print(f"Expected debug.keystore SHA256: {expected_cert_sha256}")
    print(f"debug.keystore Subject: {ks_subject}")

    # Signature scheme check (Scheme v2 and v3)
    certs_by_scheme, err = get_apk_signature_certs(apk_path)
    if err:
        print(f"❌ Signature error: {err}")
        return False
    
    v2_certs = certs_by_scheme.get("v2", [])
    v3_certs = certs_by_scheme.get("v3", [])
    print(f"Scheme v2 Certs: {len(v2_certs)}, Scheme v3 Certs: {len(v3_certs)}")
    for i, c in enumerate(v2_certs):
        print(f"  [v2] Cert [{i}]: SHA256={c['sha256']}, Subject={c['subject']}")
    for i, c in enumerate(v3_certs):
        print(f"  [v3] Cert [{i}]: SHA256={c['sha256']}, Subject={c['subject']}")
    
    all_sig_certs = v2_certs + v3_certs
    sig_match = any(c['sha256'] == expected_cert_sha256 for c in all_sig_certs)
    if sig_match:
        print(f"✅ Scheme v2/v3 Signature MATCHES debug.keystore ({expected_cert_sha256})!")
    else:
        print(f"❌ Scheme v2/v3 Signature DOES NOT MATCH debug.keystore!")

    # AXML manifest check
    axml_info = parse_axml_manifest_info(apk_path)
    print(f"Binary AXML Manifest: {axml_info}")
    
    version_code = axml_info.get("versionCode")
    version_name = axml_info.get("versionName")
    package = axml_info.get("package")
    
    print(f"Package: {package}")
    print(f"versionCode: {version_code}")
    print(f"versionName: {version_name}")
    
    # Internal ZIP check
    with zipfile.ZipFile(apk_path, "r") as z:
        names = z.namelist()
        dex_count = len([n for n in names if n.endswith(".dex")])
        has_manifest = "AndroidManifest.xml" in names
        has_arsc = "resources.arsc" in names
    print(f"ZIP Contents: Manifest={has_manifest}, ARSC={has_arsc}, DEX files={dex_count}, Total files={len(names)}")

    ver_tuple = parse_version_tuple(version_name)
    min_ver_tuple = parse_version_tuple(min_version_name)

    all_ok = (
        sig_match and
        package == "com.aleksandr.pockethud" and
        version_code is not None and version_code >= min_version_code and
        ver_tuple >= min_ver_tuple and
        has_manifest and has_arsc and dex_count > 0
    )
    print(f"Overall Result: {'✅ ALL INVARIANTS PASSED' if all_ok else '❌ FAILED'}")
    return all_ok

def find_default_apks(base_dir):
    candidates = []
    # 1. Local APKs in base_dir
    local_apk_42 = os.path.join(base_dir, "PocketHUD_v1.0.42.apk")
    if os.path.exists(local_apk_42):
        candidates.append(local_apk_42)
    local_apk_41 = os.path.join(base_dir, "PocketHUD_v1.0.41.apk")
    if os.path.exists(local_apk_41) and not os.path.exists(local_apk_42):
        candidates.append(local_apk_41)
    
    # 2. Local Gradle build output
    build_apk = os.path.join(base_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if os.path.exists(build_apk):
        candidates.append(build_apk)
    
    # 3. Google Drive locations (PC1 / PC2)
    drive_roots = [r"G:\Мой диск", r"G:\Il mio Drive"]
    for drive in drive_roots:
        if os.path.exists(drive):
            p1 = os.path.join(drive, "PocketHUD_latest.apk")
            p2 = os.path.join(drive, "PocketHUD_v1.0.42.apk")
            p3 = os.path.join(drive, "PocketHUD_v1.0.41.apk")
            if os.path.exists(p1) and p1 not in candidates:
                candidates.append(p1)
            if os.path.exists(p2) and p2 not in candidates:
                candidates.append(p2)
            if os.path.exists(p3) and p3 not in candidates and not os.path.exists(p2):
                candidates.append(p3)
    return candidates

if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.abspath(__file__))
    keystore = os.path.join(base_dir, "app", "debug.keystore")
    
    if len(sys.argv) > 1:
        apks = sys.argv[1:]
    else:
        apks = find_default_apks(base_dir)

    print(f"Found {len(apks)} APK candidate(s) for verification.")
    if not apks:
        print("❌ No APK files found to verify.")
        sys.exit(1)

    results = []
    for apk in apks:
        res = inspect_full(apk, keystore)
        results.append(res)
    
    print("\n" + "="*50)
    if all(results):
        print("🎉 ALL APKS COMPLY WITH PROTOCOL 9 MOBILE RELEASE INVARIANT!")
        sys.exit(0)
    else:
        print("❌ ONE OR MORE APKS FAILED VERIFICATION")
        sys.exit(1)
