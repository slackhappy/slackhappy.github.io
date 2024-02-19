package io.github.slackhappy.poolsdemo;

import com.google.auto.service.AutoService;
import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

@AutoService(BlockHoundIntegration.class)
public class ArmeriaIntegration implements BlockHoundIntegration {
    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.nonBlockingThreadPredicate(current -> current.or(thread -> {
            if (thread.getName() == null) {
                return false;
            }

            return thread.getName().contains("epoll") || thread.getName().contains("nio");
        }));
    }
}
