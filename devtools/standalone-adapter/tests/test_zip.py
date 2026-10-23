import io
from datetime import datetime
import stat
import sys
from pathlib import Path
import unittest
import tempfile
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from verification import safe_zip, unpack


class ZipTests(unittest.TestCase):
    def pack(self,names):
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as z:
            for name in names:z.writestr(name,b'a')
        return out.getvalue()
    def test_bad_paths_duplicates_and_links_rejected(self):
        for names in (['../a'],['/abs'],['a','a'],['A','a'],['a','a/b'],['C:/a']):
            with self.subTest(names=names),self.assertRaises(ValueError):safe_zip(self.pack(names))
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as z:
            info=zipfile.ZipInfo('link');info.create_system=3;info.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(info,b'target')
        with self.assertRaises(ValueError):safe_zip(out.getvalue())
    def test_valid_zip(self):self.assertEqual(safe_zip(self.pack(['backend/pom.xml'])),{'backend/pom.xml':b'a'})
    def test_unpack_preserves_fixed_positive_archive_time(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'project'
            unpack({'backend/src/main/resources/mapper/auth/RoleMemberMapper.xml':b'<mapper/>'},root)
            resource=root/'backend/src/main/resources/mapper/auth/RoleMemberMapper.xml'
            self.assertEqual(datetime.fromtimestamp(resource.stat().st_mtime),datetime(1980,1,2))
            self.assertEqual(resource.read_bytes(),b'<mapper/>')
