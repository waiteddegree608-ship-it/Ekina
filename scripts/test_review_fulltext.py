import pathlib, tempfile, unittest
from unittest.mock import patch
import fitz
from review_fulltext import acquire
from review_paper_figures import write, digest
class FullTextTest(unittest.TestCase):
 def test_all_pages_including_image_only_and_after_page_40_are_reading_inputs(self):
  with tempfile.TemporaryDirectory() as d:
   work=pathlib.Path(d);pdf=work/'p1.pdf';doc=fitz.open()
   for i in range(42):
    page=doc.new_page(width=200,height=200)
    if i!=2:page.insert_text((10,20),'Full article page %s: methods results discussion.'%i,fontsize=6)
   doc.save(pdf);doc.close();write(work/'p1-download.json',{'sha256':digest(pdf),'resolvedUrl':'https://example.org/paper.pdf'})
   result=acquire({'work':str(work),'paper':{'key':'p1'}})
   self.assertEqual('downloaded',result['status']);self.assertEqual(42,result['pageCount'])
   self.assertIn('p1-page-042.png',[c['file'] for c in result['chunks']]);self.assertIn('p1-page-003.png',[c['file'] for c in result['chunks']])
 def test_missing_download_does_not_become_read(self):
  with tempfile.TemporaryDirectory() as d:
   with patch('review_fulltext.download',side_effect=ValueError('unavailable')):
    result=acquire({'work':d,'paper':{'key':'p1','pdfUrls':['https://example.org/a']}})
   self.assertEqual('needs_access',result['status'])
