import copy
import json
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from http_smoke import check_update


class UpdateTests(unittest.TestCase):
    def package(self):
        return {'protocolVersion':'4','mode':'APPLY','sourceRevision':'2026.10.23.1005','expectedStateSequence':0,
                'nextTemplateState':{'stateSequence':1,'effective':{'login':{}}},'operations':[], 'payloadManifest':{}}
    def check(self,data):
        return check_update({'extension-update-package.json':json.dumps(data).encode()},{'templateRevision':'2026.10.23.1005'},('login',),0)
    def test_wrong_sequence_and_cross_release_are_rejected(self):
        self.check(self.package())
        for field,value in [('expectedStateSequence',1),('sourceRevision','2026.10.23.2')]:
            data=self.package();data[field]=value
            with self.assertRaises(ValueError):self.check(data)
        data=self.package();data['nextTemplateState']['stateSequence']=2
        with self.assertRaises(ValueError):self.check(data)
    def test_missing_payload_and_bad_target_are_rejected(self):
        for target,payload in [('backend/a','payload/missing'),('../escape',None)]:
            data=self.package();data['operations']=[{'index':0,'operationId':'one','target':target,'payloadRef':payload}]
            with self.assertRaises(ValueError):self.check(data)
