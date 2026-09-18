SET @sid = 108198;
SET @anchor = (SELECT MIN(created_at) FROM pose_data WHERE session_id = @sid);
WITH pt AS (
  SELECT id, timestamp_sec, rep_number, smoothed_knee_angle, sync_rate,
         joint_coordinates->'$[23].x' AS lhx, joint_coordinates->'$[23].y' AS lhy, joint_coordinates->'$[23].z' AS lhz,
         joint_coordinates->'$[25].x' AS lkx, joint_coordinates->'$[25].y' AS lky, joint_coordinates->'$[25].z' AS lkz,
         joint_coordinates->'$[27].x' AS lax, joint_coordinates->'$[27].y' AS lay, joint_coordinates->'$[27].z' AS laz,
         joint_coordinates->'$[24].x' AS rhx, joint_coordinates->'$[24].y' AS rhy, joint_coordinates->'$[24].z' AS rhz,
         joint_coordinates->'$[26].x' AS rkx, joint_coordinates->'$[26].y' AS rky, joint_coordinates->'$[26].z' AS rkz,
         joint_coordinates->'$[28].x' AS rax, joint_coordinates->'$[28].y' AS ray, joint_coordinates->'$[28].z' AS raz,
         joint_coordinates->'$[25].visibility' AS lkv, joint_coordinates->'$[26].visibility' AS rkv
  FROM pose_data
  WHERE session_id = @sid AND created_at = @anchor
),
ang AS (
  SELECT id, timestamp_sec, rep_number, smoothed_knee_angle, sync_rate, lkv, rkv,
    DEGREES(ACOS(GREATEST(-1, LEAST(1, ((lhx-lkx)*(lax-lkx)+(lhy-lky)*(lay-lky)) / (SQRT(POW(lhx-lkx,2)+POW(lhy-lky,2))*SQRT(POW(lax-lkx,2)+POW(lay-lky,2))+1e-8))))) AS l2d,
    DEGREES(ACOS(GREATEST(-1, LEAST(1, ((lhx-lkx)*(lax-lkx)+(lhy-lky)*(lay-lky)+(lhz-lkz)*(laz-lkz)) / (SQRT(POW(lhx-lkx,2)+POW(lhy-lky,2)+POW(lhz-lkz,2))*SQRT(POW(lax-lkx,2)+POW(lay-lky,2)+POW(laz-lkz,2))+1e-8))))) AS l3d,
    DEGREES(ACOS(GREATEST(-1, LEAST(1, ((rhx-rkx)*(rax-rkx)+(rhy-rky)*(ray-rky)) / (SQRT(POW(rhx-rkx,2)+POW(rhy-rky,2))*SQRT(POW(rax-rkx,2)+POW(ray-rky,2))+1e-8))))) AS r2d,
    DEGREES(ACOS(GREATEST(-1, LEAST(1, ((rhx-rkx)*(rax-rkx)+(rhy-rky)*(ray-rky)+(rhz-rkz)*(raz-rkz)) / (SQRT(POW(rhx-rkx,2)+POW(rhy-rky,2)+POW(rhz-rkz,2))*SQRT(POW(rax-rkx,2)+POW(ray-rky,2)+POW(raz-rkz,2))+1e-8))))) AS r3d
  FROM pt
)
SELECT id, timestamp_sec AS t, rep_number AS rep, smoothed_knee_angle AS col_stored,
       ROUND((l3d+r3d)/2,2) AS avg3d, ROUND((l2d+r2d)/2,2) AS avg2d,
       ROUND(l3d,1) AS l3d, ROUND(l2d,1) AS l2d, ROUND(r3d,1) AS r3d, ROUND(r2d,1) AS r2d,
       ROUND(ABS(l3d-r3d),1) AS lr_asym3d, ROUND(lkv,2) AS lkv, ROUND(rkv,2) AS rkv
FROM ang ORDER BY timestamp_sec;
