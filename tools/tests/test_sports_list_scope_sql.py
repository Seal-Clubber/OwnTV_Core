"""Sports scope uses the real dynamic membership SQL, including refreshes and exclusions."""
import unittest, pathlib, re, json
import test_live_category_sql as category_sql

class SportsListScopeSqlTests(unittest.TestCase):
    def setUp(self):
        self.fixture = category_sql.ProductionQueryTests(); self.fixture.setUp()
    def tearDown(self): self.fixture.tearDown()
    def members(self, key, sources=(10,11)):
        return {row['itemId'] for row in self.fixture.query('liveSelectionKeys', contextKey=key, categoryId=None, sourceIds=list(sources))}
    def add_rule(self, key, sid, kind, ref, profile=1):
        self.fixture.db.execute('INSERT OR REPLACE INTO custom_category_live_rules VALUES (?,?,?,?,?,?,?,?,?)',(profile,key,sid,kind,ref,ref,kind!='EXCLUDE',1,100))
    def test_dutch_and_sports_union_follows_new_channels(self):
        self.add_rule('custom:dutch',10,'GROUP','id:news')
        self.add_rule('custom:sports',10,'GROUP','id:sports')
        self.add_rule('custom:sports',10,'CHANNEL','id:one')
        self.assertEqual({30,31,33,34},self.members('custom:dutch')|self.members('custom:sports'))
        self.fixture.channel(35,10,21,'five','NPO1')
        self.assertEqual({30,31,33,34,35},self.members('custom:dutch')|self.members('custom:sports'))
    def test_exclusion_applies_inside_its_list_and_union_keeps_other_members(self):
        self.add_rule('custom:dutch',10,'GROUP','id:news')
        self.add_rule('custom:sports',10,'GROUP','id:sports')
        self.add_rule('custom:sports',10,'CHANNEL','id:one')
        self.add_rule('custom:sports',10,'EXCLUDE','id:one')
        self.add_rule('custom:sports',10,'EXCLUDE','id:two')
        self.assertEqual({33,34},self.members('custom:sports'))
        self.assertEqual({30,33,34},self.members('custom:dutch')|self.members('custom:sports'))
    def test_active_sources_profile_and_deleted_context_do_not_broaden_scope(self):
        self.add_rule('custom:turkish',11,'GROUP','id:news')
        self.add_rule('custom:sports',10,'GROUP','id:sports',profile=2)
        self.assertEqual({32},self.members('custom:turkish'))
        self.assertEqual(set(),self.members('custom:turkish',sources=(10,)))
        self.assertEqual(set(),self.members('custom:sports'))
        self.assertEqual(set(),self.members('custom:deleted'))

class SportsGuideSqlTests(unittest.TestCase):
    def setUp(self):
        self.fixture = category_sql.ProductionQueryTests(); self.fixture.setUp()
        source=(pathlib.Path(__file__).resolve().parents[2]/'core/src/main/java/tv/own/owntv/core/database/dao/EpgDao.kt').read_text(encoding='utf-8')
        for expr,name in re.findall(r'@Query\((.*?)\)\s+(?:suspend\s+)?fun\s+(\w+)\(',source,re.S):
            if name=='sportsMatchSummaries':self.sql=''.join(json.loads(token) for token in re.findall(r'"(?:\\.|[^"\\])*"',expr))
    def tearDown(self): self.fixture.tearDown()
    def test_match_shapes_channel_keys_and_time_window_in_production_query(self):
        for i,key,start,title in ((1,'ziggo',100,'Ajax vs PSV'),(2,'ziggo',100,'Turkey v. France'),(3,'ziggo',100,'Fenerbahçe – Real Madrid'),(4,'other',100,'Real Madrid - Barcelona'),(5,'ziggo',100,'News'),(6,'ziggo',1000,'Ajax - PSV'),(7,'ziggo',1,'Turkey - France')):
            self.fixture.insert('epg_programmes',id=i,sourceId=10,epgChannelId=key,startMs=start+i,stopMs=start+i+50,title=title)
        rows=self.fixture.db.execute(self.sql.replace(':epgKeys','?').replace(':from','?').replace(':to','?'),('ziggo',80,500,80)).fetchall()
        self.assertEqual([1,2,3],[row['id'] for row in rows])
        self.assertTrue(all(row['description'] is None for row in rows))

if __name__=='__main__': unittest.main()
