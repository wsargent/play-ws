/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */

package play.libs.ws.ahc;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

import javax.cache.Cache;
import javax.inject.Inject;

import akka.stream.Materializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.typesafe.sslconfig.ssl.SystemConfiguration;
import com.typesafe.sslconfig.ssl.debug.DebugConfiguration;
import org.slf4j.LoggerFactory;
import play.api.libs.json.jackson.PlayJsonModule$;
import play.api.libs.ws.ahc.AhcConfigBuilder;
import play.api.libs.ws.ahc.AhcLoggerFactory;
import play.api.libs.ws.ahc.AhcWSClientConfig;
import play.api.libs.ws.ahc.Streamed;
import play.api.libs.ws.ahc.cache.CachingAsyncHttpClient;
import play.api.libs.ws.ahc.cache.EffectiveURIKey;
import play.api.libs.ws.ahc.cache.ResponseEntry;
import play.libs.ws.StandaloneWSClient;
import play.libs.ws.StandaloneWSResponse;
import play.libs.ws.StreamedResponse;
import play.shaded.ahc.org.asynchttpclient.AsyncCompletionHandler;
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient;
import play.shaded.ahc.org.asynchttpclient.DefaultAsyncHttpClient;
import play.shaded.ahc.org.asynchttpclient.DefaultAsyncHttpClientConfig;
import play.shaded.ahc.org.asynchttpclient.Request;
import play.shaded.ahc.org.asynchttpclient.Response;
import scala.compat.java8.FutureConverters;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Promise;

/**
 * A WS asyncHttpClient backed by an AsyncHttpClient instance.
 */
public class StandaloneAhcWSClient implements StandaloneWSClient {

    static ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper()
        .registerModule(PlayJsonModule$.MODULE$)
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

    private final AsyncHttpClient asyncHttpClient;
    private final Materializer materializer;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new client.
     *
     * @param asyncHttpClient the underlying AsyncHttpClient
     * @param materializer the Materializer to use for streams
     * @param mapper the ObjectMapper to use for serializing JSON objects
     */
    @Inject
    public StandaloneAhcWSClient(AsyncHttpClient asyncHttpClient, Materializer materializer, ObjectMapper mapper) {
        this.asyncHttpClient = asyncHttpClient;
        this.materializer = materializer;
        this.objectMapper = mapper;
    }

    /**
     * Creates a new client with the default Jackson ObjectMapper.
     *
     * @param asyncHttpClient the underlying AsyncHttpClient
     * @param materializer the Materializer to use for streams
     */
    public StandaloneAhcWSClient(AsyncHttpClient asyncHttpClient, Materializer materializer) {
        this(asyncHttpClient, materializer, DEFAULT_OBJECT_MAPPER);
    }

    @Override
    public Object getUnderlying() {
        return asyncHttpClient;
    }

    @Override
    public StandaloneAhcWSRequest url(String url) {
        return new StandaloneAhcWSRequest(this, url, materializer, objectMapper);
    }

    @Override
    public void close() throws IOException {
        asyncHttpClient.close();
    }

    CompletionStage<StandaloneWSResponse> execute(Request request) {
        final Promise<StandaloneWSResponse> scalaPromise = scala.concurrent.Promise$.MODULE$.apply();

        AsyncCompletionHandler<Response> handler = new AsyncCompletionHandler<Response>() {
            @Override
            public Response onCompleted(Response response) {
                StandaloneAhcWSResponse r = new StandaloneAhcWSResponse(response, objectMapper);
                scalaPromise.success(r);
                return response;
            }

            @Override
            public void onThrowable(Throwable t) {
                scalaPromise.failure(t);
            }
        };

        try {
            asyncHttpClient.executeRequest(request, handler);
        } catch (RuntimeException exception) {
            scalaPromise.failure(exception);
        }
        Future<StandaloneWSResponse> future = scalaPromise.future();
        return FutureConverters.toJava(future);
    }

    ExecutionContext executionContext() {
        return materializer.executionContext();
    }

    CompletionStage<? extends StreamedResponse> executeStream(Request request) {
        return StreamedResponse.from(Streamed.execute(asyncHttpClient, request, executionContext()));
    }

    /**
     * A convenience method for creating a StandaloneAhcWSClient from configuration.
     *
     * @param ahcWSClientConfig the configuration object
     * @param cache if not null, will be used for HTTP response caching.
     * @param materializer an akka materializer
     * @return a fully configured StandaloneAhcWSClient instance.
     */
    public static StandaloneAhcWSClient create(AhcWSClientConfig ahcWSClientConfig, Cache<EffectiveURIKey, ResponseEntry> cache, Materializer materializer) {
        AhcLoggerFactory loggerFactory = new AhcLoggerFactory(LoggerFactory.getILoggerFactory());

        // Set up debugging configuration
        if (ahcWSClientConfig.wsClientConfig().ssl().debug().enabled()) {
            new DebugConfiguration(loggerFactory).configure(ahcWSClientConfig.wsClientConfig().ssl().debug());
        }

        // Configure the AsyncHttpClientConfig.Builder from the application.conf file...
        final AhcConfigBuilder builder = new AhcConfigBuilder(ahcWSClientConfig);
        final DefaultAsyncHttpClientConfig.Builder ahcBuilder = builder.configure();

        // Set up SSL configuration settings that are global..
        new SystemConfiguration(loggerFactory).configure(ahcWSClientConfig.wsClientConfig().ssl());

        // Create the AHC asyncHttpClient
        DefaultAsyncHttpClientConfig asyncHttpClientConfig = ahcBuilder.build();
        DefaultAsyncHttpClient defaultAsyncHttpClient = new DefaultAsyncHttpClient(asyncHttpClientConfig);

        AsyncHttpClient ahcClient;
        if (cache != null) {
            ahcClient = new CachingAsyncHttpClient(defaultAsyncHttpClient, cache);
        } else {
            ahcClient = defaultAsyncHttpClient;
        }
        return new StandaloneAhcWSClient(ahcClient, materializer);
    }

}
