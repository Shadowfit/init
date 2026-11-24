import json
import sys

BS = chr(92)

d = json.load(open(sys.argv[1], encoding="utf-8"))
s = d["poseData"][0]["jointCoordinates"]
obj = json.loads(s)


def lit(o):
    text = json.dumps(o, separators=(",", ":"))
    return "'" + text.replace(BS, BS + BS).replace("'", "''") + "'"


def padded(target):
    o = {"pose": obj, "pad": ""}
    base = len(json.dumps(o, separators=(",", ":")))
    o["pad"] = "x" * max(0, target - base)
    return o


variants = [
    ("real_1809", obj),
    ("pad_2300", padded(2300)),
    ("pad_4000", padded(4000)),
    ("pad_7000", padded(7000)),
    ("pad_8200", padded(8200)),
    ("pad_9000", padded(9000)),
    ("pad_12000", padded(12000)),
]

out = [
    "SET SESSION cte_max_recursion_depth=100000;",
    "CREATE DATABASE IF NOT EXISTS t; USE t;",
]
for name, o in variants:
    out.append(
        f"""DROP TABLE IF EXISTS t_{name};
CREATE TABLE t_{name} (
  id BIGINT AUTO_INCREMENT, session_id BIGINT NOT NULL, rep_number INT NOT NULL DEFAULT 0,
  timestamp_sec DECIMAL(10,3) NOT NULL, joint_coordinates JSON NOT NULL, sync_rate DECIMAL(5,2) NOT NULL,
  smoothed_knee_angle DECIMAL(5,2) NOT NULL DEFAULT 0.00, feedback_message VARCHAR(500),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id, created_at),
  INDEX idx_session_timestamp (session_id, timestamp_sec)) ENGINE=InnoDB;
INSERT INTO t_{name} (session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<2000)
SELECT 1+(i DIV 750), 1+(i DIV 30), i*0.033, {lit(o)}, 45.00, 90.00, '' FROM n;
ANALYZE TABLE t_{name};"""
    )

out.append(
    "SELECT VERSION() v, @@innodb_page_size page_size, @@innodb_default_row_format default_row_format;"
)
for name, _ in variants:
    sp = f"(SELECT SPACE FROM information_schema.INNODB_TABLESPACES WHERE NAME='t/t_{name}')"
    st = (
        "(SELECT stat_value FROM mysql.innodb_index_stats WHERE database_name='t' "
        f"AND table_name='t_{name}' AND index_name='PRIMARY' AND stat_name='n_leaf_pages')"
    )
    out.append(
        f"""SELECT '{name}' variant,
 (SELECT ROW_FORMAT FROM information_schema.tables WHERE table_schema='t' AND table_name='t_{name}') row_format,
 (SELECT ROUND(AVG(LENGTH(joint_coordinates))) FROM t_{name}) json_text_len,
 (SELECT ROUND(AVG(JSON_STORAGE_SIZE(joint_coordinates))) FROM t_{name}) json_storage,
 {st} pk_leaf_pages,
 ROUND(2000/{st},1) rows_per_leaf,
 (SELECT COUNT(*) FROM information_schema.INNODB_BUFFER_PAGE bp WHERE bp.SPACE={sp} AND bp.PAGE_TYPE='INDEX') index_pages,
 (SELECT COUNT(*) FROM information_schema.INNODB_BUFFER_PAGE bp WHERE bp.SPACE={sp} AND bp.PAGE_TYPE LIKE '%LOB%') lob_pages;"""
    )
    out.append(
        f"SELECT '{name}' variant, PAGE_TYPE, COUNT(*) pages FROM information_schema.INNODB_BUFFER_PAGE "
        f"WHERE SPACE={sp} GROUP BY PAGE_TYPE ORDER BY pages DESC;"
    )

open(sys.argv[2], "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("sample json text length:", len(s))
