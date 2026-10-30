package org.newpipe.desktop.net;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** OkHttp backed {@link Downloader}, mirrors NewPipe's DownloaderImpl on Android. */
public final class HttpDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private static HttpDownloader instance;
    private final OkHttpClient client;

    private HttpDownloader() {
        client = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized HttpDownloader get() {
        if (instance == null) instance = new HttpDownloader();
        return instance;
    }

    public OkHttpClient client() {
        return client;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        final String method = request.httpMethod();
        final byte[] data = request.dataToSend();
        RequestBody body = null;
        if (data != null) body = RequestBody.create(data);
        else if ("POST".equals(method) || "PUT".equals(method)) body = RequestBody.create(new byte[0]);

        okhttp3.Request.Builder b = new okhttp3.Request.Builder()
                .method(method, body)
                .url(request.url())
                .addHeader("User-Agent", USER_AGENT);

        for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            b.removeHeader(e.getKey());
            for (String v : e.getValue()) b.addHeader(e.getKey(), v);
        }

        try (okhttp3.Response resp = client.newCall(b.build()).execute()) {
            if (resp.code() == 429) throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
            ResponseBody rb = resp.body();
            String text = rb == null ? null : rb.string();
            return new Response(resp.code(), resp.message(), resp.headers().toMultimap(), text,
                    resp.request().url().toString());
        }
    }
}
