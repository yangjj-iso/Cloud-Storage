package com.cloudchunk.core.audit.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * An authenticated user's audit events derived from the existing op_log.
 * No request body, file name, model prompt, or source excerpt is saved or returned.
 * The recording filter is best-effort; this is NOT a tamper-proof financial ledger.
 */
@Service
public class DriveOperationHistory {
    public record Event(long id,String operation,boolean success,LocalDateTime createdAt,long elapsedMs) {}

    private static final List<String> OPERATIONS=List.of(
        "DRIVE_MKDIR","DRIVE_RENAME","DRIVE_MOVE","DRIVE_RECYCLE","DRIVE_RESTORE",
        "DRIVE_PURGE","SHARE_SAVE","AI_RENAME_SUGGEST","AI_RENAME_APPLY",
        "AI_MOVE_SUGGEST","AI_MOVE_APPLY","AI_MOVE_UNDO"
    );
    private final JdbcTemplate jdbc;
    public DriveOperationHistory(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** Stable bounded labels; arbitrary or dynamic paths never become metric/audit types. */
    public static String classify(String path,String method) {
        String operation=method==null?"":method.toUpperCase(Locale.ROOT);
        if(path!=null) {
            if("POST".equals(operation)) {
                if(path.equals("/api/v1/drive/mkdir"))return "DRIVE_MKDIR";
                if(path.equals("/api/v1/drive/rename"))return "DRIVE_RENAME";
                if(path.equals("/api/v1/drive/move"))return "DRIVE_MOVE";
                if(path.matches("^/api/v1/recycle/[0-9]+/restore$"))return "DRIVE_RESTORE";
                if(path.matches("^/api/v1/recycle/[0-9]+/hard-delete$"))return "DRIVE_PURGE";
                if(path.equals("/api/v1/knowledge/assistant/rename/suggest"))return "AI_RENAME_SUGGEST";
                if(path.matches("^/api/v1/knowledge/assistant/rename/[a-f0-9-]{36}/apply$"))
                    return "AI_RENAME_APPLY";
                if(path.equals("/api/v1/knowledge/assistant/move/suggest") ||
                   path.equals("/api/v1/knowledge/assistant/move/batch/suggest"))
                    return "AI_MOVE_SUGGEST";
                if(path.matches("^/api/v1/knowledge/assistant/move/[a-f0-9-]{36}/apply$"))
                    return "AI_MOVE_APPLY";
                if(path.matches("^/api/v1/knowledge/assistant/move/[a-f0-9-]{36}/undo$"))
                    return "AI_MOVE_UNDO";
                if(path.matches("^/api/v1/share/[a-zA-Z0-9_-]+/save$"))return "SHARE_SAVE";
            }
            if("DELETE".equals(operation)) {
                if(path.matches("^/api/v1/drive/[0-9]+$"))return "DRIVE_RECYCLE";
            }
        }
        String area=path==null?"API":path.replaceFirst("^/api/v1/","").split("/",2)[0];
        area=area.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
        if(area.isBlank())area="API";
        return area+"_"+operation;
    }

    public List<Event> recent(long userId) {
        if(userId<=0)throw new IllegalArgumentException("userId required");
        String placeholders=String.join(",",java.util.Collections.nCopies(OPERATIONS.size(),"?"));
        String sql="SELECT id,op_type,result,created_at,cost_ms FROM op_log "
            +"WHERE user_id=? AND op_type IN ("+placeholders+") "
            +"ORDER BY created_at DESC,id DESC LIMIT 30";
        Object[] args=new Object[OPERATIONS.size()+1];
        args[0]=userId;
        for(int i=0;i<OPERATIONS.size();i++)args[i+1]=OPERATIONS.get(i);
        return jdbc.query(sql,(rs,n)->new Event(rs.getLong(1),rs.getString(2),
            rs.getInt(3)==1,rs.getTimestamp(4).toLocalDateTime(),rs.getLong(5)),args);
    }
}
