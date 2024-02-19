package io.github.slackhappy.poolsdemo;


import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import io.micrometer.core.lang.NonNull;

public class FakeKeyValueServer {
  public static void main(String[] args){
    ServerBuilder sb = Server.builder();
    sb.http(8080);

    // Using path variables:
    sb.service("/kv/{collection}/{key}", new AbstractHttpService() {
      @Override
      protected @NonNull HttpResponse doGet(@NonNull ServiceRequestContext ctx, @NonNull HttpRequest req) {
        String key = ctx.pathParam("key");
        return HttpResponse.of("Hello, %s!", key);
      }
    }.decorate(LoggingService.newDecorator())); // Enable logging

    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();
    System.out.println("hello");
  }
}
