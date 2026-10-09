"""Acquire public PDFs and render original panels. No model, prose editing or redrawing."""
import hashlib
import http.client
import ipaddress
import json
import pathlib
import re
import shutil
import socket
import ssl
import sys
import urllib.parse
import urllib.request

import fitz
from PIL import Image, ImageDraw

MAX_BYTES = 25_000_000


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def public_target(url):
    p = urllib.parse.urlsplit(url)
    if p.scheme != 'https' or not p.hostname or p.username or p.password or p.port not in (None, 443):
        raise ValueError('Only public HTTPS PDFs without credentials are accepted')
    addresses = {x[4][0] for x in socket.getaddrinfo(p.hostname, 443, type=socket.SOCK_STREAM)}
    if addresses and any(not ipaddress.ip_address(x).is_global for x in addresses):
        # Some local research proxies synthesize RFC1918 DNS answers. Resolve the
        # public hostname independently, then still PIN a globally routable IP;
        # never connect to the synthetic/private address or weaken TLS checks.
        try:
            ipaddress.ip_address(p.hostname)
            raise ValueError('Non-public literal destination rejected')
        except ValueError as exc:
            if 'literal destination' in str(exc):
                raise
        if any(ipaddress.ip_address(x).is_loopback or ipaddress.ip_address(x).is_link_local for x in addresses):
            raise ValueError('Non-public destination rejected')
        query = 'https://dns.google/resolve?' + urllib.parse.urlencode({'name': p.hostname, 'type': 'A'})
        with urllib.request.urlopen(query, timeout=15) as response:
            answer = json.load(response)
        if answer.get('Status') != 0 or not any(q.get('name', '').rstrip('.').lower() == p.hostname.lower() for q in answer.get('Question', [])):
            raise ValueError('Public DNS verification failed')
        addresses = {a['data'] for a in answer.get('Answer', []) if a.get('type') == 1}
    if not addresses or any(not ipaddress.ip_address(x).is_global for x in addresses):
        raise ValueError('Non-public destination rejected')
    return p, sorted(addresses)[0]


def download(url, target):
    # Pin the validated IP for each hop while preserving TLS hostname verification.
    for _ in range(5):
        p, address = public_target(url)
        conn = http.client.HTTPSConnection(p.hostname, timeout=25)
        raw = socket.create_connection((address, 443), timeout=25)
        conn.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=p.hostname)
        try:
            conn.request('GET', urllib.parse.urlunsplit(('', '', p.path or '/', p.query, '')),
                         headers={'User-Agent': 'Ekina-ResearchReader/1.0', 'Accept': 'application/pdf', 'Accept-Encoding': 'identity'})
            res = conn.getresponse()
            if res.status in (301, 302, 303, 307, 308):
                url = urllib.parse.urljoin(url, res.getheader('Location', ''))
                continue
            if res.status != 200:
                raise ValueError(f'PDF HTTP {res.status}; no login or paywall bypass attempted')
            content = bytearray()
            while chunk := res.read(65536):
                content.extend(chunk)
                if len(content) > MAX_BYTES:
                    raise ValueError('PDF exceeds 25 MB')
            if b'%PDF-' not in content[:1024]:
                raise ValueError('Response is not a PDF')
            target.write_bytes(content)
            return url
        finally:
            conn.close()
    raise ValueError('Too many PDF redirects')


def load(path):
    return json.loads(pathlib.Path(path).read_text(encoding='utf-8'))


def write(path, data):
    pathlib.Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def acquire(request):
    base, work = pathlib.Path(request['base']), pathlib.Path(request['work'])
    work.mkdir(parents=True, exist_ok=True)
    sources = {s['id']: s for s in load(base / 'sources.json')}
    proposals = load(request['plan'])
    papers, gaps = [], list(proposals.get('gaps', []))
    candidates = proposals.get('papers', [])
    if len(candidates) > 4:
        raise ValueError('At most four source papers per supplement')
    for i, candidate in enumerate(candidates, 1):
        sid = candidate['sourceId']
        if sid not in sources:
            raise ValueError('Paper must correspond to an existing source ID')
        paper_id = f'p{i}'
        pdf = work / f'{paper_id}.pdf'
        receipt = work / f'{paper_id}-download.json'
        try:
            url = candidate['pdfUrl']
            if pdf.exists() and receipt.exists() and load(receipt).get('requestedUrl') == url and load(receipt).get('sha256') == digest(pdf):
                info = load(receipt)
            else:
                final_url = download(url, pdf)
                info = {'requestedUrl': url, 'resolvedUrl': final_url, 'sha256': digest(pdf)}
                write(receipt, info)
            pages = []
            with fitz.open(pdf) as doc:
                if doc.needs_pass or len(doc) > 300:
                    raise ValueError('Encrypted or excessively long PDF')
                for n in range(min(len(doc), 40)):
                    page = doc[n]
                    name = f'{paper_id}-page-{n+1:03d}.png'
                    scale = min(900 / page.rect.width, 1400 / page.rect.height)
                    page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False).save(work / name)
                    pages.append({'page': n + 1, 'preview': name, 'width': page.rect.width, 'height': page.rect.height,
                                  'text': page.get_text()[:5000]})
                paper = {'id': paper_id, 'sourceId': sid, 'expectedSource': sources[sid], 'pdfFile': pdf.name,
                         'pdfSha256': info['sha256'], 'pdfUrl': info['resolvedUrl'], 'pageCount': len(doc),
                         'pages': pages, 'license': candidate.get('license', '未核实'),
                         'licenseUrl': candidate.get('licenseUrl', ''), 'sheets': []}
                for start in range(0, len(pages), 6):
                    sheet = Image.new('RGB', (1200, 1680), 'white')
                    draw = ImageDraw.Draw(sheet)
                    for j, entry in enumerate(pages[start:start + 6]):
                        with Image.open(work / entry['preview']) as original:
                            thumb = original.copy(); thumb.thumbnail((580, 525))
                        x, y = (j % 2) * 600 + 10, (j // 2) * 560 + 25
                        sheet.paste(thumb, (x, y)); draw.text((x, y - 20), f'{paper_id} PDF page {entry["page"]}', fill='black')
                    name = f'{paper_id}-sheet-{start//6+1}.png'; sheet.save(work / name); paper['sheets'].append(name)
                papers.append(paper)
                if len(doc) > 40:
                    gaps.append(f'{sid}: only the first 40 PDF pages were rendered for bounded inspection')
        except Exception as e:
            gaps.append(f'{sid}: {e}')
    catalog = {'papers': papers, 'gaps': gaps, 'baseArtifact': base.name,
               'rule': 'PDF page numbers are 1-based; bboxes are normalized [left,top,right,bottom]. Verify PDF identity, then inspect full-page previews.'}
    write(work / 'paper-catalog.json', catalog)
    return catalog


def unique_section(markdown, heading):
    return len([line for line in markdown.splitlines() if line == heading and re.match(r'^#{1,6} ', line)]) == 1


def render(request):
    base, work = pathlib.Path(request['base']), pathlib.Path(request['work'])
    catalog = load(work / 'paper-catalog.json')
    papers = {p['id']: p for p in catalog['papers']}
    chosen = load(request['selection'])
    plates = chosen.get('figures', [])
    if not 1 <= len(plates) <= 4:
        raise ValueError('Select one to four supplemental plates, or report no usable sources separately')
    markdown = (base / 'review.md').read_text(encoding='utf-8')
    manifest = []
    for i, plate in enumerate(plates, 1):
        if not unique_section(markdown, plate['afterSection']):
            raise ValueError('Insertion heading must exactly match one existing Markdown heading')
        if not plate.get('title') or not plate.get('caption') or not 1 <= len(plate['panels']) <= 4:
            raise ValueError('Each plate needs a title, caption and one to four panels')
        images, provenance = [], []
        for panel in plate['panels']:
            paper = papers[panel['paperId']]
            pdf = work / paper['pdfFile']
            if digest(pdf) != paper['pdfSha256']:
                raise ValueError('Original PDF changed')
            page_index = panel['page'] - 1
            box = panel['bbox']
            if len(box) != 4 or not all(isinstance(x, (int, float)) for x in box) or not (0 <= box[0] < box[2] <= 1 and 0 <= box[1] < box[3] <= 1):
                raise ValueError('Invalid normalized crop')
            if box[2]-box[0] < .1 or box[3]-box[1] < .06 or not panel.get('originalFigure') or not panel.get('identityEvidence'):
                raise ValueError('Crop too small or missing source figure/identity evidence')
            with fitz.open(pdf) as doc:
                if not 0 <= page_index < min(len(doc), 40):
                    raise ValueError('Selected page was not supplied to the agent')
                page = doc[page_index]; r = page.rect
                clip = fitz.Rect(r.x0 + box[0]*r.width, r.y0 + box[1]*r.height, r.x0 + box[2]*r.width, r.y0 + box[3]*r.height)
                scale = min(2.5, 1600 / clip.width, 1800 / clip.height)
                pix = page.get_pixmap(matrix=fitz.Matrix(scale, scale), clip=clip, alpha=False)
                image = Image.frombytes('RGB', [pix.width, pix.height], pix.samples)
            image.thumbnail((1500, 1300)); images.append(image)
            provenance.append({**panel, 'sourceId': paper['sourceId'], 'sourceUrl': paper['expectedSource']['url'],
                               'pdfUrl': paper['pdfUrl'], 'pdfSha256': paper['pdfSha256'], 'license': paper['license'],
                               'licenseUrl': paper['licenseUrl']})
        width = max(x.width for x in images) + 40
        height = sum(x.height + 38 for x in images) + 20
        canvas = Image.new('RGB', (width, height), 'white'); draw = ImageDraw.Draw(canvas); y = 10
        for index, (im, evidence) in enumerate(zip(images, provenance), 1):
            draw.text((20, y), f'Panel {index} | {evidence["sourceId"]} | PDF p.{evidence["page"]}', fill='black')
            y += 24; canvas.paste(im, ((width-im.width)//2, y)); y += im.height+14
        filename = f'figure-paper-{i:02d}.png'; canvas.save(work / filename, optimize=True)
        while (work / filename).stat().st_size > 3_800_000 and canvas.width > 900:
            canvas.thumbnail((int(canvas.width*.85), int(canvas.height*.85)))
            canvas.save(work / filename, optimize=True)
        if (work / filename).stat().st_size > 3_800_000:
            raise ValueError('Plate too large for visual review; use fewer panels per plate')
        manifest.append({'id': f'P{i:02d}', 'file': filename, 'kind': '原论文图摘录/组图',
                         'title': plate['title'], 'caption': plate['caption'], 'afterSection': plate['afterSection'],
                         'sourceIds': list(dict.fromkeys(x['sourceId'] for x in provenance)), 'panels': provenance,
                         'imageSha256': digest(work / filename),
                         'evidenceBoundary': '来自所列论文PDF原图的裁剪与等比例排版；未重绘或修改图内数据。适用范围以原论文为准。'})
    write(work / 'paper-figures.json', manifest)
    write(work / 'paper-gaps.json', {'gaps': catalog['gaps'] + chosen.get('gaps', [])})
    return manifest


def assemble(request):
    base, work, output = map(pathlib.Path, (request['base'], request['work'], request['output']))
    expected = load(work / 'base-hashes.json')
    for name, value in expected.items():
        if digest(base / name) != value:
            raise ValueError('Base manuscript/figure changed during supplementation')
    output.mkdir(parents=True, exist_ok=True)
    for name in expected:
        shutil.copy2(base / name, output / name)
    for name in ('paper-figures.json', 'paper-gaps.json'):
        shutil.copy2(work / name, output / name)
    for figure in load(work / 'paper-figures.json'):
        if digest(work / figure['file']) != figure['imageSha256']:
            raise ValueError('Supplemental image changed after inspection')
        shutil.copy2(work / figure['file'], output / figure['file'])
    write(output / 'paper-provenance.json', {'baseArtifact': base.name, 'baseHashes': expected, 'workArtifact': work.name,
                                          'auditTask': request['auditTask'], 'originalProseUnchanged': True})
    return {'artifactId': output.name, 'figureCount': len(load(work / 'paper-figures.json'))}


if __name__ == '__main__':
    request = load(sys.argv[1])
    result = {'acquire': acquire, 'render': render, 'assemble': assemble}[request['operation']](request)
    print(json.dumps({'ok': True, 'result': result}, ensure_ascii=False))
