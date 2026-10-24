#!/usr/bin/env python3
"""Compile the adapter-owned semantic test against the packaged production Engine."""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from adapter import ADAPTER, REPO, audit, require


def main():
    audit()
    jar = REPO / 'template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar'
    require(jar.is_file(), 'Build the real Engine JAR first')
    with tempfile.TemporaryDirectory(prefix='contract-loader-', dir=REPO / '.standalone-build') as directory:
        root = Path(directory)
        # Only known regular library files are read from our locally built JAR.
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (root / Path(name).name).write_bytes(archive.read(name))
        classpath = os.pathsep.join(str(path) for path in sorted(root.glob('*.jar')))
        source = ADAPTER / 'tests/java/contract/ContractParity.java'
        subprocess.run(['javac', '-source', '8', '-target', '8', '-cp', classpath, '-d', str(root), str(source)], check=True)
        subprocess.run(['java', '-cp', str(root) + os.pathsep + classpath, 'ContractParity',
                        str(REPO / 'template-source/code'), str(REPO / '.standalone-build/template-source/code')], check=True)


if __name__ == '__main__':
    main()
