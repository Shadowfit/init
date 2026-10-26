SET @anchor = (SELECT MIN(created_at) FROM pose_data WHERE session_id = 108198);
EXPLAIN FORMAT=TRADITIONAL SELECT id, joint_coordinates->'$[25].x' FROM pose_data WHERE session_id = 108198\G
EXPLAIN FORMAT=TRADITIONAL SELECT id, joint_coordinates->'$[25].x' FROM pose_data WHERE session_id = 108198 AND created_at = @anchor\G
-- JSON_TABLE 로 같은 3점을 꺼내는 형태
EXPLAIN FORMAT=TRADITIONAL SELECT p.id, jt.idx, jt.x FROM pose_data p CROSS JOIN JSON_TABLE(p.joint_coordinates, '$[*]' COLUMNS (idx INT PATH '$.index', x DOUBLE PATH '$.x')) jt WHERE p.session_id = 108198 AND p.created_at = @anchor AND jt.idx IN (23,25,27)\G
SELECT COUNT(*) FROM information_schema.partitions WHERE table_schema='shadowfit' AND table_name='pose_data';
