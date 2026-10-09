"""Build a source preview ZIP without runtime data, dependencies, or old Git history."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile
from source_files import ROOT
from validate_source import validate

def main():
    files, errors = validate()
    if errors:
        raise SystemExit('\n'.join(errors))
    output = ROOT.parent / 'Ekina-source-preview.zip'
    if output.exists():
        raise SystemExit(f'Refusing to overwrite existing archive: {output}')
    manifest = {}
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            name = path.relative_to(ROOT).as_posix()
            data = path.read_bytes()
            manifest[name] = hashlib.sha256(data).hexdigest()
            archive.writestr('Ekina/' + name, data)
        archive.writestr('Ekina/SOURCE_MANIFEST.json', json.dumps(manifest, indent=2) + '\n')
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise SystemExit('Archive verification failed')
    print(f'{output}\n{len(manifest)} source files\nSHA256 {hashlib.sha256(output.read_bytes()).hexdigest()}')

if __name__ == '__main__':
    main()
