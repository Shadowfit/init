-- SQL Server version of loadtest/measure_admin_selectivity_sweep.sh (TARGET=sessions) -- seeding + sanity gate.
-- Source experiment: Shadowfit docs/decisions/admin-page-scope.md section 4-3/4-5,
--                    loadtest/results/admin-selectivity-sweep-2026-09-10/README.md
--
-- usage:
--   sqlcmd -S "(localdb)\MSSQLLocalDB" -E -b -i setup_seed.sql -v DB=sf_idx_bench SESSIONS=1000000 USERS=200000
--
-- Design notes (ASCII only on purpose: sqlcmd codepage issues with Korean comments)
--  * Same seeding recipe as the MySQL rig: member_id / exercise_id / start_time / status are each drawn from a
--    DIFFERENT-salted MD5 of n, so the four columns are mutually independent (see hash-function-selection.md;
--    CRC32 is NOT used). MySQL: CONV(SUBSTR(MD5(CONCAT('s',n)),1,8),16,10)  ==  first 4 bytes of MD5 as unsigned int.
--    Because MD5 is deterministic and the status salt/thresholds are identical, the status distribution here must equal the
--    MySQL run's (69.97 / 20.10 / 7.95 / 1.98 %) -> used as a cross-engine data-equality check.
--  * status is VARCHAR(12) here (MySQL had ENUM = 1 byte). Row/index sizes therefore differ slightly. Documented in the result.
--  * FKs are omitted, same reason as the original rig (measurement target is the read plan).

SET NOCOUNT ON;
IF DB_ID('$(DB)') IS NOT NULL
BEGIN
    ALTER DATABASE [$(DB)] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE [$(DB)];
END
CREATE DATABASE [$(DB)];
GO
ALTER DATABASE [$(DB)] SET RECOVERY SIMPLE;
GO
USE [$(DB)];
GO
CREATE TABLE dbo.sessions_scale (
    id            BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_sessions_scale PRIMARY KEY CLUSTERED,
    member_id     BIGINT       NOT NULL,
    exercise_id   BIGINT       NOT NULL,
    start_time    DATETIME2(0) NOT NULL,
    end_time      DATETIME2(0) NULL,
    total_reps    INT          NOT NULL CONSTRAINT df_reps   DEFAULT 0,
    avg_sync_rate DECIMAL(5,2) NULL,
    status        VARCHAR(12)  NOT NULL CONSTRAINT df_status DEFAULT 'IN_PROGRESS',
    version       BIGINT       NOT NULL CONSTRAINT df_ver    DEFAULT 0,
    created_at    DATETIME2(0) NOT NULL CONSTRAINT df_cr     DEFAULT SYSDATETIME()
);
CREATE TABLE dbo.bench_meta (k VARCHAR(40) PRIMARY KEY, v VARCHAR(80) NOT NULL);
GO

-- ---- seeding (one batch so @now is shared) -------------------------------------------------
DECLARE @now DATETIME2(0) = SYSDATETIME();
INSERT INTO dbo.bench_meta VALUES ('anchor_now', CONVERT(VARCHAR(30), @now, 126));

;WITH nums AS (
    SELECT TOP ($(SESSIONS)) CAST(ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) - 1 AS BIGINT) AS n
    FROM sys.all_objects a CROSS JOIN sys.all_objects b CROSS JOIN sys.all_objects c
)
INSERT INTO dbo.sessions_scale (member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status)
SELECT 1 + (CONVERT(BIGINT, SUBSTRING(HASHBYTES('MD5', CONCAT('m', n)), 1, 4)) % $(USERS)),
       1 + (CONVERT(BIGINT, SUBSTRING(HASHBYTES('MD5', CONCAT('e', n)), 1, 2)) % 5),
       DATEADD(MINUTE, -CONVERT(INT, CONVERT(BIGINT, SUBSTRING(HASHBYTES('MD5', CONCAT('t', n)), 1, 4)) % 525600), @now),
       NULL, 0, NULL,
       CASE WHEN x.s < 700 THEN 'COMPLETED'
            WHEN x.s < 900 THEN 'IN_PROGRESS'
            WHEN x.s < 980 THEN 'CANCELLED'
            ELSE 'FAILED' END
FROM nums
CROSS APPLY (SELECT CONVERT(BIGINT, SUBSTRING(HASHBYTES('MD5', CONCAT('s', n)), 1, 4)) % 1000 AS s) x
ORDER BY n;
GO

-- ---- sanity gate: seeding must have produced the properties the experiment assumes ---------
-- (the MySQL rig learned this the hard way: sections 4-2 defects #5/#6 -- a seeding that "succeeded" but was dependent.)
DECLARE @N BIGINT = (SELECT COUNT_BIG(*) FROM dbo.sessions_scale);
DECLARE @fail INT = 0;
PRINT '== gate 1: row count';
PRINT CONCAT('rows=', @N, ' expected=$(SESSIONS)');
IF @N <> $(SESSIONS) SET @fail += 1;

PRINT '== gate 2: status distribution vs 70/20/8/2 (|diff| <= 5 sigma)';
;WITH t AS (SELECT * FROM (VALUES ('COMPLETED',0.70),('IN_PROGRESS',0.20),('CANCELLED',0.08),('FAILED',0.02)) v(status,p0)),
d AS (
    SELECT t.status, t.p0, 100.0 * COUNT_BIG(s.id) / @N AS pct
    FROM t LEFT JOIN dbo.sessions_scale s ON s.status = t.status
    GROUP BY t.status, t.p0
)
SELECT status, CONVERT(DECIMAL(7,3), pct) AS pct,
       CASE WHEN ABS(pct - p0*100) > 5 * 100 * SQRT(p0*(1-p0)/@N) THEN 'FAIL' ELSE 'ok' END AS verdict
FROM d ORDER BY pct DESC;
IF EXISTS (SELECT 1 FROM (VALUES ('COMPLETED',0.70),('IN_PROGRESS',0.20),('CANCELLED',0.08),('FAILED',0.02)) t(status,p0)
           CROSS APPLY (SELECT 100.0 * COUNT_BIG(*) / @N AS pct FROM dbo.sessions_scale s WHERE s.status = t.status) q
           WHERE ABS(q.pct - t.p0*100) > 5 * 100 * SQRT(t.p0*(1-t.p0)/@N))
    SET @fail += 1;

PRINT '== gate 3: members with >=3 sessions whose sessions ALL share one status (independent seeding => well below 50%; dependent seeding => ~100%)';
DECLARE @single FLOAT;
;WITH m AS (SELECT member_id, COUNT(*) c, COUNT(DISTINCT status) d FROM dbo.sessions_scale GROUP BY member_id HAVING COUNT(*) >= 3)
SELECT @single = 100.0 * SUM(CASE WHEN d = 1 THEN 1 ELSE 0 END) / COUNT(*) FROM m;
PRINT CONCAT('single_status_member_pct=', CONVERT(DECIMAL(6,2), @single));
IF @single >= 50 SET @fail += 1;

PRINT '== gate 4: mean minutes-ago per status vs global (|diff| <= 5 sd/sqrt(n)); start_time independent of status';
DECLARE @bad4 INT;
;WITH x AS (SELECT status, CONVERT(FLOAT, DATEDIFF(MINUTE, start_time, (SELECT CONVERT(DATETIME2(0), v) FROM dbo.bench_meta WHERE k='anchor_now'))) AS a FROM dbo.sessions_scale),
g AS (SELECT AVG(a) ga, STDEV(a) gsd FROM x),
t AS (SELECT status, AVG(a) aa, COUNT_BIG(*) n FROM x GROUP BY status)
SELECT @bad4 = SUM(CASE WHEN ABS(t.aa - g.ga) > 5 * g.gsd / SQRT(t.n) THEN 1 ELSE 0 END) FROM t CROSS JOIN g;
PRINT CONCAT('statuses_failing=', @bad4);
IF @bad4 > 0 SET @fail += 1;

PRINT '== gate 5: same, bucketed by member_id % 10';
DECLARE @bad5 INT;
;WITH x AS (SELECT member_id % 10 b, CONVERT(FLOAT, DATEDIFF(MINUTE, start_time, (SELECT CONVERT(DATETIME2(0), v) FROM dbo.bench_meta WHERE k='anchor_now'))) a FROM dbo.sessions_scale),
g AS (SELECT AVG(a) ga, STDEV(a) gsd FROM x),
t AS (SELECT b, AVG(a) aa, COUNT_BIG(*) n FROM x GROUP BY b)
SELECT @bad5 = SUM(CASE WHEN ABS(t.aa - g.ga) > 5 * g.gsd / SQRT(t.n) THEN 1 ELSE 0 END) FROM t CROSS JOIN g;
PRINT CONCAT('buckets_failing=', @bad5);
IF @bad5 > 0 SET @fail += 1;

PRINT '== gate 6: duplicate (member_id,start_time) pairs (birthday expectation ~5 at this size; hundreds => seeding is degenerate)';
DECLARE @dup BIGINT = (SELECT COUNT_BIG(*) FROM (SELECT member_id, start_time FROM dbo.sessions_scale GROUP BY member_id, start_time HAVING COUNT(*) > 1) q);
PRINT CONCAT('dup_pairs=', @dup);
IF @dup > 50 SET @fail += 1;

PRINT '== gate 7: start_time span in days (365-day range => ~365)';
DECLARE @span INT = (SELECT DATEDIFF(DAY, MIN(start_time), MAX(start_time)) FROM dbo.sessions_scale);
PRINT CONCAT('span_days=', @span);
IF @span < 360 SET @fail += 1;

PRINT CONCAT('== GATE RESULT: failures=', @fail);
IF @fail > 0 THROW 50001, 'seeding sanity gate failed - do not measure on this data', 1;
GO
