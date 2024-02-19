package io.github.slackhappy.poolsdemo;


import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.logging.LoggingService;
import io.micrometer.core.lang.NonNull;

public class FakeRankService {
  public static void main(String[] args){
    ServerBuilder sb = Server.builder();
    sb.http(8081);

    sb.annotatedService(new Object() {
      @LoggingDecorator()
      @Get("/rank")
      public HttpResponse hello(@Param("q") String query, @Param("user") String user) {
        return HttpResponse.of("Hello, q: %s, u: %s!", query, user);
      }
    });
    Server server = sb.build();
    CompletableFuture<Void> future = server.start();
    future.join();
    System.out.println("hello");
  }
}
