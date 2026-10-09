import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import fitz
from PIL import Image
from review_paper_figures import acquire, render, assemble, digest, public_target
from review_latex import insert_paper_figures


class PaperFiguresTest(unittest.TestCase):
    def fixture(self, root):
        base, work = root/'base', root/'work'; base.mkdir(); work.mkdir()
        (base/'review.md').write_text('# Review\n\n## Study\n\nOriginal prose.\n\n## End\n\nOriginal conclusion.\n',encoding='utf8')
        (base/'sources.json').write_text(json.dumps([{'id':'S1','title':'Fixture paper','url':'https://example.org/paper'}]),encoding='utf8')
        Image.new('RGB',(20,20),'blue').save(base/'figure-original.png')
        (base/'figures.json').write_text('[]')
        doc=fitz.open(); page=doc.new_page(width=400,height=600)
        page.insert_text((30,30),'Fixture paper DOI: fixture')
        page.draw_rect(fitz.Rect(40,100,360,400),color=(0,0,1));page.insert_text((50,120),'Figure 1: original panel')
        doc.save(work/'p1.pdf');doc.close()
        (work/'paper-catalog.json').write_text(json.dumps({'papers':[{'id':'p1','sourceId':'S1','pdfFile':'p1.pdf','pdfSha256':digest(work/'p1.pdf'),'pdfUrl':'https://example.org/p.pdf','expectedSource':{'url':'https://example.org/paper'},'license':'unknown','licenseUrl':''}],'gaps':[]}))
        selection={'figures':[{'title':'Original research panel','caption':'Extracted original figure','afterSection':'## Study','panels':[{'paperId':'p1','page':1,'bbox':[.08,.12,.94,.75],'originalFigure':'Figure 1','identityEvidence':'Fixture title and DOI'}]}],'gaps':[]}
        (work/'selection.json').write_text(json.dumps(selection))
        (work/'base-hashes.json').write_text(json.dumps({f.name:digest(f) for f in base.iterdir()}))
        return base,work,selection

    def test_crop_assemble_preserves_every_original_and_inserts_only_at_section_end(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);base,work,_=self.fixture(root)
            before={p.name:p.read_bytes() for p in base.iterdir()}
            manifest=render({'base':str(base),'work':str(work),'selection':str(work/'selection.json')})
            output=root/'supplemented';assemble({'base':str(base),'work':str(work),'output':str(output),'auditTask':'test'})
            for n,b in before.items():self.assertEqual(b,(output/n).read_bytes());self.assertEqual(b,(base/n).read_bytes())
            self.assertTrue((output/'figure-paper-01.png').is_file())
            self.assertEqual(digest(work/'p1.pdf'),manifest[0]['panels'][0]['pdfSha256'])
            merged=insert_paper_figures((base/'review.md').read_text(),manifest)
            self.assertLess(merged.index('Original prose.'),merged.index('figure-paper-01.png'))
            self.assertLess(merged.index('figure-paper-01.png'),merged.index('## End'))

    def test_invalid_crop_identity_and_changed_base_cannot_be_published(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);base,work,selection=self.fixture(root)
            selection['figures'][0]['panels'][0]['bbox']=[-.1,0,1,1]
            (work/'selection.json').write_text(json.dumps(selection))
            with self.assertRaises(ValueError):render({'base':str(base),'work':str(work),'selection':str(work/'selection.json')})
            (base/'review.md').write_text('changed')
            with self.assertRaises(ValueError):assemble({'base':str(base),'work':str(work),'output':str(root/'out'),'auditTask':'test'})
            self.assertFalse((root/'out').exists())

    def test_download_failure_is_recorded_as_gap_not_fake_fulltext(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);base,work,_=self.fixture(root)
            (work/'plan.json').write_text(json.dumps({'papers':[{'sourceId':'S1','pdfUrl':'https://example.org/p.pdf'}]}))
            with patch('review_paper_figures.download',side_effect=ValueError('HTTP 403')):
                result=acquire({'base':str(base),'work':str(work),'plan':str(work/'plan.json')})
            self.assertEqual([],result['papers']);self.assertIn('403',result['gaps'][0])

    def test_synthetic_private_dns_uses_independent_public_resolution_not_private_connection(self):
        import io
        with patch('review_paper_figures.socket.getaddrinfo',return_value=[(2,1,6,'',('172.29.0.34',443))]):
            answer={'Status':0,'Question':[{'name':'example.org.'}],'Answer':[{'type':1,'data':'93.184.216.34'}]}
            with patch('review_paper_figures.urllib.request.urlopen',return_value=io.BytesIO(json.dumps(answer).encode())):
                self.assertEqual('93.184.216.34',public_target('https://example.org/a.pdf')[1])
            answer['Answer'][0]['data']='10.0.0.1'
            with patch('review_paper_figures.urllib.request.urlopen',return_value=io.BytesIO(json.dumps(answer).encode())):
                with self.assertRaises(ValueError):public_target('https://example.org/a.pdf')

    def test_private_network_credentials_and_non_https_are_rejected(self):
        with self.assertRaises(ValueError):public_target('http://example.org/p.pdf')
        with self.assertRaises(ValueError):public_target('https://user:secret@example.org/p.pdf')
        with patch('review_paper_figures.socket.getaddrinfo',return_value=[(2,1,6,'',('127.0.0.1',443))]):
            with self.assertRaises(ValueError):public_target('https://example.org/p.pdf')


if __name__=='__main__':unittest.main()
