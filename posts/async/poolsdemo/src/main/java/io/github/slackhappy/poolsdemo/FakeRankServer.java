package io.github.slackhappy.poolsdemo;


import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import reactor.blockhound.BlockHound;

public class FakeRankServer {
  public static void main(String[] args){
    BlockHound.install(new ArmeriaIntegration());

    ServerBuilder sb = Server.builder();
    sb.http(8081);

    sb.annotatedService(new Object() {
      private QueryExecutor qExecutor = new BlockingQueryExecutor();
      private MetadataClient metadataClient = new MetadataClient();
      @LoggingDecorator()
      @Get("/rank")
      public HttpResponse process(ServiceRequestContext ctx, @Param("q") String query, @Param("user") String user) {
        assert ctx.eventLoop().inEventLoop();
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        ctx.blockingTaskExecutor().execute(() -> {
          future.complete(apply(new QueryRequest(query, user)));
        });
        reurn HttpResponse.from(future);
      }

      public HttpResponse apply (QueryRequest req) {
        System.out.println("yo3");
        CompletableFuture<Map<String, String>> prefetchMetaF = metadataClient.lookupAsync("affinities", req.user);
        CompletableFuture<Map<String, String>> synonymsF;
        if (req.queryIsSynonymsElible()) {
          synonymsF = metadataClient.lookupAsync("synonyms", req.query);
        } else {
          synonymsF = CompletableFuture.completedFuture(Collections.emptyMap());
        }
        CompletableFuture<QueryResponse> qRespF = synonymsF.thenCompose(synonyms -> {
            req.addSynonyms(synonyms);
            return qExecutor.execute(req);
        });
        CompletableFuture<Map<String,String>> postfetchMetaF = qRespF.thenCompose(qResp ->
            metadataClient.lookupAsync("affinities", qResp.documentIds()));

        CompletableFuture.allOf(
            prefetchMetaF,
            qRespF,
            postfetchMetaF).join();

        return Ranker.rerank(prefetchMetaF.join(), postfetchMetaF.join(), qRespF.join());
      }

    });

    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();
    System.out.println("hello");
  }
}
