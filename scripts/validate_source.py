"""Bounded preflight. Does not certify all credentials, licenses, or Git history."""
import json
import re
import sys
from source_files import ROOT, source_files

def validate():
    files = list(source_files())
    errors = []
    for path in files:
        rel = path.relative_to(ROOT).as_posix()
        if path.suffix == '.png':
            continue
        text = path.read_text(encoding='utf-8-sig')
        # Report only path and category, never a matching secret value.
        if re.search(r'-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}|\bsk-[A-Za-z0-9_-]{32,}', text):
            errors.append(f'{rel}: possible credential')
        if re.search(r'[CE]:[/\\](?:Users[/\\]|workspace[/\\]Ekina)', text):
            errors.append(f'{rel}: machine-specific path')
        if path.suffix in {'.java', '.vue', '.ts'} and re.search(r'live2d|petdesktop|petrp', text, re.I):
            errors.append(f'{rel}: retired pet implementation')
        if path.suffix == '.md':
            for target in re.findall(r'\[[^\]\n]+\]\(([^)\s]+)\)', text):
                if '://' in target or target.startswith(('#', 'mailto:')):
                    continue
                resolved = (path.parent / target.split('#')[0]).resolve()
                if not resolved.is_relative_to(ROOT) or not resolved.exists():
                    errors.append(f'{rel}: missing local link {target}')
    for path in sorted((ROOT / 'plugins').glob('*/plugin.json')):
        obj = json.loads(path.read_text(encoding='utf-8'))
        if obj.get('schemaVersion') != 1 or obj.get('id') != path.parent.name:
            errors.append(f'{path.relative_to(ROOT)}: invalid manifest identity')
        for rel in [obj['implementation'], *[s + '/SKILL.md' for s in obj['skills']]]:
            dest = (ROOT / rel).resolve()
            if not dest.is_relative_to(ROOT) or not dest.is_file():
                errors.append(f'{path.relative_to(ROOT)}: missing component {rel}')
    for path in sorted((ROOT / 'skills').glob('*/SKILL.md')):
        text = path.read_text(encoding='utf-8')
        if not text.startswith('---\n') or f'name: {path.parent.name}\n' not in text or not re.search(r'^description: .+', text, re.M):
            errors.append(f'{path.relative_to(ROOT)}: invalid skill metadata')
    return files, errors

if __name__ == '__main__':
    files, errors = validate()
    if errors:
        print('\n'.join(errors))
        sys.exit(1)
    print(f'PASS: {len(files)} distributable files; links, manifests, skills and bounded source checks.')
