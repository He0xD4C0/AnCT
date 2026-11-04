#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tarfile
import urllib.request
import zipfile

TERMUX_APP_PREFIX = "data/data/com.termux/files/usr"
ANCT_APP_PREFIX = "data/data/dev.anct//files/usr"
REPO = "https://packages.termux.dev/apt/termux-main"
INDEX_URL = f"{REPO}/dists/stable/main/binary-aarch64/Packages.gz"
BOOTSTRAP_URL = (
    "https://github.com/termux/termux-packages/releases/download/"
    "bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip"
)
BOOTSTRAP_SHA256 = "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae"
ROOT_PACKAGES = ("python", "proot")


def download(url: str, destination: Path, expected_sha256: str | None = None) -> bytes:
    if destination.is_file():
        data = destination.read_bytes()
        if expected_sha256 is None or sha256(data) == expected_sha256:
            return data
    request = urllib.request.Request(url, headers={"User-Agent": "AnCT-runtime-builder/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    if expected_sha256 is not None and sha256(data) != expected_sha256:
        raise RuntimeError(f"Checksum mismatch for {url}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return data


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_index(payload: bytes) -> dict[str, dict[str, str]]:
    import gzip

    text = gzip.decompress(payload).decode("utf-8")
    packages: dict[str, dict[str, str]] = {}
    for stanza in text.split("\n\n"):
        fields: dict[str, str] = {}
        current = None
        for line in stanza.splitlines():
            if line.startswith((" ", "\t")) and current:
                fields[current] += " " + line.strip()
            elif ":" in line:
                current, value = line.split(":", 1)
                fields[current] = value.strip()
        name = fields.get("Package")
        if not name:
            continue
        current_entry = packages.get(name)
        if current_entry is None or version_key(fields.get("Version", "")) > version_key(
            current_entry.get("Version", "")
        ):
            packages[name] = fields
    return packages


def version_key(value: str):
    return tuple(int(part) if part.isdigit() else part for part in re.split(r"([0-9]+)", value))


def resolve_packages(index: dict[str, dict[str, str]]) -> list[dict[str, str]]:
    resolved: dict[str, dict[str, str]] = {}
    pending = list(ROOT_PACKAGES)
    while pending:
        name = pending.pop()
        if name in resolved:
            continue
        entry = index.get(name)
        if entry is None:
            raise RuntimeError(f"Package is absent from the Termux index: {name}")
        resolved[name] = entry
        dependencies = (entry.get("Pre-Depends", "") + "," + entry.get("Depends", "")).strip(",")
        for expression in dependencies.split(","):
            alternatives = [part.strip() for part in expression.split("|") if part.strip()]
            if not alternatives:
                continue
            selected = None
            for alternative in alternatives:
                candidate = re.split(r"\s+|\(", alternative, maxsplit=1)[0]
                if candidate in index:
                    selected = candidate
                    break
            if selected:
                pending.append(selected)
    return [resolved[name] for name in sorted(resolved)]


def ar_members(deb: bytes):
    if not deb.startswith(b"!<arch>\n"):
        raise RuntimeError("Invalid Debian archive")
    offset = 8
    while offset + 60 <= len(deb):
        header = deb[offset : offset + 60]
        name = header[:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        start = offset + 60
        yield name, deb[start : start + size]
        offset = start + size + (size % 2)


def data_archive(deb: bytes) -> bytes:
    for name, payload in ar_members(deb):
        if name.startswith("data.tar"):
            return payload
    raise RuntimeError("Debian package has no data archive")


def safe_relative(name: str) -> PurePosixPath:
    parts = [part for part in PurePosixPath(name).parts if part not in ("", ".", "/")]
    if any(part == ".." for part in parts):
        raise RuntimeError(f"Unsafe package path: {name}")
    return PurePosixPath(*parts)


def install_data_archive(payload: bytes, prefix: Path) -> None:
    symlinks: list[tuple[Path, str]] = []
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as archive:
        for member in archive:
            relative = safe_relative(member.name)
            if not relative.parts or len(relative.parts) < len(PurePosixPath(TERMUX_APP_PREFIX).parts):
                continue
            prefix_parts = PurePosixPath(TERMUX_APP_PREFIX).parts
            if relative.parts[: len(prefix_parts)] != prefix_parts:
                continue
            package_path = PurePosixPath(*relative.parts[len(prefix_parts) :])
            if not package_path.parts:
                continue
            target = prefix.joinpath(*package_path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            if target.exists() or target.is_symlink():
                if target.is_dir() and not target.is_symlink():
                    continue
                target.unlink()
            if member.issym():
                symlinks.append((target, member.linkname))
                continue
            if member.islnk():
                linked = PurePosixPath(member.linkname)
                link_parts = linked.parts
                if link_parts[: len(prefix_parts)] == prefix_parts:
                    linked_target = prefix.joinpath(*link_parts[len(prefix_parts) :])
                else:
                    linked_target = prefix / member.linkname
                target.hardlink_to(linked_target)
                continue
            if not member.isfile():
                continue
            source = archive.extractfile(member)
            if source is None:
                continue
            target.write_bytes(source.read())
            os.chmod(target, member.mode & 0o777)
    for target, linkname in symlinks:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.unlink(missing_ok=True)
        rewritten = linkname.replace("com.termux", "dev.anct//")
        os.symlink(rewritten, target)


def install_stock_bootstrap(payload: bytes, prefix: Path) -> list[tuple[str, str]]:
    symlinks: list[tuple[str, str]] = []
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        for entry in archive.infolist():
            if entry.filename == "SYMLINKS.txt":
                for line in archive.read(entry).decode("utf-8").splitlines():
                    target, link = line.split("\u2190", 1)
                    symlinks.append((target, link.removeprefix("./")))
                continue
            relative = safe_relative(entry.filename)
            target = prefix.joinpath(*relative.parts)
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(entry))
    return symlinks


def rewrite_prefix(root: Path) -> None:
    old = b"com.termux"
    new = b"dev.anct//"
    if len(old) != len(new):
        raise RuntimeError("Prefix replacement must preserve byte length")
    for path in root.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        data = path.read_bytes()
        if old in data:
            path.write_bytes(data.replace(old, new))


def write_bootstrap(prefix: Path, symlinks: list[tuple[str, str]], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".tmp")
    fixed_time = (1980, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(prefix.rglob("*")):
            if not path.is_file():
                continue
            name = path.relative_to(prefix).as_posix()
            info = zipfile.ZipInfo(name, fixed_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100644 & 0xFFFF) << 16
            archive.writestr(info, path.read_bytes())
        link_lines = []
        for target, link in symlinks:
            if (prefix / link).exists() and not (prefix / link).is_symlink():
                continue
            link_lines.append(
                target.replace("com.termux", "dev.anct//") + "\u2190" + link
            )
        info = zipfile.ZipInfo("SYMLINKS.txt", fixed_time)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o100644 & 0xFFFF) << 16
        archive.writestr(info, "\n".join(sorted(set(link_lines))) + "\n")
    temporary.replace(output)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--lock", required=True, type=Path)
    args = parser.parse_args()

    cache = args.work / "downloads"
    prefix = args.work / "prefix"
    if prefix.exists():
        import shutil

        shutil.rmtree(prefix)
    prefix.mkdir(parents=True)

    stock = download(BOOTSTRAP_URL, cache / "bootstrap-aarch64.zip", BOOTSTRAP_SHA256)
    if args.lock.is_file():
        locked = json.loads(args.lock.read_text())
        selected = [
            {
                "Package": item["name"],
                "Version": item["version"],
                "Filename": item["filename"],
                "SHA256": item["sha256"],
            }
            for item in locked["packages"]
        ]
    else:
        package_index = parse_index(download(INDEX_URL, cache / "Packages.gz"))
        selected = resolve_packages(package_index)
    symlinks = install_stock_bootstrap(stock, prefix)

    for package in selected:
        name = package["Package"]
        filename = package["Filename"]
        expected = package["SHA256"]
        deb = download(f"{REPO}/{filename}", cache / filename, expected)
        print(f"Installing {name} {package['Version']}", flush=True)
        install_data_archive(data_archive(deb), prefix)

    rewrite_prefix(prefix)
    write_bootstrap(prefix, symlinks, args.output)

    lock = {
        "bootstrap": {"url": BOOTSTRAP_URL, "sha256": BOOTSTRAP_SHA256},
        "packages": [
            {
                "name": item["Package"],
                "version": item["Version"],
                "filename": item["Filename"],
                "sha256": item["SHA256"],
            }
            for item in selected
        ],
    }
    args.lock.parent.mkdir(parents=True, exist_ok=True)
    args.lock.write_text(json.dumps(lock, indent=2) + "\n")
    print(f"{args.output} {sha256(args.output.read_bytes())}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise

