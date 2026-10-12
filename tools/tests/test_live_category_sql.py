"""Execute production Room SQL and migration on host SQLite. Android driver tests are separate."""
import json, pathlib, re, sqlite3, time, unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]
DAO = (ROOT/'core/src/main/java/tv/own/owntv/core/database/dao/CustomCategoryDao.kt').read_text(encoding='utf-8')
SCHEMA = ROOT/'core/schemas/tv.own.owntv.core.database.OwnTVDatabase'
DB_SOURCE = (ROOT/'core/src/main/java/tv/own/owntv/core/database/OwnTVDatabase.kt').read_text(encoding='utf-8')
CONSTANTS = {name: json.loads(value) for name,value in re.findall(r'private const val (\w+) = ("(?:\\.|[^"\\])*")', DAO)}
QUERIES = {}
for expr, name in re.findall(r'@Query\((.*?)\)\s+(?:suspend\s+)?fun\s+(\w+)\(', DAO, re.S):
    tokens = re.findall(r'"(?:\\.|[^"\\])*"|LIVE_RULE_MEMBERSHIP|LIVE_CONTEXT_MEMBERSHIP',expr)
    QUERIES[name] = ''.join(json.loads(t) if t.startswith('"') else CONSTANTS[t] for t in tokens)

class ProductionQueryTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.schema(48)
        for pid in (1,2): self.insert('profiles',id=pid,name=f'Profile {pid}')
        for sid in (10,11): self.insert('sources',id=sid,name=f'Provider {sid}',type='XTREAM',url='https://example.test')
        for gid,sid,ref in ((20,10,'news'),(21,10,'sports'),(22,11,'news')):
            self.insert('categories',id=gid,sourceId=sid,mediaType='LIVE',remoteId=ref,name='Same name')
        for cid,sid,gid,ref,name in ((30,10,20,'one','İstanbul'),(31,10,21,'two','één Nieuws'),(32,11,22,'one','İstanbul'),(33,10,21,'three','العربية'),(34,10,21,'four','ırmak')):
            self.channel(cid,sid,gid,ref,name)
        self.params = dict(profileId=1, contextKey='custom:tr', sourceIds=[10,11],type='LIVE',limit=100, categoryIds=[-1],itemIds=[-1], contextKeys=['custom:tr'], excludedIds=[-1])
    def tearDown(self): self.db.close()
    def schema(self,version):
        data=json.loads((SCHEMA/f'{version}.json').read_text(encoding='utf-8'))['database']
        for e in data['entities']:
            self.db.execute(e['createSql'].replace('${TABLE_NAME}',e['tableName']))
            for i in e.get('indices',[]): self.db.execute(i['createSql'].replace('${TABLE_NAME}',e['tableName']))
            for trigger in e.get('contentSyncTriggers',[]): self.db.execute(trigger)
    def insert(self,table,**values):
        for cid,name,kind,notnull,default,pk in self.db.execute(f'PRAGMA table_info(`{table}`)'):
            if name not in values and notnull and default is None and not pk:
                values[name] = '' if kind=='TEXT' else 0
        keys=list(values)
        self.db.execute(f'INSERT INTO `{table}` ({",".join(keys)}) VALUES ({",".join("?" for _ in keys)})',list(values.values()))
    def channel(self,cid,sid,gid,ref,name):
        self.insert('channels',id=cid,sourceId=sid,categoryId=gid,remoteId=ref,name=name,streamUrl=f'https://example.test/{ref}',sortOrder=cid)
    def rule(self,sid,kind,ref,active=1,pid=1):
        self.db.execute('INSERT OR REPLACE INTO custom_category_live_rules VALUES (?,?,?,?,?,?,?,?,?)',(pid,'custom:tr',sid,kind,ref,ref,kind!='EXCLUDE',active,100))
    def query(self,name,**overrides):
        sql=QUERIES[name]; bindings=self.params|overrides; positional=[]
        def bind(match):
            value=bindings[match.group(1)]
            if isinstance(value,list):
                positional.extend(value)
                return ','.join('?' for _ in value) if value else 'NULL'
            positional.append(value);return '?'
        sql=re.sub(r':(\w+)',bind,sql)
        return self.db.execute(sql,positional).fetchall()
    def ids(self,**kwargs): return [r['id'] for r in self.query('snapshotChannels',**kwargs)]
    def test_union_deduplication_and_profiles(self):
        self.rule(10,'GROUP','id:news');self.rule(10,'CHANNEL','id:one');self.rule(11,'GROUP','id:news')
        self.insert('custom_category_members',profileId=1,mediaType='LIVE',contextKey='custom:tr',itemId=30,position=0)
        self.assertEqual(set(self.ids()),{30,32})
        self.assertEqual(self.query('countMembers')[0][0],2)
        self.assertEqual(self.query('observeCountsByContexts')[0]['count'],2)
        self.assertEqual(self.ids(profileId=2),[])
        self.assertEqual(self.query('contextsOf',itemId=30)[0]['contextKey'],'custom:tr')
        self.assertEqual(set(r['id'] for r in self.query('itemIds')),{30,32})
    def test_exclusions_manual_inclusions_and_group_removal(self):
        self.rule(10,'GROUP','id:news');self.rule(10,'CHANNEL','id:one');self.rule(10,'EXCLUDE','id:one')
        self.assertEqual(self.ids(),[])
        self.rule(10,'GROUP','id:news',active=0);self.rule(10,'EXCLUDE','id:one',active=0)
        self.assertEqual(self.ids(),[30])
    def test_refresh_rename_disappearance_and_return(self):
        self.rule(10,'GROUP','id:news');self.rule(10,'EXCLUDE','id:one')
        self.db.execute('DELETE FROM channels WHERE sourceId=10');self.db.execute('DELETE FROM categories WHERE sourceId=10')
        self.assertEqual(self.ids(),[])
        self.assertFalse(self.query("observeLiveRuleAvailability")[0]["available"])
        self.insert('categories',id=200,sourceId=10,mediaType='LIVE',remoteId='news',name='Renamed')
        self.channel(300,10,200,'one','Renamed channel');self.channel(301,10,200,'new','New arrival')
        self.assertEqual(self.ids(),[301])
        self.db.execute("UPDATE categories SET remoteId='different' WHERE id=200")
        self.assertEqual(self.ids(),[])
        self.db.execute("UPDATE categories SET remoteId='news' WHERE id=200")
        self.assertEqual(self.ids(),[301])
    def test_manual_missing_channel_returns_and_ordering_is_preserved(self):
        self.rule(10,'GROUP','id:sports');self.rule(10,'CHANNEL','id:one')
        self.insert('content_order',profileId=1,mediaType='LIVE',contextKey='custom:tr',itemId=33,position=0)
        self.assertEqual(self.ids()[0],33)
        self.db.execute('DELETE FROM channels WHERE id=30');self.assertNotIn(30,self.ids())
        self.channel(300,10,20,'one','Renamed');self.assertIn(300,self.ids())
        self.assertEqual(self.ids()[0],33)
    def test_unicode_search_is_scoped_and_fts_stays_current(self):
        self.rule(10,'GROUP','id:news')
        found=self.query('searchChannelsFts',query='istanbul',ftsQuery='"istanbul"*')
        self.assertEqual([r['id'] for r in found],[30])
        self.rule(10,'GROUP','id:sports')
        for query,expected in [('EEN',[31]),('العربية',[33]),('ırmak',[34])]:
            found=self.query('searchChannelsFts',query=query,ftsQuery=f'"{query}"*')
            self.assertEqual([r['id'] for r in found],expected)
        # Unicode61 does not conflate Turkish dotted and dotless I.
        self.assertEqual(self.query('searchChannelsFts',query='irmak',ftsQuery='"irmak"*'),[])
        self.db.execute("UPDATE channels SET name='Bursa' WHERE id=30")
        self.assertEqual(self.query('searchChannelsFts',query='istanbul',ftsQuery='"istanbul"*'),[])
        self.assertEqual([r['id'] for r in self.query('searchChannelsFts',query='bursa',ftsQuery='"bursa"*')],[30])
    def test_preview_existing_members_exclusions_and_explicit_reinclude(self):
        self.rule(10,'GROUP','id:news');self.rule(10,'EXCLUDE','id:one')
        self.assertEqual(self.query('previewLiveAddition',categoryIds=[20,21,22])[0][0],4)
        self.assertEqual(self.query('observeLiveAdditionCount',categoryIds=[20,21,22])[0][0],4)
        self.assertEqual(len(self.query('pagingLiveAddition',categoryIds=[20,21,22])),4)
        self.assertEqual(self.query('observeLiveAdditionCount',categoryIds=[20,21,22],excludedIds=[31,99999])[0][0],3)
        self.assertEqual(self.query('previewLiveAddition',categoryIds=[20,21,22],itemIds=[30])[0][0],5)
    def test_legacy_movie_counts_are_unchanged(self):
        self.insert('movies',id=77,sourceId=10,name='Movie')
        self.insert('custom_category_members',profileId=1,mediaType='MOVIE',contextKey='custom:tr',itemId=77,position=0)
        self.assertEqual(self.query('countMembers',type='MOVIE')[0][0],1)
        self.assertEqual(self.query('observeCountsByContexts',type='MOVIE')[0]['count'],1)
    def test_full_span_projection_and_bounded_preview_for_large_catalog(self):
        self.rule(10,'GROUP','id:sports')
        batch=[]
        for cid in range(1000,51000): batch.append((cid,10,21,f'bulk{cid}',f'Channel {cid}','https://example.test',cid,0,0,0))
        self.db.executemany('INSERT INTO channels(id,sourceId,categoryId,remoteId,name,streamUrl,sortOrder,catchup,catchupDays,contentHash) VALUES (?,?,?,?,?,?,?,?,?,?)',batch)
        started=time.perf_counter()
        self.assertEqual(self.query('countMembers')[0][0],50003)
        self.assertEqual(len(self.query('liveSelectionKeys',categoryId=21)),50003)
        self.assertEqual(len(self.query('snapshotChannels',limit=50)),50)
        elapsed=time.perf_counter()-started
        print(f'Production count + complete span keys + bounded 50-row preview: {elapsed:.3f}s for 50,005 channels')

class MigrationTests(ProductionQueryTests):
    # Reuse schema/fixture helpers but run only the migration case below.
    def test_47_to_48_preserves_members_orders_and_rebuilds_unicode_index(self):
        self.db.close();self.db=sqlite3.connect(':memory:');self.db.row_factory=sqlite3.Row;self.schema(47)
        self.insert('profiles',id=1,name='Primary')
        self.insert('sources',id=10,name='Provider',type='XTREAM',url='https://example.test')
        self.insert('categories',id=20,sourceId=10,mediaType='LIVE',remoteId='news',name='News')
        self.channel(30,10,20,'one','İstanbul')
        self.insert('custom_category_members',profileId=1,mediaType='LIVE',contextKey='custom:tr',itemId=30,position=0)
        self.insert('content_order',profileId=1,mediaType='LIVE',contextKey='custom:tr',itemId=30,position=0)
        block=DB_SOURCE.split('val MIGRATION_47_48 =',1)[1].split('val ALL_MIGRATIONS',1)[0]
        for literal in re.findall(r'connection.execSQL\(("(?:\\.|[^"\\])*")\)',block):self.db.execute(json.loads(literal))
        self.assertEqual(self.ids(),[30]);self.assertEqual(self.db.execute('SELECT COUNT(*) FROM content_order').fetchone()[0],1)
        self.assertEqual(self.db.execute("SELECT rowid FROM channels_fts WHERE channels_fts MATCH '\"istanbul\"*'").fetchone()[0],30)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM custom_category_live_rules').fetchone()[0],0)
        self.channel(31,10,20,'two','één')
        self.assertEqual(self.db.execute("SELECT rowid FROM channels_fts WHERE channels_fts MATCH '\"een\"*'").fetchone()[0],31)

if __name__=='__main__':
    suite=unittest.TestSuite()
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(ProductionQueryTests))
    suite.addTest(MigrationTests('test_47_to_48_preserves_members_orders_and_rebuilds_unicode_index'))
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)