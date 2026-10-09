import json
import pathlib
import tempfile
import unittest
from review_latex import make_tex, validate_tex, PREAMBLE


class LatexDocumentTest(unittest.TestCase):
    def test_conversion_keeps_chinese_evidence_and_full_worker_caption(self):
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d)
            (p/'review.md').write_text('# 测试综述\n\n可追溯论断[C01]，数值为37%。\n\n![图1](figure-test.svg)\n',encoding='utf8')
            (p/'figure-test.png').write_bytes(b'placeholder')
            (p/'figures.json').write_text(json.dumps([{'file':'figure-test.svg','caption':'原作者完整图注。','sourceIds':['C01'],'evidenceBoundary':'仅示意。'}]),encoding='utf8')
            tex=make_tex(p,'测试','draft_ready')
            self.assertIn('可追溯论断{[}C01{]}，数值为37\\%',tex)
            self.assertIn('原作者完整图注。',tex)
            self.assertIn('{figure-test.png}',tex)
            self.assertNotIn('EKINAFIGURE',tex)
            validate_tex(tex,p)

    def test_missing_figure_is_explicit_not_a_broken_include(self):
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d);(p/'review.md').write_text('![图1](figure-test.svg)',encoding='utf8')
            tex=make_tex(p,'测试','pending')
            self.assertIn('配图尚未完成',tex)
            self.assertNotIn('\\includegraphics',tex)

    def test_raw_tex_in_research_markdown_cannot_read_files(self):
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d);(p/'review.md').write_text(r'正文 \input{secret.txt}',encoding='utf8')
            tex=make_tex(p,'测试','pending')
            self.assertNotIn(r'\input{secret.txt}',tex)
            validate_tex(tex,p)

    def test_editor_rejects_external_read_and_figure_traversal(self):
        for text in [r'\input{secret}',r'\includegraphics{../secret.png}',r'\csname input\endcsname{secret}']:
            with self.assertRaises(ValueError):validate_tex(PREAMBLE+text+r'\end{document}',pathlib.Path('.'))


if __name__=='__main__':unittest.main()
