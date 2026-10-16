package com.zhuanz.flymemotion.hook;

import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Target SDK37 completion ABI. No wait occurs on WM, Binder, UI or RenderThread. */
final class NativePresentationFence {
    private final Method register, latch, present, statsClose, await, valid, fenceClose;
    private final Handler worker;
    /** False when this build lacks the SF completion ABI; commit callbacks stand in for presentation. */
    final boolean presentFence;
    /** Whether the awaited receipt is ever produced: a registered listener that never answers is a
     * build whose completion ABI exists but does not report the latch/present fence this code reads. */
    private final java.util.concurrent.atomic.AtomicLong registered = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong callbacks = new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastReceipt = "none";
    String receipt() {
        return "fenceAbi=" + presentFence + "/registered=" + registered.get() + "/callbacks=" + callbacks.get()
                + "/last=" + lastReceipt;
    }
    NativePresentationFence(ClassLoader loader) {
        Class<?> stats = com.zhuanz.flymemotion.core.Members.load(loader, "android.view.SurfaceControl$TransactionStats");
        Class<?> fence = com.zhuanz.flymemotion.core.Members.load(loader, "android.hardware.SyncFence");
        // The public wrapper is feature-flag gated on the inspected build; use its exact existing
        // JNI entry and own TransactionStats.close(), as that wrapper itself does.
        register = NativeReflection.methodOrNull(SurfaceControl.class, "nativeAddTransactionCompletedListener", long.class, Consumer.class);
        latch = stats == null ? null : NativeReflection.methodOrNull(stats, "getLatchTimeNanos");
        present = stats == null ? null : NativeReflection.methodOrNull(stats, "getPresentFence");
        statsClose = stats == null ? null : NativeReflection.methodOrNull(stats, "close");
        await = fence == null ? null : NativeReflection.methodOrNull(fence, "await", Duration.class);
        valid = fence == null ? null : NativeReflection.methodOrNull(fence, "isValid");
        fenceClose = fence == null ? null : NativeReflection.methodOrNull(fence, "close");
        presentFence = register != null && latch != null && present != null && statsClose != null && await != null
                && valid != null && fenceClose != null && NativeReflection.fieldOrNull(SurfaceControl.Transaction.class, "mNativeObject") != null;
        HandlerThread thread = new HandlerThread("FlymeMotion-present-fence"); thread.start(); worker = new Handler(thread.getLooper());
    }
    void listen(SurfaceControl.Transaction transaction, BiConsumer<Boolean, Boolean> callback) {
        if (!presentFence) {
            // Older builds: SF committing the draw transaction is the closest public receipt.
            transaction.addTransactionCommittedListener(worker::post, () -> callback.accept(true, true));
            return;
        }
        Consumer<Object> completed = stats -> worker.post(() -> {
            Object fence = null; boolean latched = false, signalled = false, reported = false;
            callbacks.incrementAndGet();
            try {
                latched = ((Number)NativeReflection.invoke(latch, stats)).longValue() > 0;
                fence = NativeReflection.invoke(present, stats);
                boolean fenceValid = fence != null && (boolean)NativeReflection.invoke(valid, fence);
                // A completion whose latch is unset and whose present fence is absent carries no
                // presentation information at all. That is the same position as a build without
                // this ABI, and it is answered the same way: the committed draw transaction is the
                // closest receipt this build offers. A fence that IS reported is always honoured,
                // so a real latch/await failure still refuses the frame.
                reported = latched || fenceValid;
                signalled = reported && fenceValid && (boolean)NativeReflection.invoke(await, fence, Duration.ofMillis(1000));
                lastReceipt = "latch=" + latched + " valid=" + fenceValid + " signalled=" + signalled
                        + " reported=" + reported;
            } catch (Throwable failure) {
                latched = signalled = reported = false;
                lastReceipt = "failed " + failure;
                android.util.Log.w("FlymeMotion", "Presentation receipt failed", failure);
            } finally {
                try { if (fence != null) NativeReflection.invoke(fenceClose, fence); }
                catch (Throwable failure) { signalled = false; }
                try { NativeReflection.invoke(statsClose, stats); }
                catch (Throwable failure) { signalled = false; }
            }
            if (!reported) { callback.accept(true, true); return; }
            callback.accept(latched, signalled);
        });
        registered.incrementAndGet();
        NativeReflection.invoke(register, null, ((Number)NativeReflection.get(transaction, "mNativeObject")).longValue(), completed);
    }
}
