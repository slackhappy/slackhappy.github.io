package io.github.slackhappy.poolsdemo;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;

public class MetadataClient {
  WebClient client;
  public MetadataClient() {
    this.client = WebClient.of("http", Endpoint.of("127.0.0.1", 8080));
  }

  public CompletableFuture<Map<String, String>> lookupAsync(String collection, String key) {
    System.out.println("yo1");
    return lookupAsync(collection, Collections.singletonList(key));
  }
  public CompletableFuture<Map<String, String>> lookupAsync(String collection, List<String> keys) {
    System.out.println("yo2");
    List<CompletableFuture<Map<String, String>>> responses = keys.stream().map(key -> {
      System.out.println("preget " + "/kv/" + collection + "/" + key);
      HttpResponse res = client.get("/kv/" + collection + "/" + key);
      System.out.println("getting " + "/kv/" + collection + "/" + key);
      return res.aggregate().thenApply(agg -> {
        Map<String, String> kv = new HashMap<>();
        System.out.println("got " + "/kv/" + collection + "/" + key + ":" + agg.contentUtf8());
        kv.put(key, agg.contentUtf8());
        return kv;
      });
    }).collect(Collectors.toList());

    return CompletableFuture.allOf(responses.toArray(new CompletableFuture[0])).thenApply(x -> {
      System.out.println("thenapply");
      Map<String, String> kv = new HashMap<>();
      responses.forEach(responseF -> kv.putAll(responseF.join()));
      return kv;
    });
  }
}
