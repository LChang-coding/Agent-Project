package cn.bugstack.ai.domain.rag.service;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RagToolInvocationBudgetStoreTest {

    @Test
    public void shouldAtomicallyLimitRunToThreeInvocations() throws Exception {
        RagToolInvocationBudgetStore store = new RagToolInvocationBudgetStore(3, 8000);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    store.reserve("tenant", "user", "run", 100);
                    return true;
                } catch (RagToolInvocationBudgetStore.BudgetExceededException exception) {
                    return false;
                }
            }));
        }

        start.countDown();
        int accepted = 0;
        for (Future<Boolean> future : futures) if (future.get()) accepted++;
        executor.shutdownNow();

        Assert.assertEquals(3, accepted);
        Assert.assertEquals(3, store.snapshot("tenant", "user", "run").invocations());
    }

    @Test
    public void shouldReconcileReservedTokensAndRollbackFailedInvocation() {
        RagToolInvocationBudgetStore store = new RagToolInvocationBudgetStore(3, 1000);
        RagToolInvocationBudgetStore.Reservation first = store.reserve("tenant", "user", "run", 800);
        first.complete(200);
        RagToolInvocationBudgetStore.Reservation failed = store.reserve("tenant", "user", "run", 700);
        failed.rollback();
        RagToolInvocationBudgetStore.Reservation second = store.reserve("tenant", "user", "run", 800);
        second.complete(800);

        Assert.assertEquals(2, store.snapshot("tenant", "user", "run").invocations());
        Assert.assertEquals(1000, store.snapshot("tenant", "user", "run").consumedTokens());
    }

    @Test
    public void shouldKeepCompletionAndRollbackIdempotentForReplay() {
        RagToolInvocationBudgetStore store = new RagToolInvocationBudgetStore(3, 1000);
        RagToolInvocationBudgetStore.Reservation reservation = store.reserve("tenant", "user", "run", 800);

        reservation.complete(200);
        reservation.complete(200);
        reservation.rollback();

        Assert.assertEquals(new RagToolInvocationBudgetStore.Usage(1, 200),
                store.snapshot("tenant", "user", "run"));
    }

    @Test
    public void shouldAtomicallyEnforceTotalTokenBudget() throws Exception {
        RagToolInvocationBudgetStore store = new RagToolInvocationBudgetStore(20, 1000);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    store.reserve("tenant", "user", "run", 200);
                    return true;
                } catch (RagToolInvocationBudgetStore.BudgetExceededException exception) {
                    return false;
                }
            }));
        }

        start.countDown();
        int accepted = 0;
        for (Future<Boolean> future : futures) if (future.get()) accepted++;
        executor.shutdownNow();

        Assert.assertEquals(5, accepted);
        Assert.assertEquals(new RagToolInvocationBudgetStore.Usage(5, 1000),
                store.snapshot("tenant", "user", "run"));
    }
}
