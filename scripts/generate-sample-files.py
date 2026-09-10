#!/usr/bin/env python3
"""Generate billing + GL CSV files with SHA-256 sidecars, plus vendor/customer reference CSVs."""

from __future__ import annotations

import argparse
import hashlib
from datetime import date, timedelta
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_checksum(path: Path) -> None:
    sidecar = path.with_name(path.name + ".sha256")
    sidecar.write_text(f"{sha256_file(path)}  {path.name}\n", encoding="utf-8")


def gl_name_for(stem: str) -> str:
    suffix = stem.removeprefix("billing-") if stem.startswith("billing-") else stem
    return f"gl-{suffix}.csv"


def unique_stem(home: Path, stem: str) -> str:
    """Pick billing-demo, then billing-demo-2, billing-demo-3, ... so reruns never overwrite."""
    n = 1
    candidate = stem
    while True:
        billing = home / f"{candidate}.csv"
        gl = home / gl_name_for(candidate)
        sidecar = home / f"{candidate}.csv.sha256"
        gl_sidecar = home / f"{gl.name}.sha256"
        if not any(path.exists() for path in (billing, gl, sidecar, gl_sidecar)):
            return candidate
        n += 1
        candidate = f"{stem}-{n}"


def generate(rows: int, home: Path, reference: Path, stem: str) -> None:
    home.mkdir(parents=True, exist_ok=True)
    reference.mkdir(parents=True, exist_ok=True)
    today = date.today()
    stem = unique_stem(home, stem)

    vendors = reference / "vendors.csv"
    with vendors.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("vendor_id,vendor_name,status\n")
        for gs in range(1, 101):
            status = "INACTIVE" if gs % 20 == 0 else "ACTIVE"
            handle.write(f"V{gs:04d},Vendor {gs},{status}\n")

    customers = reference / "customers.csv"
    with customers.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("customer_id,customer_name,status\n")
        for gs in range(1, 1001):
            status = "INACTIVE" if gs % 50 == 0 else "ACTIVE"
            handle.write(f"C{gs:06d},Customer {gs},{status}\n")

    billing = home / f"{stem}.csv"
    gl = home / gl_name_for(stem)

    with billing.open("w", encoding="utf-8", newline="\n") as billing_out, gl.open(
        "w", encoding="utf-8", newline="\n"
    ) as gl_out:
        billing_out.write("id,txn_id,vendor_id,customer_id,amount,currency,txn_date,due_date\n")
        gl_out.write("txn_id,account,amount,gl_date\n")
        for gs in range(1, rows + 1):
            txn_id = f"TXN{gs:010d}"
            vendor_id = f"V{((gs % 100) + 1):04d}"
            customer_id = f"C{((gs % 1000) + 1):06d}"
            amount = 0.0 if gs % 5000 == 0 else round(10 + (gs % 4900) * 0.31, 2)
            txn_date = today - timedelta(days=gs % 60)
            due_date = txn_date + timedelta(days=15)
            billing_out.write(
                f"{gs},{txn_id},{vendor_id},{customer_id},{amount:.2f},USD,{txn_date.isoformat()},{due_date.isoformat()}\n"
            )
            gl_amount = amount + 417 if gs % 10000 == 1 else amount
            gl_out.write(f"{txn_id},4000,{gl_amount:.2f},{txn_date.isoformat()}\n")

    write_checksum(billing)
    write_checksum(gl)
    print(f"Wrote {billing} and {gl} ({rows} rows)")
    print(f"Wrote {vendors} and {customers}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=1_200_000)
    parser.add_argument("--home", default="sftp/home")
    parser.add_argument("--reference", default="sftp/destination/reference")
    parser.add_argument("--stem", default="billing-demo")
    args = parser.parse_args()
    generate(args.rows, Path(args.home), Path(args.reference), args.stem)


if __name__ == "__main__":
    main()
