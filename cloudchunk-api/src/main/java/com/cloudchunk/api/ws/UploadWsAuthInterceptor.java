package com.cloudchunk.api.ws;

import com.cloudchunk.core.auth.service.AuthService;
import com.cloudchunk.core.file.service.FileMetaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@Component
public class UploadWsAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_FILE_ID = "fileId";
    public static final String ATTR_USER_ID = "userId";

    private static final UriTemplate URI_TEMPLATE = new UriTemplate("/ws/upload/{fileId}");

    private final AuthService authService;
    private final FileMetaService fileMetaService;

    public UploadWsAuthInterceptor(AuthService authService, FileMetaService fileMetaService) {
        this.authService = authService;
        this.fileMetaService = fileMetaService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String fileId = extractFileId(request.getURI());
        if (fileId == null || fileId.isBlank()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        Optional<Long> userId = authService.resolveToken(resolveToken(request.getHeaders()));
        if (userId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!fileMetaService.hasReference(fileId, userId.get())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_FILE_ID, fileId);
        attributes.put(ATTR_USER_ID, userId.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private String resolveToken(HttpHeaders headers) {
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return tokenFromProtocol(headers.getFirst("Sec-WebSocket-Protocol"));
    }

    private String tokenFromProtocol(String protocolHeader) {
        if (protocolHeader == null || protocolHeader.isBlank()) {
            return null;
        }
        for (String part : protocolHeader.split(",")) {
            String p = part.trim();
            if (p.matches("[0-9a-fA-F]{64}")) {
                return p;
            }
        }
        return null;
    }

    private String extractFileId(URI uri) {
        if (uri == null) {
            return null;
        }
        Map<String, String> vars = URI_TEMPLATE.match(uri.getPath());
        return vars.get("fileId");
    }
}
