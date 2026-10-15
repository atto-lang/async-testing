"""Preservation contracts for compiler-cache admission, without running Maven."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


class CompilerCacheAdmission(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root/'scripts').mkdir()
        shutil.copy(Path(__file__).with_name('test.sh'), self.root/'scripts/test.sh')
        self.source = self.root/'.compiler'
        self.source.mkdir()
        self.git('init', '-q')
        (self.source/'local.txt').write_text('original')
        self.git('add', '.')
        self.git('-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','initial')
        self.original = self.git('rev-parse','HEAD')
        (self.source/'new.txt').write_text('next revision')
        self.git('add', '.')
        self.git('-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','next')
        revision = self.git('rev-parse','HEAD')
        (self.root/'compiler-revision.txt').write_text(revision)
        (self.root/'compiler-tree.txt').write_text(self.git('rev-parse','HEAD^{tree}'))
        (self.root/'pom.xml').write_text('<oreslang.version>0.1.0-'+revision+'</oreslang.version>')
        self.git('checkout','--detach',self.original)
        bin_dir = self.root/'bin'
        bin_dir.mkdir()
        mvn = bin_dir/'mvn'
        mvn.write_text('#!/bin/sh\ntouch "$SETUP_MAVEN_CALLED"\nexit 99\n')
        mvn.chmod(0o755)
        self.env = {**os.environ,'PATH':str(bin_dir)+os.pathsep+os.environ['PATH'],
                    'SETUP_MAVEN_CALLED':str(self.root/'maven-called')}

    def git(self,*args):
        return subprocess.check_output(['git','-C',str(self.source),*args],text=True,stderr=subprocess.DEVNULL).strip()

    def reject(self):
        result = subprocess.run(['sh','scripts/test.sh'],cwd=self.root,env=self.env,capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(self.git('rev-parse','HEAD'),self.original)
        self.assertFalse((self.root/'maven-called').exists())
        return result.stderr

    def test_dirty_tracked_checkout_keeps_head_and_changes(self):
        (self.source/'local.txt').write_text('developer edits')
        self.assertIn('dirty',self.reject())
        self.assertEqual((self.source/'local.txt').read_text(),'developer edits')

    def test_untracked_checkout_keeps_head_and_files(self):
        (self.source/'untracked').write_text('developer file')
        self.assertIn('dirty',self.reject())
        self.assertTrue((self.source/'untracked').exists())

    def test_symlink_checkout_is_rejected(self):
        target=self.root/'saved'
        self.source.rename(target)
        self.source.symlink_to(target,target_is_directory=True)
        self.assertIn('symlink',self.reject())

    def test_invalid_tree_pin_does_not_switch_head(self):
        (self.root/'compiler-tree.txt').write_text('main')
        self.assertIn('tree',self.reject())


if __name__=='__main__':
    unittest.main()
