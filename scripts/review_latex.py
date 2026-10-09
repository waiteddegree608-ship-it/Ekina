"""Local review typesetting/versioning. Copies Ekina's prose; never authors research."""
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import uuid

import pypandoc
import fitz

PREAMBLE = r'''\documentclass[UTF8,a4paper,11pt,fontset=windows]{ctexart}
\usepackage[margin=23mm]{geometry}
\usepackage{graphicx,longtable,booktabs,array,calc,amsmath,amssymb,xcolor,hyperref,float,caption}
\setmainfont{Times New Roman}
\newcounter{none}
\pagestyle{plain}
\renewcommand{\topfraction}{0.95}
\renewcommand{\textfraction}{0.05}
\renewcommand{\floatpagefraction}{0.85}
\hypersetup{colorlinks=true,urlcolor=teal,linkcolor=teal,breaklinks=true}
\Urlmuskip=0mu plus 1mu
\expandafter\def\expandafter\UrlBreaks\expandafter{\UrlBreaks\do\a\do\b\do\c\do\d\do\e\do\f\do\g\do\h\do\i\do\j\do\k\do\l\do\m\do\n\do\o\do\p\do\q\do\r\do\s\do\t\do\u\do\v\do\w\do\x\do\y\do\z\do\A\do\B\do\C\do\D\do\E\do\F\do\G\do\H\do\I\do\J\do\K\do\L\do\M\do\N\do\O\do\P\do\Q\do\R\do\S\do\T\do\U\do\V\do\W\do\X\do\Y\do\Z\do\0\do\1\do\2\do\3\do\4\do\5\do\6\do\7\do\8\do\9}
\setlength{\parindent}{0pt}
\setlength{\parskip}{0.65em}
\linespread{1.3}
\setlength{\emergencystretch}{3em}
\providecommand{\tightlist}{\setlength{\itemsep}{0pt}\setlength{\parskip}{0pt}}
\providecommand{\pandocbounded}[1]{#1}
\begin{document}
'''


def escaped(text):
    return ''.join({'\\': r'\textbackslash{}', '{': r'\{', '}': r'\}', '&': r'\&', '%': r'\%',
                    '$': r'\$', '#': r'\#', '_': r'\_', '~': r'\textasciitilde{}', '^': r'\textasciicircum{}'}.get(c, c) for c in str(text))


def validate_tex(source, folder):
    if len(source.encode('utf8')) > 500_000:
        raise ValueError('LaTeX源码超过500KB')
    # This editor builds self-contained text plus local figure files, not arbitrary TeX programs.
    if '^^' in source or re.search(r'\\(?:input|include|openin|openout|read|write|immediate|catcode|csname|directlua|special|write18)\b', source):
        raise ValueError('此文稿编辑器不支持外部文件读写、动态命令或shell指令')
    for name in re.findall(r'\\includegraphics(?:\[[^\]]*\])?\{([^}]+)\}', source):
        if not re.fullmatch(r'figure-[a-z0-9-]{1,64}\.png', name) or not (folder / name).is_file():
            raise ValueError('图片必须是本版本已有的figure-*.png')
    if '\\begin{document}' not in source or '\\end{document}' not in source:
        raise ValueError('需要完整的LaTeX文档')


def insert_paper_figures(markdown, figures):
    lines = markdown.splitlines()
    insertions = {}
    for figure in figures:
        heading = figure['afterSection']
        positions = [i for i, line in enumerate(lines) if line == heading]
        if len(positions) != 1 or not re.match(r'^#{1,6} ', heading):
            raise ValueError('原图插入位置与正文不一致')
        level = len(heading.split(' ', 1)[0]); end = len(lines)
        for i in range(positions[0]+1, len(lines)):
            match = re.match(r'^(#{1,6}) ', lines[i])
            if match and len(match[1]) <= level:
                end = i; break
        insertions.setdefault(end, []).append(f"\n![{figure['id']}]({figure['file']})\n")
    for i in sorted(insertions, reverse=True):
        lines[i:i] = insertions[i]
    return '\n'.join(lines)


def make_tex(bundle, title, status):
    figures = json.loads((bundle / 'figures.json').read_text(encoding='utf8')) if (bundle / 'figures.json').exists() else []
    supplemental = json.loads((bundle / 'paper-figures.json').read_text(encoding='utf8')) if (bundle / 'paper-figures.json').exists() else []
    for f in supplemental:
        f = dict(f)
        f['caption'] = '原论文图补充：' + f['caption'] + ' 原图定位：' + '；'.join(
            f"[{p['sourceId']}] {p['originalFigure']}，PDF第{p['page']}页，{p['sourceUrl']}" for p in f['panels'])
        figures.append(f)
    by_file = {f['file']: f for f in figures}
    placeholders = {}

    def figure(match):
        label, name = match.group(1), match.group(2)
        f = by_file.get(name)
        if not f or not (bundle / name.replace('.svg', '.png')).is_file():
            return '\n\n［配图尚未完成：' + label + '］\n\n'
        token = 'EKINAFIGURE' + str(len(placeholders)) + 'BLOCK'
        caption = f['caption'] + ' 来源：' + '、'.join(f['sourceIds']) + '。' + f['evidenceBoundary']
        # Preserve the worker's complete caption, without inventing scientific text.
        placeholders[token] = ('\\begin{figure}[!htbp]\n\\centering\n\\includegraphics[width=\\linewidth,height=0.62\\textheight,keepaspectratio]{'
                               + name.replace('.svg', '.png') + '}\n\\par\\smallskip{\\small ' + escaped(caption) + '\\par}\n\\end{figure}')
        return '\n\n' + token + '\n\n'

    original = (bundle / 'review.md').read_text(encoding='utf8')
    md = re.sub(r'!\[([^\]]*)\]\((figure-[a-z0-9-]+\.(?:svg|png))\)', figure, insert_paper_figures(original, supplemental) if supplemental else original)
    heading = re.match(r'^# ([^\n]+)\n', md)
    if heading:
        title=heading.group(1);md=md[heading.end():]
    body = pypandoc.convert_text(md, 'latex', format='markdown+pipe_tables+autolink_bare_uris-raw_tex-raw_html', extra_args=['--wrap=none','--shift-heading-level-by=-1']).replace('\r\n', '\n')
    for token, tex in placeholders.items():
        body = body.replace(token, tex)
    notice = '中文阅读稿 · ' + ('模型核查通过，仍非专家验收' if status == 'draft_ready' else '制作或修订中，尚未完成验收')
    references=''
    if not re.search(r'^#{1,6}\s.*(?:参考文献|来源索引)',md,re.M) and (bundle/'sources.json').exists():
        entries=json.loads((bundle/'sources.json').read_text(encoding='utf8'))
        references='\n\\clearpage\n\\section*{来源索引}\n{\\small\n'
        for item in entries:
            references+='\\noindent ['+escaped(item['id'])+'] '+escaped(item['title'])+'。'+escaped(item.get('year') or '')+'\\par\n'
            references+='\\url{'+escaped(item['url'])+'}\\par\\medskip\n'
        references+='}\n'
    return PREAMBLE + '\n{\\small\\color{gray}' + escaped(notice) + '}\n\\begin{center}{\\LARGE\\bfseries '+escaped(title)+'\\par}\\end{center}\n\n' + body + references + '\n\\end{document}\n'


def run(command, cwd, log, timeout=50):
    with log.open('ab') as out:
        result = subprocess.run(command, cwd=cwd, stdout=out, stderr=subprocess.STDOUT, timeout=timeout,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    return result.returncode


def build(request):
    root = pathlib.Path(request['documentRoot']).resolve()
    root.mkdir(parents=True, exist_ok=True)
    index_path = root / 'index.json'
    index = json.loads(index_path.read_text(encoding='utf8')) if index_path.exists() else {'versions': []}
    if request['mode'] == 'auto' and not request.get('force',False) and index.get('lastAutoSignature') == request['signature']:
        return index
    version = 'v_' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '_' + uuid.uuid4().hex[:8]
    folder = root / 'versions' / version
    folder.mkdir(parents=True)
    original = pathlib.Path(request['bundle']).resolve()
    for f in original.iterdir():
        if f.is_file() and (f.name in ['review.md', 'sources.json', 'evidence.csv', 'protocol.md', 'gaps.md', 'figures.json', 'figure-plan.json', 'paper-figures.json', 'paper-provenance.json', 'paper-gaps.json']
                            or re.fullmatch(r'figure-[a-z0-9-]{1,64}\.(png|svg)', f.name)):
            shutil.copy2(f, folder / f.name)
    record = {'id': version, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'author': 'Ekina' if request['mode'] == 'auto' else '用户编辑', 'title': request['title'],
              'researchStatus': request.get('researchStatus', 'manual'), 'artifactId': request.get('artifactId', ''),
              'basedOn': request.get('basedOn', ''), 'compiled': False, 'message': request.get('message', '')}
    try:
        source = request['source'] if request['mode'] == 'manual' else make_tex(folder, request['title'], request['researchStatus'])
        (folder / 'main.tex').write_text(source, encoding='utf8')
        validate_tex(source, folder)
        record['figureCount'] = len(re.findall(r'\\includegraphics(?:\[[^\]]*\])?\{figure-[a-z0-9-]+\.png\}', source))
        engine = shutil.which('xelatex')
        if not engine:
            raise ValueError('未找到本地XeLaTeX；源码已保留')
        log = folder / 'compile.log'
        for _ in range(2):
            code = run([engine, '--disable-installer', '-no-shell-escape', '-interaction=nonstopmode', '-halt-on-error', '-file-line-error', 'main.tex'], folder, log)
            if code != 0:
                raise ValueError('LaTeX编译失败；见编译日志，旧版本仍保留')
        record['compiled'] = (folder / 'main.pdf').is_file()
        if not record['compiled']:
            raise ValueError('编译器未输出PDF')
        with fitz.open(folder/'main.pdf') as pdf:
            if len(pdf)>100:
                raise ValueError('PDF超过100页，保留PDF，请使用外部阅读器')
            for n,page in enumerate(pdf,1):
                page.get_pixmap(matrix=fitz.Matrix(1.4,1.4),alpha=False).save(folder/f'page-{n:03d}.png')
            record['pageCount']=len(pdf)
    except Exception as e:
        record['error'] = str(e)
    (folder / 'version.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf8')
    git_log = root / 'git.log'
    try:
        if not (root / '.git').exists():
            if run(['git', 'init', '-b', 'main'], root, git_log, 20) != 0:
                raise ValueError('git init失败')
            (root / '.gitignore').write_text('index.json\ngit.log\nrequest-*.json\n**/*.aux\n**/*.out\n**/*.toc\n**/main.log\n**/*.xdv\n**/*.synctex.gz\n', encoding='utf8')
        if run(['git', 'add', '--', 'versions/' + version, '.gitignore'], root, git_log, 20) != 0:
            raise ValueError('git add失败')
        if run(['git', '-c', 'user.name=Ekina Local Documents', '-c', 'user.email=local@ekina.invalid', 'commit', '-m', request['mode'] + ' ' + version], root, git_log, 20) != 0:
            raise ValueError('git commit失败')
        record['commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    except Exception as e:
        record['versioningError'] = str(e)
    index['versions'].insert(0, record)
    if request['mode'] == 'auto':
        index['lastAutoSignature'] = request['signature']
    if record.get('compiled'):
        index['latestPdfVersion'] = version
    tmp = root / 'index.tmp'
    tmp.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf8')
    tmp.replace(index_path)
    return index


if __name__ == '__main__':
    request_path = pathlib.Path(sys.argv[1])
    result = build(json.loads(request_path.read_text(encoding='utf8')))
    print(json.dumps({'version': result['versions'][0]['id'], 'compiled': result['versions'][0]['compiled']}))
