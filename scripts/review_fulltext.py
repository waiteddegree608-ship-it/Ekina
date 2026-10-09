"""Acquire and index complete PDFs for the pre-writing reading stage (no scientific writing)."""
import json
import pathlib
import sys
import fitz
from review_paper_figures import download, digest, load, write


def acquire(request):
    work = pathlib.Path(request['work'])
    paper = request['paper']
    key = paper['key']
    if not key.isalnum():
        raise ValueError('Invalid corpus key')
    pdf = work / (key + '.pdf')
    receipt = work / (key + '-download.json')
    failures = []
    if pdf.exists() and receipt.exists() and load(receipt).get('sha256') == digest(pdf):
        record = load(receipt)
    else:
        record = None
        for url in paper.get('pdfUrls', [])[:4]:
            try:
                resolved = download(url, pdf)
                record = {'requestedUrl': url, 'resolvedUrl': resolved, 'sha256': digest(pdf)}
                write(receipt, record)
                break
            except Exception as e:
                failures.append({'url': url, 'error': str(e)})
        if not record:
            result = {'status': 'needs_access', 'failures': failures}
            write(work / (key + '-acquisition.json'), result)
            return result
    chunks, buffer = [], ''
    with fitz.open(pdf) as doc:
        if doc.needs_pass or not 1 <= len(doc) <= 300:
            raise ValueError('Unreadable or oversized PDF')
        page_count = len(doc)
        empty_pages = []
        for i, page in enumerate(doc):
            text = page.get_text()
            if len(text.strip()) < 40:
                empty_pages.append(i + 1)
            buffer += f'\n\n[PDF PAGE {i+1}]\n' + text
        for i in range(0, len(buffer), 4500):
            name = f'{key}-text-{len(chunks)+1:03d}.txt'
            (work / name).write_text(buffer[i:i+4500], encoding='utf-8')
            chunks.append({'file': name, 'sha256': digest(work / name)})
        # Store every page preview, without the illustration-stage first-40-page limit.
        for i, page in enumerate(doc):
            scale = min(900 / page.rect.width, 1400 / page.rect.height)
            name = f'{key}-page-{i+1:03d}.png'
            page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False).save(work / name)
            chunks.append({'file': name, 'sha256': digest(work / name), 'kind': 'page_image'})
    result = {'status': 'downloaded', 'pdfFile': pdf.name, 'sha256': record['sha256'], 'resolvedUrl': record['resolvedUrl'],
              'pageCount': page_count, 'chunks': chunks, 'characters': len(buffer), 'failures': failures}
    write(work / (key + '-acquisition.json'), result)
    return result


if __name__ == '__main__':
    print(json.dumps(acquire(load(sys.argv[1])), ensure_ascii=True))
