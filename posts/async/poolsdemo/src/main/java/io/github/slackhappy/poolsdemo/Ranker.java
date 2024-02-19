package io.github.slackhappy.poolsdemo;

import java.util.Map;

import com.linecorp.armeria.common.HttpResponse;

public class Ranker {
  public static HttpResponse rerank(Map<String, String> prefetchMeta,  Map<String, String> postfetchMeta, QueryResponse resp) {
    return HttpResponse.of("Done");
  }
}
