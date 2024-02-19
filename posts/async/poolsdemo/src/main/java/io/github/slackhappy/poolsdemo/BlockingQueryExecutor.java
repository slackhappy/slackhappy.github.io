package io.github.slackhappy.poolsdemo;

import java.util.concurrent.CompletableFuture;

public class BlockingQueryExecutor extends QueryExecutor {
  @Override
  public CompletableFuture<QueryResponse> execute(QueryRequest query) {
    try {
      CompletableFuture<QueryResponse> cf = new CompletableFuture();
      Thread.sleep(200);
      cf.complete(new QueryResponse(query));
      return cf;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
