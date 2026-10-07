"""Exercise the actual additive Kotlin migration SQL against SQLite, including a v1 upgrade."""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] / "app/src/main/java/com/jiacimu/lulu/data/SharedExperienceTimeline.kt").read_text()

class TimelineMigrationTest(unittest.TestCase):
    def test_existing_records_and_tombstones_survive(self):
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE timeline_events (id TEXT PRIMARY KEY NOT NULL, character_id TEXT NOT NULL, channel TEXT NOT NULL, speaker TEXT NOT NULL, content TEXT NOT NULL, occurred_at INTEGER NOT NULL)")
        db.execute("INSERT INTO timeline_events VALUES ('old','a','电话','用户','喜欢短回复',123)")
        db.execute("CREATE TABLE deleted_timeline_events (event_id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
        db.execute("INSERT INTO deleted_timeline_events VALUES ('deleted',124)")
        statements = re.findall(r'db.execSQL\("(ALTER TABLE timeline_events [^"]+)"\)', SOURCE)
        self.assertEqual(len(statements), 5)
        for sql in statements:
            db.execute(sql)
        self.assertEqual(db.execute("SELECT id,character_id,content,occurred_at,revision,evidence_kind FROM timeline_events").fetchone(),
                         ('old','a','喜欢短回复',123,1,'Legacy'))
        self.assertEqual(db.execute("SELECT event_id FROM deleted_timeline_events").fetchone(), ('deleted',))
        db.execute("UPDATE timeline_events SET content='更正',revision=revision+1 WHERE id='old' AND character_id='b'")
        self.assertEqual(db.execute("SELECT content FROM timeline_events").fetchone(), ('喜欢短回复',))

if __name__ == '__main__':
    unittest.main()
