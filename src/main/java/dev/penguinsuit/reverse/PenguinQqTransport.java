package dev.penguinsuit.reverse;

import com.google.gson.*;
import com.huhobot.penguin.qq.QQClient;
import dev.penguinsuit.mixin.QQClientAccess;
import dev.penguinsuit.media.MediaFetcher;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/** Runs on Penguin's send queue, never on the Minecraft server thread. */
public final class PenguinQqTransport implements QqMediaApi.Transport {
    private static volatile HttpClient shared;
    private static int connectSeconds;
    private static synchronized HttpClient http(){int seconds=dev.penguinsuit.config.RuntimeSettings.get().network.qqConnectTimeoutSeconds;if(shared==null||seconds!=connectSeconds){var previous=shared;shared=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(seconds)).followRedirects(HttpClient.Redirect.NEVER).build();connectSeconds=seconds;if(previous!=null)previous.shutdown();}return shared;}

    private final Supplier<String> token;
    private final Runnable invalidateToken;
    private final URI base;
    private final String appId;
    public PenguinQqTransport(QQClient client, String appId) {
        this(client::getAccessTokenSync, () -> ((QQClientAccess)(Object) client).penguinsuit$invalidateToken(),
                appId, URI.create("https://api.bot.qq.com"));
    }
    // Package-private endpoint injection is for loopback HTTP tests; no configurable production URL.
    PenguinQqTransport(Supplier<String> token, Runnable invalidateToken, String appId, URI base) {
        this.token = token; this.invalidateToken = invalidateToken; this.appId = appId; this.base = base;
    }
    @Override public QqMediaApi.Response post(String path, JsonObject body) throws Exception {
        if (!path.matches("/v2/groups/[A-Za-z0-9%+_.-]+/messages|/v2/media-images/(?:prepare|complete)"))
            throw new QqMediaApi.Failure("invalid_target", 0, 0);
        return request(path,body);
    }
    @Override public QqMediaApi.Response delete(String path) throws Exception {
        if(!path.matches("/v2/media-images/[A-Za-z0-9_-]{1,256}/delete"))
            throw new QqMediaApi.Failure("invalid_target",0,0);
        return request(path,null);
    }
    @Override public String putImage(String signedUrl,String contentType,byte[] bytes) throws Exception {
        URI uri;
        try { uri=URI.create(signedUrl); } catch(RuntimeException e) { throw new QqMediaApi.Failure("invalid_upload_target",0,0); }
        boolean testEndpoint="http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost())
                && base.getScheme().equals(uri.getScheme()) && base.getAuthority().equals(uri.getAuthority());
        if(!testEndpoint) {
            validateUploadUri(uri);
            for(var ip:InetAddress.getAllByName(uri.getHost()))
                if(!MediaFetcher.isPublic(ip)) throw new QqMediaApi.Failure("blocked_upload_address",0,0);
        }
        if(!contentType.matches("image/(?:png|jpeg|gif)")) throw new QqMediaApi.Failure("invalid_content_type",0,0);
        // Signed COS requests receive image bytes and MIME only, never QQ Authorization or App ID.
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(dev.penguinsuit.config.RuntimeSettings.get().network.qqRequestTimeoutSeconds)).header("Content-Type",contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        var response=http().send(request,HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()<200||response.statusCode()>=300) throw new QqMediaApi.Failure("upload_put",response.statusCode(),0);
        return response.headers().firstValue("ETag").orElse("");
    }
    static void validateUploadUri(URI uri) throws QqMediaApi.Failure {
        String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme())||host==null
                ||!host.toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9-]+\\.cos\\.[a-z0-9-]+\\.myqcloud\\.com")
                ||uri.getUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))
            throw new QqMediaApi.Failure("invalid_upload_target",0,0);
    }
    private QqMediaApi.Response request(String path, JsonObject body) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            var builder = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofSeconds(dev.penguinsuit.config.RuntimeSettings.get().network.qqRequestTimeoutSeconds)).header("Authorization", "QQBot " + token.get())
                    .header("X-Union-Appid", appId).header("Content-Type", "application/json; charset=utf-8");
            var request = (body == null ? builder.DELETE() : builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))).build();
            // Request timeout bounds download as well; QQ responses are small JSON objects.
            var response = http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 && attempt == 0) {
                invalidateToken.run();
                continue;
            }
            if (response.body().length() > 262144) throw new QqMediaApi.Failure("response_too_large", response.statusCode(), 0);
            JsonObject parsed;
            try { parsed = body == null && response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject(); }
            catch (RuntimeException e) { throw new QqMediaApi.Failure("invalid_response", response.statusCode(), 0); }
            return new QqMediaApi.Response(response.statusCode(), parsed);
        }
        throw new QqMediaApi.Failure("authentication", 401, 0);
    }
}
