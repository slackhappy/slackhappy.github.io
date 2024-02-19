package io.github.slackhappy.poolsdemo;

import java.util.concurrent.CompletableFuture;

public abstract class QueryExecutor {
  public abstract CompletableFuture<QueryResponse> execute(QueryRequest query);
}
