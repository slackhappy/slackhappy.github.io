## Adventures in Async Java, Pt 1: Jumping into the Wrong Pool

Asynchronous programming on the JVM is tricky.  I have seen services perform poorly, instrumentation lay blame incorrectly, and even seen unintentional deadlocks arise while debugging async Java and Scala services.

The problems I have seen mostly boil down to confusion about _**who**_ will be running a async task (which thread, from which thread pool configuration), and what the implications of that are. Unfortunately, the `Future` paradigm for async computation, and standard instrumentation and analysis tools make this hard to determine.

One problem occurred in a ranking service.  The ranking service took a basic query, and augmented both the query itself, and the results from the query in order to produce a better set of ranked results for the original query.  It had four main phases, that worked like this:

 1. (concurrently) Look up some metadata from a key-value store, via an async client, based on the user running the query.
 2. (concurrently) Run the query itself in a blocking client.
 3. Look up some more metadata from a key-value store, to augment the results from 2.
 4. Rerank the query results from 2 based on the additional metadata gathered in 1 and 3.

The main query in step 2 had this interface:

```java
public abstract class QueryExecutor {
  public abstract CompletableFuture<QueryResponse> execute(QueryRequest query);
}
```

And approximately this implementation:

```java
public class BlockingQueryExecutor {
   public Client client = ...;
   public CompletebleFuture<QueryResponse> execute(QueryRequest query) {
     CompletableFuture<QueryResponse> cf = new CompletableFuture();
     QueryResponse response = client.runQuery(query);
     cf.complete(response);
     return cf;
   }
}
```

Maybe you can already spot a problem[^1], however this code performed fine in the running system for over a year, so the problem lurked undetected.  I haven't shared **who** was running the blocking query code, and to do that, I'll to introduce some thread pools that were in use, and how the futures were composed.


The service is built on [Armeria](https://armeria.dev/), an RPC framework that is very flexible and adaptable, and generally encourages async service implementations.

Armeria has [two](https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/CommonPools.html) thread pools that can be used to perform the service's work: a `workerGroup` for socket I/O, whose size is generally fixed, a `blockingTaskExecutor` for blocking work.


#### Who: workerGroup thread pool 

On linux, this worker group is an `EpollEventLoopGroup`, and the total number of threads is defaulted to double the number of available processors.  For this service's configuration, that meant 16 threads were assigned to the `workerGroup` for doing socket I/O.  

The idea behind a small, fixed, system-dependent `workerGroup` thread pool for socket I/O is simple:  regardless of how many concurrent requests you are handling, a given host only has so much capability for cooperatively handling the events associated with those requests (socket accept, read some data, write some data).  Having a large number of threads won't increase the throughput, and will only add extra thread scheduling overhead, which impacts request latency.

Underlying this fixed pool reasoning is a basic assumption: that all work scheduled on these pools is non-blocking and mostly constant time in operation (do a socket accept routine, do a non-blocking read up to K[^1] bytes of a buffer that has available data, etc).  Each of these operations performs quickly, and allowing the pool to do other work should an event be triggered.


#### Who: the blockingTaskExecutor thread pool
Now that we have met the `workerGroup` thread pool, it makes sense that we would need an alternative execution model for work that doesn't follow the assumption.  What if you wanted to implement a silly service that returned "hello" after waiting 3 seconds?  If this was implemented as `Thread.sleep`[^2] in the service implementation body, which runs in workerGroup's pool, and the host was a 6-core machine, after just 4 (2 * availableProcessors / 3 secs per thread) concurrent requests per second, the server would no longer have free threads to accept new requests or write responses.  Compute-intensive work could cause similar bottlenecks, especially if the compute time is very different from the fast, effectively constant time socket I/O operations that the workerGroup pool is designed for.

In this service, the blockingTaskExecutor's pool was configured to be pretty large, 200 threads, which was the maximum supported concurrent requests for the service.  Additionally the pool was configured to be backed with SyncronousQueue of fixed size, with an Abort rejected execution policy[^3].



#### "Working" Initial Flow

Prior to the performance issue, here's how the handler code was approximately written:

```java
public void process(T req, StreamObserver<U> respObserver) {
   ctx.blockingTaskExecutor().submit( () -> {
      U resp = apply(req)
      respObserver.onNext(resp)
      respObserver.onCompleted()
   })
}

public void U apply (T req) {
   Future<Map<String,String>> prefetchMetaF = metadataClient.lookupAsync("affinities", req.searcher());
   Future<QueryResponse> qRespF = qExecutor.execute(req.query());
   Future<Map<String,String>> postfetchMetaF = qRespF.thenCompose(qResp ->
       metadataClient.lookupAsync("affinities", qResp.documentIds()))
   
   CompletableFuture.allOf(
       prefetchMetaF,
       qRespF,
       postfetchMetaF
   ).join()
   
   return Ranker.rerank(prefetchMetaF.join(), postfetchMetaF.join(), qRespF.join())
}
```

The `process` and `apply` methods were written in different files, but placing them side by side here makes it easier to tell who ran which piece.  The `process` method is part of the evented grpc handler implementation so the body of `process` itself was run by socket I/O `workerPool`.  

In the body of `process`, the meaningful work, especially the body of `apply`, is immediately transferred to the `blockingTaskExecutor` pool:

 - Creating the request for prefetch metadata (though the actual request and response will be handled on the socket I/O `workerPool`
 - Running the query itself on the blocking client listed above.  The fact that `process` runs `apply` in a blockingTaskExecutor is what saves the service from accidentally running on the socket I/O pool and clogging it.  Whew.
 - Composing the postfetch future, which is set to run when the response comes back from the blocking query client

 - The join and rerank code that runs after the all the data is fetched.

The code isn't a great example of async composition, but it is tested, and passes code review from someone more concerned with the ranking logic and test plan than perfect code style.  And, the service competently reranks query results for a year.

#### New feature: Synonyms!
When using search for recalling an existing document, a common reason for a no-result query is the user can't precisely recall the terminology used.  By augmenting the query with synonyms, recall can be improved with some sacrifice to precision.  The synonym data was loaded into the key-value store and augmented the query pre-fetch.  Not all queries need a synonym prefetch.  For example, a large portion of queries are filter-only queries which have no terms to fetch synonyms for, so the query augmentation is skipped in that case. Here's the diff to `apply`:

```diff
   Future<Map<String,String>> prefetchMetaF = metadataClient.lookupAsync("affinities", req.searcher());
-  Future<QueryResponse> qRespF = qExecutor.execute(req.query());
+  Future<Map<String,String>> synonymsF;
+  if (req.query().synonymsEligible()) {
+      metadataClient.lookupAsync("synonyms", req.query().terms());
+  } else {
+     synonymsF = CompletableFuture.completedFuture(Collections.emptyMap());
+  }
+  Future<QueryResponse> qRespF = synonymsF.thenCompose(synonyms -> {
+      req.query().addSynonyms(synonyms);
+      qExecutor.execute(req.query())
+  });
   qExecutor.execute(req.query());
   Future<Map<String,String>> postfetchMetaF = qRespF.thenCompose(qResp ->
```

The code passes tests, review, and is released to production.  Weirdly, the p95 timings associated with the metadataClient, which is the async client for the key-value store go way up.  A typical key-value store request takes about 1-3ms.  The p95 was showing 200ms.  Additionally, overall p95 end-to-end request times rose from 500ms to 1s.  "Oh well - the code just added a new call to look up synonyms, so maybe that makes sense", one could rationalize.  Anyway, the search queries are mostly performing as expected, and reproducing the slow queries is hard - rerunning a query that had a long key-value timing doesn't appear to reproduce that long key-value timing.  So maybe it is a load issue on the key-value servers?

But... the graphs for the key-value servers don't seem to show an increase in load, or a increase in latency.  Something is up.  

Maybe you already know what's up.  In that innocous-seeming diff, the _**who**_ running the blocking query changed when a query is eligible for synonym expansion!

If the service runs synonym expansion, the main query now runs after the synonym data is retrieved.  The synonym data is fetched via an async client running on the socket I/O `workerPool`.  The thread from that pool that handles the completion event from the synonym response will also dependent async work that has been sequenced via Future composition.  That means a `workerPool` thread, not a `blockingTaskExecutor` thread, will now run the blocking query!  In the introduction I explained how the `workerPool` has a small, fixed size number of threads and is intended for non-blocking work.  

Typical query times are around 200-500ms, probably 1000x longer than typical non-blocking event handling code running in that pool.   I forget the exact statistics, but if a single instance was servig 60 synonym-eligible queries per second, and an average query took 200ms, and there were 16 worker threads, there's about 20% chance that some non-blocking work will have to wait for some time for a worker thread to become available[^4].

Since non-blocking I/O was occasionally queued, this showed up as latency in async requests.  Sending off a request and receiving a response are separate events.  A timer started when the request is sent, and stopped when the response is received isn't anticipating any time taken waiting on the queue for a free thread to service the response received event.  As a result, when the blocking query's work moved to the `workerPool`, random, unrelated key-value requests appeared to take a long time, because that execution model did not anticipate occasional but significant time spent on the queue waiting for a free thread.


With the graphs we had, it wasn't obvious what was going on.  There was performance degradation.  The smoking gun was a `jstack` capture:



```
"armeria-common-worker-epoll-2-7" 
    java.lang.Thread.State: RUNNABLE
        at java.net.SocketInputStream.socketRead0(Native Method)
        java.net.SocketInputStream.socketRead(SocketInputStream.java:115)   
        [SNIP]
        at Client.runQuery(Client.java:)
        at BlockingQueryExecutor.execute(BlockingQueryExecutor.execute:)
        at java.util.concurrent.CompletableFuture$UniCompose.tryFire(CompletableFuture.java:1072)
        at java.util.concurrent.CompletableFuture.postComplete(CompletableFuture.java:506)
        at java.util.concurrent.CompletableFuture.complete(CompletableFuture.java:2073)
        at com.linecorp.armeria.common.HttpMessageAggregator.onComplete(HttpMessageAggregator.java:98)
        at com.linecorp.armeria.common.stream.DeferredStreamMessage$ForwardingSubscriber.onComplete(DeferredStreamMessage.java:334)
        [SNIP]
        at com.linecorp.armeria.client.HttpResponseDecoder$HttpResponseWrapper.closeAction(HttpResponseDecoder.java:276)",
        [SNIP]
        at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:379)
        at io.netty.channel.epoll.EpollEventLoop.processReady(EpollEventLoop.java:475)
        at io.netty.channel.epoll.EpollEventLoop.run(EpollEventLoop.java:378)
        [SNIP]
        
```
A relevant stack trace has been extracted and trimmed from the trace.  The thread name is `armeria-common-worker-epoll-2-7`, indicating that it comes from the socket I/O workerPool.  On linux, the events are managed with `epoll` commands.  At the top of the trace for this thread, in the current frame, is a blocking call to `SocketInputStream.read`, so we are doing blocking work in a thread pool designed for non-blocking work.  

In this case, culprit is fairly clear, but The rest of the trace (reading from bottom to top) has some clues about how we got there:  an epoll event fired.  In the event's associated netty channel, the data was read.  That channel was in turn associated with an armeria http client (our key-value store async http client).  The closeAction/onComplete method names suggest that the event read the entire response and the Completeable future that had triggered this async http client read was now running any subsequent work now that the async http response was finished, leading us to the code that runs the blocking read.  

The trace hammers home the concept that, unless explicitly configured, the _**who**_ that runs an async task is determined by the dynamic events of the system[^5].  In this case the conditional block determining eligibility is one clear factor in determining the runtime thread pool of the `BlockingQueryExecutor.execute` work, but other conditions could affect the runner.  What if the key-value client could not discover any servers to send to?  In our case, the client was configured to just return an empty map and continue.  In that case, the blocking work would likely have remained on the blocking task executor, since no socket I/O to perform the key-value lookup would have been made.  Even a careful, expert read of the code may not uncover the full set of circumstances that determine who will run an async task.


#### Fixing the bug and a style recommendation


Once identified, fixing the issue was simple: enqueue the blocking work on to the `blockingTaskExecutor` pool.

Here's the change to `BlockingQueryExecutor` from the second code listing above (the class name was also changed)[^6]: 

```diff
+  import com.linecorp.armeria.common.RequestContext
+  
   public CompleteableFuture<QueryResponse> execute(QueryRequest query) {
-    CompleteableFuture<QueryResponse> cf = new CompleteableFuture();
-    QueryResponse response = client.runQuery(query);
-    cf.complete(query);
+    return CompleteableFuture.supplyAsync(() -> {
+       return client.runQuery(query)
+    },  RequestContext.mapCurrent(ctx -> ctx.blockingTaskExecutor, ForkJoinPool::commonPool);
   }
}
```

There are also other potential fixes.  For example, the query client could be rewritten to be based on a fully asynchronous libraries, or the main response composition code in `apply` could have been altered to forcibly run `qExecutor.execute` in a `blockingTaskExecutor`.  

The fix chosen above is good one because it restores a useful invariant to have in async code:

<div style="border: 2px solid; padding: 10px">
If a method returns a Future, it should either be trivially non-blocking, or it should be responsible for enqueuing its own work on to an appropriate pool.
</div>

Often async tasks composed in a different file from where they are implemented, as was the case here.  A user should be able to compose dependent asynchronous work as they see fit, such as adding synonym metadata prior to executing a query, without fear of perturbing a delicate balance of which pool runs which work.  The implementation of a block or method that returns a future should either be so trivial that it doesn't matter where it is executed (such as returning a constant value, or some other effectively constant-time work[^7]), or it should manage the execution of its own work by submitting it to an appropriate worker pool.  Anything else is a trap that may spring a year later.


#### Future-proofing with instrumentation

I have seen a version of the above problem many times:  In both Scala and Java, in RPC frameworks like Finagle and Armeria.  It is an easy mistake to make, and can be hard to catch in a code review.  It may cause subtle performance degredations, or maybe no performance issues at all - yet.

A more reliable way to prevent these kinds of issues is with dedicated instrumentation.  I asked Trustin Lee, the founder of netty and armeria, if he had any advice.  He suggested trying [BlockHound](https://github.com/reactor/BlockHound), a java agent that detects blocking operations run in non-blocking thread pools.





[^1]: The author of the code knew about the pitfall, and was trying to work around an issue that I might cover in a future post, but that author had left and the context dissipated from the team's memory.

[^1]: I'm not entirely sure what the fixed size read/write buffers are, but I suspect that far enough down the stack there is a small-ish maximum amount of data that can be read at one time that is trivial to process compared to, say, a compute-intensive task.

[^2]: Instead of `Thread.sleep`, there are plenty of non-blocking ways of scheduling deferred work.  Armeria's `workerGroup` implements [ScheduledExecutorService](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html), which has a `schedule` function which returns a `Future`.  I'm just using `Thread.sleep` as a simple example of blocking work.

[^3]: A fixed-size `SynchronousQueue` with an Abort rejected execution policy is my go-to recommendation for a non-evented, latency-sensitive online work queue.  It follows the recommendations of [ThreadPoolExecutor's own documentation](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) in the queueing section at the top of the doc.  When using alternatives, particularly with composable async constructs like Futures, it is possible to get into a deadlock - maybe I'll make another post on that.


[^4]: I'm using the "probability that an arriving customer is forced to join the queue" from this M/M/c queueing analysis on [wikipedia's M/M/c queue page](https://en.wikipedia.org/wiki/M/M/c_queue), λ = 60, μ = 5, c = 16:

   ```python
    >>> l = 60
    >>> u = 5
    >>> c = 16
    >>> p = l / (c * u)
    >>> 1 / (1 + (1 - p) * (math.factorial(c) / math.pow(c * p, c)) * sum([math.pow(c * p, k)/math.factorial(k) for k in range(0, c)]))
    0.20457385843573994
    ```

[^5]: As the [CompleteableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html) documentation says, _"Actions supplied for dependent completions of non-async methods may be performed by the thread that completes the current CompletableFuture, or by any other caller of a completion method"_ (note in this case their differentiation between async and non-async methods are methods with async present or absent in the name - so `complete` is considered non-async, while `supplyAsync` is considered async).  I actually think this documentation is confusing, since all the "async" methods are doing is hiding the creation of the dependent task and its submission to the specified pool.  I think it would be more straightforward to have simply have "non-async" methods where the body of the task does the work of enqueuing into the correct pool, instead of having "async" methods encapsulate that same behavior.

[^6]: `RequestContext` statically accesses information about an armeria request.  The fallback, `ForkJoinPool.common`, is used in unit tests where no request context is present.  RequestContext information is available from a ThreadLocal. As discussed above, in an async service, many threads handle many parts of many requests.  How the request context is managed and passed along to dependent tasks within a request would be an interesting post.  Additionally, `ForkJoinPool` would be a usually be a poor choice for an typical online service.  Most work performed by an online service is not fork-join in nature - most services are not doing distributed integration and matrix multiplication.  For more info, see [Doug Lea's paper](http://gee.cs.oswego.edu/dl/papers/fj.pdf) that motivated the inclusion in Java.   If the fallback pool were actually triggered in production, a better choice would be a `SynchronousQueue`-backed `ThreadPoolExecutor`.


[^7]: When does a non-blocking task cross the boundary between "effectively constant-time" and compute-intensive?  It probably depends on your expected throughput and your workload mix.  In my experience, a common operation in an proxy-style service is to take a set of returned results, maybe up to 10,000, sort by some inner field, and return some projection of the results.  I haven't had throughput issues when running that kind of operation on the worker pool intended for socket I/O.  A mock up of that type of operation running on my laptop (likely no JITing) took about 2.5ms.  That is probably where I'd set the boundary for "trivial" post-processing code.  Of course, any code that could potentially block on I/O should be based on async primitives, or be explicitly scheduled into an appropriate pool.

    ```scala
    scala> case class Foo(x: Int)
    scala> val r = new java.util.Random
    scala> val a = Vector.fill(10000)(Foo(r.nextInt))
    scala> val avgMicros = (0 until 100).map(_ => { val s = System.nanoTime; a.sortBy(_.x); val e = System.nanoTime; (e - s)/1000 }).sum / 100
    avgMicros: Long = 2392
    ```