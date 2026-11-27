#!/usr/bin/env python3
import ast,pathlib,re,unittest
path=pathlib.Path(__file__).with_name("release-ui-smoke.py")
tree=ast.parse(path.read_text())
function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=="is_playing_dump")
namespace={"re":re}
exec(compile(ast.Module(body=[function],type_ignores=[]),str(path),"exec"),namespace)
playing=namespace["is_playing_dump"]
class MediaStateTest(unittest.TestCase):
    def test_android_15_playing(self):self.assertTrue(playing("com.mawja.player state=PLAYING(3), title=فرانس 24 عربي"))
    def test_legacy_playing(self):self.assertTrue(playing("com.mawja.player state=3, title=فرانس 24 عربي"))
    def test_paused_rejected(self):self.assertFalse(playing("com.mawja.player state=PAUSED(2), title=فرانس 24 عربي"))
    def test_buffering_rejected(self):self.assertFalse(playing("com.mawja.player state=BUFFERING(6), title=فرانس 24 عربي"))
    def test_wrong_title(self):self.assertFalse(playing("com.mawja.player state=PLAYING(3), title=Other"))
    def test_wrong_package(self):self.assertFalse(playing("other.app state=PLAYING(3), title=فرانس 24 عربي"))
if __name__=="__main__":unittest.main()
