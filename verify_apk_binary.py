import os
import sys
import zipfile
import struct
import hashlib
from cryptography import x509
from cryptography.hazmat.primitives.serialization import pkcs12, Encoding

def get_keystore_cert_sha256(keystore_path, password=b"android"):
    with open(keystore_path, "rb") as f:
        data = f.read()
    pkey, cert, add_certs = pkcs12.load_key_and_certificates(data, password)
    if not cert:
        raise ValueError(f"No certificate found in {keystore_path}")
    cert_der = cert.public_bytes(Encoding.DER)
    sha256 = hashlib.sha256(cert_der).hexdigest()
    return sha256, cert.subject.rfc4514_string()

def get_apk_v2_signature_certs(apk_path):
    with open(apk_path, "rb") as f:
        data = f.read()
    eocd_idx = data.rfind(b"\x50\x4b\x05\x06")
    if eocd_idx == -1:
        raise ValueError("EOCD not found")
    cd_size, cd_offset = struct.unpack("<II", data[eocd_idx+12:eocd_idx+20])
    magic = data[cd_offset-16:cd_offset]
    if magic != b"APK Sig Block 42":
        return [], "No APK Signing Block 42"
    
    block_size = struct.unpack("<Q", data[cd_offset-24:cd_offset-16])[0]
    block_start = cd_offset - 8 - block_size
    offset = block_start + 8
    end = cd_offset - 24
    
    v2_certs = []
    while offset < end:
        pair_len = struct.unpack("<Q", data[offset:offset+8])[0]
        pair_id = struct.unpack("<I", data[offset+8:offset+12])[0]
        pair_val = data[offset+12:offset+8+pair_len]
        if pair_id == 0x7109871a: # Scheme v2
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
                    v2_certs.append({
                        "sha256": hashlib.sha256(c_bytes).hexdigest(),
                        "subject": cert.subject.rfc4514_string(),
                        "issuer": cert.issuer.rfc4514_string()
                    })
        offset += 8 + pair_len
    return v2_certs, None

def parse_axml_manifest_info(apk_path):
    with zipfile.ZipFile(apk_path, "r") as z:
        axml = z.read("AndroidManifest.xml")
    
    # Header: type (2 bytes), header size (2 bytes), chunk size (4 bytes)
    res_type, header_size, chunk_size = struct.unpack("<HHI", axml[:8])
    
    # String pool
    sp_type, sp_header_size, sp_chunk_size = struct.unpack("<HHI", axml[8:16])
    string_count, style_count, flags, strings_start, styles_start = struct.unpack("<IIIII", axml[16:36])
    offsets = [struct.unpack("<I", axml[36+i*4:40+i*4])[0] for i in range(string_count)]
    strings = []
    for off in offsets:
        pos = 8 + strings_start + off
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
            # chunk header (16 bytes): type(2), hsize(2), size(4), line(4), comment(4)
            # element payload (20 bytes): ns(4), name(4), attr_start(2), attr_size(2), attr_count(2), id_idx(2), class_idx(2), style_idx(2)
            elem_hdr = axml[pos:pos+16]
            elem_body = axml[pos+16:pos+36]
            ns_idx, name_idx, attr_start, attr_size, attr_count, id_idx, class_idx, style_idx = struct.unpack("<IIHHHHHH", elem_body)
            tag_name = strings[name_idx] if name_idx < len(strings) else "unknown"
            
            if tag_name == "manifest":
                # Attributes start at pos + 16 + attr_start
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

def inspect_full(apk_path, keystore_path):
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

    # V2 signature check
    v2_certs, err = get_apk_v2_signature_certs(apk_path)
    if err:
        print(f"❌ Signature error: {err}")
        return False
    print(f"Scheme v2 Certs count: {len(v2_certs)}")
    for i, c in enumerate(v2_certs):
        print(f"  Cert [{i}]: SHA256={c['sha256']}, Subject={c['subject']}")
    
    sig_match = any(c['sha256'] == expected_cert_sha256 for c in v2_certs)
    if sig_match:
        print(f"✅ Scheme v2 Signature MATCHES debug.keystore ({expected_cert_sha256})!")
    else:
        print(f"❌ Scheme v2 Signature DOES NOT MATCH debug.keystore!")

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

    all_ok = (
        sig_match and
        package == "com.aleksandr.pockethud" and
        version_code is not None and version_code >= 39 and
        version_name is not None and version_name >= "1.0.35" and
        has_manifest and has_arsc and dex_count > 0
    )
    print(f"Overall Result: {'✅ ALL INVARIANTS PASSED' if all_ok else '❌ FAILED'}")
    return all_ok

if __name__ == "__main__":
    keystore = r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\app\debug.keystore"
    apks = [
        r"c:\Users\Aleksandr\Documents\Private\android_pocket_hud\PocketHUD_v1.0.35.apk",
        r"G:\Мой диск\PocketHUD_latest.apk",
        r"G:\Мой диск\PocketHUD_v1.0.35.apk"
    ]
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
