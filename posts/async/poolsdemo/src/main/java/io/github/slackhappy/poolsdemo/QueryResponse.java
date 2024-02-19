package io.github.slackhappy.poolsdemo;

import java.util.Collections;
import java.util.List;

public class QueryResponse {
  public final QueryRequest request;
  public QueryResponse(QueryRequest request) {
    this.request = request;
  }

  public List<String> documentIds() {
    return Collections.emptyList();
  }
}
