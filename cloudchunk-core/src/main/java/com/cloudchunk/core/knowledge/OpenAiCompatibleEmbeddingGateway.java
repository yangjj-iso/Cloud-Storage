package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Service
public class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway {
    private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String endpoint,model,apiKey,modelKey;
    private final int dimensions;
    private final boolean enabled,allowHttp;

    public OpenAiCompatibleEmbeddingGateway(ObjectMapper mapper,Environment env) {
        this.mapper=mapper;
        endpoint=env.getProperty("cloudchunk.ai.embedding.endpoint","").strip();
        model=env.getProperty("cloudchunk.ai.embedding.model","").strip();
        apiKey=env.getProperty("cloudchunk.ai.embedding.api-key","");
        dimensions=env.getProperty("cloudchunk.ai.embedding.dimensions",Integer.class,384);
        enabled=Boolean.parseBoolean(env.getProperty("cloudchunk.ai.embedding.enabled","false"));
        allowHttp=Boolean.parseBoolean(env.getProperty("cloudchunk.ai.embedding.allow-http","false"));
        try {
            modelKey=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((endpoint+"\n"+model+"\n"+dimensions).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e){throw new IllegalStateException("model fingerprint unavailable",e);}
    }
    @Override public boolean configured() {
        if(!enabled || endpoint.isBlank() || model.isBlank() || dimensions<8 || dimensions>2048)return false;
        try {
            URI uri=URI.create(endpoint);
            return uri.getHost()!=null && uri.getRawUserInfo()==null && uri.getRawFragment()==null
                && ("https".equalsIgnoreCase(uri.getScheme())
                    || (allowHttp && "http".equalsIgnoreCase(uri.getScheme())));
        } catch(IllegalArgumentException e){return false;}
    }
    @Override public String modelKey(){return modelKey;}
    @Override public int dimensions(){return dimensions;}

    @Override public double[] embed(String text) {
        if(!configured())throw BizException.of(ErrorCode.AI_DISABLED);
        if(text==null || text.isBlank() || text.length()>2000)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"invalid embedding input");
        try {
            String payload=mapper.writeValueAsString(Map.of("model",model,"input",text,
                "dimensions",dimensions,"encoding_format","float"));
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type","application/json")
                .timeout(Duration.ofSeconds(18))
                .POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8));
            if(!apiKey.isBlank())builder.header("Authorization","Bearer "+apiKey);
            HttpResponse<InputStream> res=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=res.body()) {
                byte[] bytes=in.readNBytes(65537);
                if(res.statusCode()!=200 || bytes.length>65536)
                    throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                JsonNode values=mapper.readTree(bytes).path("data").path(0).path("embedding");
                if(!values.isArray() || values.size()!=dimensions)
                    throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                double[] result=new double[dimensions];double norm=0;
                for(int i=0;i<dimensions;i++) {
                    if(!values.get(i).isNumber())throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    result[i]=values.get(i).asDouble();
                    if(!Double.isFinite(result[i]))throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    norm+=result[i]*result[i];
                }
                if(!Double.isFinite(norm) || norm<=0)throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                return result;
            }
        } catch(BizException e){throw e;}
        catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        } catch(Exception e){throw BizException.of(ErrorCode.AI_PROVIDER_UNAVAILABLE);}
    }
}
