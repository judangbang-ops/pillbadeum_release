#!/usr/bin/env python3
"""Identify and prepare the Android SDK JARs used by the Robolectric CI tests."""

import argparse
import hashlib
import re
import subprocess
import tempfile
import tomllib
from pathlib import Path


ARTIFACT = "android-all-instrumented"
MAVEN_PATH = f"org/robolectric/{ARTIFACT}"
MAVEN_REPO = "https://repo1.maven.org/maven2"
# Coordinates from Robolectric's DefaultSdkProvider and SHA-512 from Maven Central.
SDK_ARTIFACTS = {
    "4.17": {
        32: (
            "12.1-robolectric-8229987-i7",
            "747d403cd1e80a898a9ffb917920ce094216312b3dabaa312410e100647102b414"
            "1d2fbd1efca32251308e713fd69dca72c0971f93f7d6f1247d19a13cbac4ef",
        ),
        34: (
            "14-robolectric-10818077-i7",
            "7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724ef"
            "f39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455",
        ),
        35: (
            "15-robolectric-13954326-i7",
            "5fc64ffccf4788154162686b3966ddd63abea8326542c465e2a7f0129fc92770d0e"
            "1e8ddcfabd7f1500e491ec02e3b4104b9e51b61fccd9cf08006c9dc02987c",
        ),
    },
}


def matches_checksum(path: Path, expected: str) -> bool:
    if not path.is_file():
        return False
    with path.open("rb") as jar:
        return hashlib.file_digest(jar, "sha512").hexdigest() == expected


def prepare_sdk(sdk: int, artifact_version: str, checksum: str) -> None:
    jar_name = f"{ARTIFACT}-{artifact_version}.jar"
    directory = Path.home() / ".m2" / "repository" / MAVEN_PATH / artifact_version
    jar = directory / jar_name
    source = "local cache"
    if not matches_checksum(jar, checksum):
        jar.unlink(missing_ok=True)
        directory.mkdir(parents=True, exist_ok=True)
        url = f"{MAVEN_REPO}/{MAVEN_PATH}/{artifact_version}/{jar_name}"
        print(f"SDK {sdk}: downloading {url} with retries enabled", flush=True)
        with tempfile.TemporaryDirectory(dir=directory) as staging:
            download = Path(staging) / jar_name
            transfer = subprocess.run(
                [
                    "curl", "--disable", "--fail", "--no-progress-meter",
                    "--retry", "5", "--retry-all-errors", "--retry-max-time", "600",
                    "--connect-timeout", "20", "--max-time", "120",
                    "--output", str(download),
                    "--write-out", "%{http_code} %{num_retries} %header{retry-after}", url,
                ],
                stdout=subprocess.PIPE,
                text=True,
            )
            status, _, retry_summary = transfer.stdout.strip().partition(" ")
            retries, _, retry_after = retry_summary.partition(" ")
            print(f"SDK {sdk}: HTTP {status}, retries: {retries}, Retry-After: {retry_after or '-'}", flush=True)
            # curl can report an HTTP error even when its process exit status is zero.
            if transfer.returncode != 0 or status != "200":
                raise SystemExit(f"SDK {sdk}: download failed, HTTP {status} (curl exit {transfer.returncode})")
            if not matches_checksum(download, checksum):
                raise SystemExit(f"SDK {sdk}: SHA-512 mismatch for {jar_name}")
            download.replace(jar)
        source = "Maven Central download"
    jar.with_suffix(".jar.sha512").write_text(checksum, encoding="ascii")
    print(f"SDK {sdk}: SHA-512 verified {jar_name} from {source}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true", help="Verify and prefetch known SDK JARs")
    args = parser.parse_args()

    with Path("gradle/libs.versions.toml").open("rb") as catalog:
        versions = tomllib.load(catalog)["versions"]

    app_sources = Path("androidApp/src")
    test_files = sorted(
        path for source_set in ("test", "testDebug")
        for path in (app_sources / source_set).rglob("*") if path.is_file()
    )
    sdk_numbers = set()
    complex_selection = False
    for path in test_files:
        if path.name == "robolectric.properties":
            complex_selection = True
        if path.suffix not in {".kt", ".java"}:
            continue
        source = path.read_text(encoding="utf-8")
        source = re.sub(r'""".*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', " ", source, flags=re.DOTALL)
        configs = re.findall(r"@(?:org\.robolectric\.annotation\.)?Config\s*\(([^)]*)\)", source)
        sdk_values = []
        for config in configs:
            sdk_values.extend(re.findall(r"\bsdk\s*=\s*(\[[^\]]*\]|\{[^}]*\}|[^,]+)", config))
            complex_selection |= bool(re.search(r"\b(?:minSdk|maxSdk)\s*=", config))
        for sdk_value in sdk_values:
            numbers = sdk_value.strip("[]{}").strip().rstrip(",")
            if re.fullmatch(r"[0-9]+(?:\s*,\s*[0-9]+)*", numbers):
                sdk_numbers.update(int(number) for number in numbers.split(","))
            else:
                complex_selection = True
        if "RobolectricTestRunner" in source and not sdk_values:
            sdk_numbers.add(int(versions["android-targetSdk"]))

    robolectric_version = versions["robolectric"]
    artifacts = SDK_ARTIFACTS.get(robolectric_version, {})
    known_selection = bool(sdk_numbers) and not complex_selection and sdk_numbers.issubset(artifacts)
    if args.prepare:
        if not known_selection:
            # Keep Robolectric's regular resolver for SDK selections we cannot prefetch safely.
            print(f"Robolectric {robolectric_version}, SDKs {sorted(sdk_numbers)}: using its regular resolver")
            return
        for sdk in sorted(sdk_numbers):
            prepare_sdk(sdk, *artifacts[sdk])
        return

    key = f"{robolectric_version}-sdk-" + "_".join(map(str, sorted(sdk_numbers)))
    if complex_selection or not sdk_numbers:
        # Ranges, constants and package settings can select SDKs without literal @Config values.
        # Include the test inputs and require a passing run before publishing this cache.
        digest = hashlib.sha256()
        digest.update(f"{versions['android-minSdk']},{versions['android-targetSdk']}\n".encode())
        for path in test_files:
            if path.suffix in {".kt", ".java"} or path.name == "robolectric.properties":
                digest.update(f"{path.relative_to(app_sources).as_posix()}\n".encode())
                digest.update(hashlib.sha256(path.read_bytes()).digest())
        key += f"-{digest.hexdigest()}"

    print(f"key={key}")
    print(f"required_jars={len(sdk_numbers) if known_selection else 0}")
    if known_selection and 35 in sdk_numbers and len(sdk_numbers) > 1:
        # Reuse the existing main cache for API 35 when the full SDK set is new.
        print(f"api35_key={robolectric_version}-sdk-35")


if __name__ == "__main__":
    main()
