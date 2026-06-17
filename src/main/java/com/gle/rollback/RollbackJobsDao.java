package com.gle.rollback;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Запись заданий отката/restore в {@code rollback_jobs} (для аудита и /gl status).
 * Восстановление (restore) теперь работает по фильтрам (как CoreProtect), а не по снимкам,
 * поэтому таблица {@code rollback_job_blocks} больше не используется.
 */
public final class RollbackJobsDao {

    private RollbackJobsDao() {}

    public static long createJob(Connection conn, String jobType, @Nullable Long parentJobId,
                                 UUID executor, String executorName, RollbackFilter f) throws SQLException {
        String sql = "INSERT INTO rollback_jobs(job_type, parent_job_id, started_at, executor_uuid, executor_name, " +
                "filter_time_from, filter_time_to, filter_player_uuid, filter_player_name, " +
                "filter_radius, filter_cx, filter_cy, filter_cz, filter_level, " +
                "filter_include_blocks, filter_include_items, status) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, jobType);
            if (parentJobId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, parentJobId);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, executor.toString());
            ps.setString(5, executorName);
            ps.setLong(6, f.timeFrom);
            ps.setLong(7, f.timeTo);
            ps.setNull(8, java.sql.Types.VARCHAR);
            if (f.playerName == null) ps.setNull(9, java.sql.Types.VARCHAR); else ps.setString(9, f.playerName);
            ps.setDouble(10, f.radius);
            ps.setInt(11, f.centerX); ps.setInt(12, f.centerY); ps.setInt(13, f.centerZ);
            ps.setString(14, f.levelName);
            ps.setInt(15, f.includeBlocks ? 1 : 0);
            ps.setInt(16, f.includeItems ? 1 : 0);
            ps.setString(17, "running");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    public static void finishJob(Connection conn, long jobId, String status,
                                 int affectedBlocks, int affectedContainers, int failed) throws SQLException {
        String sql = "UPDATE rollback_jobs SET status=?, completed_at=?, affected_blocks=?, " +
                "affected_containers=?, failed_count=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, affectedBlocks);
            ps.setInt(4, affectedContainers);
            ps.setInt(5, failed);
            ps.setLong(6, jobId);
            ps.executeUpdate();
        }
    }
}
