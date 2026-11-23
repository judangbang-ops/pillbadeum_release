#!/usr/bin/env python3
"""Emit GitHub Actions outputs for the SDKs selected by the app's Robolectric tests."""

import hashlib
import re
import tomllib
from pathlib import Path


def main() -> None:
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

    key = f"{versions['robolectric']}-sdk-" + "_".join(map(str, sorted(sdk_numbers)))
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
    print(f"required_jars={0 if complex_selection else len(sdk_numbers)}")


if __name__ == "__main__":
    main()
