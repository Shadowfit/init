-- Selectivity sweep on SQL Server: status (4) x window (4) x kind (list/count) x arm (A0/A1/B).
-- Mirrors loadtest/measure_admin_selectivity_sweep.sh (TARGET=sessions):
--   arms rotate per block (6 orders) so "arm effect" and "run-order effect" are separable; block 0 is a discarded warm-up;
--   30 samples per cell = 6 blocks x 5 reps.
-- usage:
--   sqlcmd -S "(localdb)\MSSQLLocalDB" -E -b -i run_sweep.sql -v DB=sf_idx_bench BLOCKS=6 REPS=5
--
-- Timing: server-side execution time in microseconds from sys.dm_exec_query_stats.last_elapsed_time, read right after each EXEC
-- (single connection, so 'last' is the execution just done). SYSDATETIME() is NOT usable: its resolution on this Windows box is
-- the 15.6 ms system tick, which is far coarser than the sub-millisecond list queries (found in the smoke run: 213/256 samples = 0).
-- Results go to output variables, so no result-set transfer is timed (same intent as MySQL EXPLAIN ANALYZE root actual time
-- instead of client wall-clock). Per (arm, cell) the statement is executed once UNTIMED first (compile + warm cache), then REPS
-- times with the cached plan, so compile time is excluded (MySQL EXPLAIN ANALYZE excludes parse/optimize as well).
-- last_logical_reads is recorded too: pages touched, the SQL Server analogue of the MySQL handler counters.
-- The anchor for "last N days" is the seeding timestamp, embedded as a literal -> plan is chosen for that literal.

USE [$(DB)];
SET NOCOUNT ON;
GO
IF OBJECT_ID('dbo.apply_arm') IS NOT NULL DROP PROCEDURE dbo.apply_arm;
GO
CREATE PROCEDURE dbo.apply_arm @arm VARCHAR(2) AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('dbo.sessions_scale') AND name = 'ix_m_s_st')   DROP INDEX ix_m_s_st   ON dbo.sessions_scale;
    IF EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('dbo.sessions_scale') AND name = 'ix_m_e_s_st') DROP INDEX ix_m_e_s_st ON dbo.sessions_scale;
    IF EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('dbo.sessions_scale') AND name = 'ix_st_m')     DROP INDEX ix_st_m     ON dbo.sessions_scale;
    IF EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('dbo.sessions_scale') AND name = 'ix_s_st')     DROP INDEX ix_s_st     ON dbo.sessions_scale;
    -- A0: the two member-leading indexes the table already had before the admin index existed
    CREATE INDEX ix_m_s_st   ON dbo.sessions_scale (member_id, status, start_time);
    CREATE INDEX ix_m_e_s_st ON dbo.sessions_scale (member_id, exercise_id, status, start_time);
    -- A1: A0 + (start_time, member_id)  -- "today without the admin index"
    IF @arm IN ('A1', 'B') CREATE INDEX ix_st_m ON dbo.sessions_scale (start_time, member_id);
    -- B: A1 + (status, start_time)      -- the admin index
    IF @arm = 'B' CREATE INDEX ix_s_st ON dbo.sessions_scale (status, start_time);
    DBCC FREEPROCCACHE WITH NO_INFOMSGS;
END
GO

IF OBJECT_ID('dbo.bench_raw') IS NOT NULL DROP TABLE dbo.bench_raw;
CREATE TABLE dbo.bench_raw (blk INT, arm VARCHAR(2), status VARCHAR(12), window_d INT, kind VARCHAR(5), rep INT, us BIGINT NULL, reads BIGINT NULL);
GO

DECLARE @anchor DATETIME2(0) = (SELECT CONVERT(DATETIME2(0), v) FROM dbo.bench_meta WHERE k = 'anchor_now');
DECLARE @orders TABLE (i INT, a1 VARCHAR(2), a2 VARCHAR(2), a3 VARCHAR(2));
INSERT @orders VALUES (1,'A0','A1','B'),(2,'B','A1','A0'),(3,'A1','B','A0'),(4,'A0','B','A1'),(5,'B','A0','A1'),(6,'A1','A0','B');

DECLARE @cells TABLE (ci INT IDENTITY(1,1), st VARCHAR(12), w INT, kind VARCHAR(5));
INSERT @cells (st, w, kind)
SELECT s.st, w.w, k.kind
FROM (VALUES ('FAILED'),('CANCELLED'),('IN_PROGRESS'),('COMPLETED')) s(st)
CROSS JOIN (VALUES (1),(7),(30),(0)) w(w)
CROSS JOIN (VALUES ('list'),('count')) k(kind);

DECLARE @blocks INT = $(BLOCKS), @reps INT = $(REPS), @ncell INT = (SELECT COUNT(*) FROM @cells);
DECLARE @blk INT = 0, @oi INT, @k INT, @arm VARCHAR(2), @ci INT, @st VARCHAR(12), @w INT, @kind VARCHAR(5);
DECLARE @r INT, @sql NVARCHAR(1000), @cond NVARCHAR(300), @us BIGINT, @reads BIGINT, @msg NVARCHAR(200);
DECLARE @o1 BIGINT, @o2 BIGINT, @o3 DATETIME2(0), @o4 VARCHAR(12), @c BIGINT;

WHILE @blk <= @blocks
BEGIN
    SET @oi = CASE WHEN @blk = 0 THEN 1 ELSE ((@blk - 1) % 6) + 1 END;
    SET @k = 1;
    WHILE @k <= 3
    BEGIN
        SET @arm = (SELECT CASE @k WHEN 1 THEN a1 WHEN 2 THEN a2 ELSE a3 END FROM @orders WHERE i = @oi);
        EXEC dbo.apply_arm @arm;
        SET @msg = CONCAT(CONVERT(VARCHAR(19), SYSDATETIME(), 120), ' block ', @blk, '/', @blocks, ' arm ', @arm);
        RAISERROR(@msg, 0, 1) WITH NOWAIT;

        SET @ci = 1;
        WHILE @ci <= @ncell
        BEGIN
            SELECT @st = st, @w = w, @kind = kind FROM @cells WHERE ci = @ci;
            SET @cond = CONCAT(N' FROM dbo.sessions_scale WHERE status = ''', @st, '''');
            IF @w > 0
                SET @cond = CONCAT(@cond, N' AND start_time >= ''', CONVERT(VARCHAR(19), DATEADD(DAY, -@w, @anchor), 126), '''');
            IF @kind = 'list'
                SET @sql = CONCAT(N'SELECT TOP 20 @o1 = id, @o2 = member_id, @o3 = start_time, @o4 = status', @cond, N' ORDER BY start_time DESC');
            ELSE
                SET @sql = CONCAT(N'SELECT @c = COUNT_BIG(*)', @cond);

            SET @r = 0;                       -- rep 0 = untimed warm run (compile + cache)
            WHILE @r <= @reps
            BEGIN
                IF @kind = 'list'
                    EXEC sp_executesql @sql, N'@o1 BIGINT OUTPUT, @o2 BIGINT OUTPUT, @o3 DATETIME2(0) OUTPUT, @o4 VARCHAR(12) OUTPUT',
                         @o1 OUTPUT, @o2 OUTPUT, @o3 OUTPUT, @o4 OUTPUT;
                ELSE
                    EXEC sp_executesql @sql, N'@c BIGINT OUTPUT', @c OUTPUT;
                IF @r > 0
                BEGIN
                    SET @us = NULL; SET @reads = NULL;
                    SELECT @us = qs.last_elapsed_time, @reads = qs.last_logical_reads
                    FROM sys.dm_exec_query_stats qs CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) t
                    WHERE CHARINDEX(@sql, t.text) > 0;
                    INSERT dbo.bench_raw VALUES (@blk, @arm, @st, @w, @kind, @r, @us, @reads);
                END
                SET @r += 1;
            END
            SET @ci += 1;
        END
        SET @k += 1;
    END
    SET @blk += 1;
END
GO

-- Was any measured statement auto-parameterized (plan reused across different literals)? 'Prepared' rows would mean yes.
SELECT cp.objtype, COUNT(*) AS plans
FROM sys.dm_exec_cached_plans cp CROSS APPLY sys.dm_exec_sql_text(cp.plan_handle) t
WHERE t.text LIKE '%sessions_scale%' AND t.text NOT LIKE '%dm_exec%'
GROUP BY cp.objtype;
SELECT COUNT(*) AS rows_total, SUM(CASE WHEN us IS NULL THEN 1 ELSE 0 END) AS rows_without_stats FROM dbo.bench_raw;
GO
