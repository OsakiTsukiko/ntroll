#!/usr/bin/env python3
"""Verify every ABI, sign APKs with one persistent key, and write release checksums."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

# ELF class and e_machine, independent of potentially incorrect APK filenames.
ARCHITECTURES = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True, stderr=subprocess.STDOUT)


def verify_native(apk, expected):
    with zipfile.ZipFile(apk) as archive:
        actual = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
        require(actual == expected, f"{apk.name}: expected native ABIs {sorted(expected)}, found {sorted(actual)}")
        for abi in expected:
            data = archive.read(f"lib/{abi}/libntroll.so")[:20]
            elf_class, machine = ARCHITECTURES[abi]
            require(len(data) == 20 and data[:4] == b'\x7fELF' and data[4] == elf_class
                    and data[5] == 1 and int.from_bytes(data[18:20], 'little') == machine,
                    f"{apk.name}: wrong native ELF architecture for {abi}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=Path('app/build/outputs/apk/release'))
    parser.add_argument('--output', type=Path, default=Path('dist'))
    parser.add_argument('--tag', default='', help='Optional release tag; must match versionName, with optional v prefix')
    parser.add_argument('--unsigned', action='store_true', help='Local verification only; output clearly marked unsigned APKs')
    args = parser.parse_args()
    metadata = json.loads((args.input / 'output-metadata.json').read_text())
    require(metadata['variantName'] == 'release', 'Expected release build metadata')
    require(metadata['applicationId'] == 'com.ntroll.app', 'Unexpected application ID')
    elements = metadata['elements']
    require(len(elements) == 5, 'Expected four ABI APKs and one universal APK; build with -PsplitApks and all four androidAbis')
    version = elements[0]['versionName']
    code = elements[0]['versionCode']
    require(re.fullmatch(r'[0-9][A-Za-z0-9.+_-]*', version), 'Version contains unsupported filename characters')
    if args.tag:
        require(args.tag.removeprefix('v') == version, f'Release tag must match app versionName ({version})')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools' / '34.0.0'
    seen = set()
    apks = []
    for element in elements:
        require(element['versionName'] == version and element['versionCode'] == code, 'APK versions disagree')
        filters = element['filters']
        require(not filters or (len(filters) == 1 and filters[0]['filterType'] == 'ABI'), 'Unexpected APK filters')
        abi = filters[0]['value'] if filters else 'universal'
        require(abi in ARCHITECTURES or abi == 'universal', 'Unsupported ABI in build metadata')
        require(abi not in seen, f'Duplicate APK for {abi}')
        seen.add(abi)
        apk = args.input / element['outputFile']
        require(apk.resolve().parent == args.input.resolve(), 'APK must be in the build output directory')
        verify_native(apk, set(ARCHITECTURES) if abi == 'universal' else {abi})
        badging = run(tools / 'aapt', 'dump', 'badging', apk)
        require('application-debuggable' not in badging, f'{apk.name}: APK is debuggable')
        require(f"versionName='{version}'" in badging and f"versionCode='{code}'" in badging,
                f'{apk.name}: manifest version differs from metadata')
        run(tools / 'zipalign', '-c', '-p', '4', apk)
        apks.append((abi, apk))
    require(seen == set(ARCHITECTURES) | {'universal'}, 'Release is missing an architecture')
    if not args.unsigned:
        for key in ['ANDROID_KEYSTORE_PATH', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']:
            require(bool(os.environ.get(key)), f'Missing signing environment variable: {key}')
        require(Path(os.environ['ANDROID_KEYSTORE_PATH']).is_file(), 'Signing keystore is missing')
    args.output.mkdir(parents=True, exist_ok=True)
    require(not list(args.output.glob('*.apk')), 'Output directory already contains APKs; use an empty directory')
    checksums = []
    for abi, source in sorted(apks):
        suffix = '-unsigned' if args.unsigned else ''
        destination = args.output / f'ntroll-{version}-{abi}{suffix}.apk'
        if args.unsigned:
            shutil.copyfile(source, destination)
        else:
            run(tools / 'apksigner', 'sign', '--ks', os.environ['ANDROID_KEYSTORE_PATH'],
                '--ks-key-alias', os.environ['ANDROID_KEY_ALIAS'], '--ks-pass', 'env:ANDROID_KEYSTORE_PASSWORD',
                '--key-pass', 'env:ANDROID_KEY_PASSWORD', '--v4-signing-enabled', 'false', '--out', destination, source)
            run(tools / 'apksigner', 'verify', '--verbose', destination)
            run(tools / 'zipalign', '-c', '-p', '4', destination)
        checksums.append(hashlib.sha256(destination.read_bytes()).hexdigest() + '  ' + destination.name + '\n')
        print(f'Verified {destination.name}')
    (args.output / 'SHA256SUMS').write_text(''.join(checksums))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f'Release packaging failed: {error}', file=sys.stderr)
        sys.exit(1)
