"""生成自签证书，给 server.py 的 WSS 用。

    pip install cryptography
    python gen_cert.py                 # 写到 $YEHOO_CHAT_DIR/cert/
    python gen_cert.py --force         # 覆盖已有文件
    python gen_cert.py --cn chat.local --days 825

自签证书只适合小圈子自用。要让公网浏览器/手机信任，得换正式证书
（Let's Encrypt 之类），并去掉客户端 Tls.java 里的信任覆盖。
"""

from __future__ import annotations

import argparse
import datetime as dt
import ipaddress
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cn", default="yehochat.local", help="证书通用名（域名或 IP）")
    ap.add_argument("--days", type=int, default=3650)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    chat_dir = os.environ.get("YEHOO_CHAT_DIR") or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "data")
    cert_dir = os.path.join(chat_dir, "cert")
    cert_path = os.path.join(cert_dir, "cert.pem")
    key_path = os.path.join(cert_dir, "key.pem")

    if os.path.exists(cert_path) and not args.force:
        print(f"已存在: {cert_path}（加 --force 覆盖）")
        return 1

    os.makedirs(cert_dir, exist_ok=True)

    key = ec.generate_private_key(ec.SECP256R1())
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, args.cn),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "YehoChat self-signed"),
    ])

    alt: list[x509.GeneralName] = [x509.DNSName(args.cn)]
    try:
        alt.append(x509.IPAddress(ipaddress.ip_address(args.cn)))
    except ValueError:
        pass  # 不是 IP，只留 DNS 条目

    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=5))
        .not_valid_after(now + dt.timedelta(days=args.days))
        .add_extension(x509.SubjectAlternativeName(alt), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    with open(key_path, "wb") as f:
        f.write(key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ))
    os.chmod(key_path, 0o600)

    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))

    print(f"证书: {cert_path}")
    print(f"私钥: {key_path}（权限 600）")
    print(f"CN={args.cn}  有效期 {args.days} 天")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ImportError:
        print("需要 cryptography：pip install cryptography", file=sys.stderr)
        raise SystemExit(2)
