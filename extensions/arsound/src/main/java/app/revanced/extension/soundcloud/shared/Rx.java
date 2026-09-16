package app.revanced.extension.soundcloud.shared;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;

/**
 * Minimal reflective access to the RxJava 3 copy bundled in SoundCloud.
 * <p>
 * R8 renames most operator methods, so they are found by their unobfuscated operator return
 * classes (for example {@code ObservableFilter}) and parameter types, not by name.
 */
public final class Rx {
    private static final String CORE = "io.reactivex.rxjava3.core.";
    private static final String FUNCTIONS = "io.reactivex.rxjava3.functions.";
    private static final String OPERATORS = "io.reactivex.rxjava3.internal.operators.observable.";

    private Rx() {
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /** Implements a single-method RxJava functional interface with a Java lambda. */
    private static Object function(ClassLoader loader, String interfaceName, java.util.function.Function<Object[], Object> body)
            throws ClassNotFoundException {
        Class<?> type = type(loader, FUNCTIONS + interfaceName);
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "Arsound" + interfaceName;
                }
            }
            return body.apply(args);
        });
    }

    private static Method find(Class<?> owner, boolean isStatic, String returnSimpleName, Class<?>... parameters) {
        for (Method method : owner.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) != isStatic) continue;
            if (!method.getReturnType().getSimpleName().equals(returnSimpleName)) continue;
            if (java.util.Arrays.equals(method.getParameterTypes(), parameters)) return method;
        }
        throw new IllegalStateException("RxJava method not found: " + returnSimpleName);
    }

    /**
     * Emits {@code local} until {@code remote} emits its first item, and {@code remote} from then on.
     * Behaves like {@code merge(local.takeUntil(remote), remote)}, without needing operators R8 removed.
     */
    public static Object localUntilRemote(Object local, Object remote) throws Exception {
        ClassLoader loader = remote.getClass().getClassLoader();
        Class<?> observable = type(loader, CORE + "Observable");
        Class<?> consumer = type(loader, FUNCTIONS + "Consumer");
        Class<?> action = type(loader, FUNCTIONS + "Action");
        Class<?> predicate = type(loader, FUNCTIONS + "Predicate");
        Class<?> source = type(loader, CORE + "ObservableSource");

        java.util.concurrent.atomic.AtomicBoolean remoteEmitted = new java.util.concurrent.atomic.AtomicBoolean();

        Object markRemote = function(loader, "Consumer", args -> {
            remoteEmitted.set(true);
            return null;
        });
        Object ignore = function(loader, "Consumer", args -> null);
        Object noAction = function(loader, "Action", args -> null);
        Object untilRemote = function(loader, "Predicate", args -> !remoteEmitted.get());

        // doOnEach(onNext, onError, onComplete, onAfterTerminate)
        Object markedRemote = find(observable, false, "ObservableDoOnEach", consumer, consumer, action, action)
                .invoke(remote, markRemote, ignore, noAction, noAction);
        Object filteredLocal = find(observable, false, "ObservableFilter", predicate)
                .invoke(local, untilRemote);

        Method merge = null;
        for (Method method : observable.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != observable) continue;
            if (!java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{source, source})) continue;
            if (isMerge(method)) {
                merge = method;
                break;
            }
        }
        if (merge == null) throw new IllegalStateException("RxJava merge not found");
        return merge.invoke(null, filteredLocal, markedRemote);
    }

    /** Several static (ObservableSource, ObservableSource) methods exist; merge is the one that interleaves. */
    private static boolean isMerge(Method method) {
        // In the 2026.09.02 build merge is "F". Concat and ambiguity helpers are named differently.
        return method.getName().equals("F");
    }

    public static Object mapSingle(Object single, java.util.function.Function<Object, Object> mapper) throws Exception {
        return map(single, "Single", "SingleMap", mapper);
    }

    public static Object mapObservable(Object observable, java.util.function.Function<Object, Object> mapper) throws Exception {
        return map(observable, "Observable", "ObservableMap", mapper);
    }

    private static Object map(Object source, String baseName, String operatorName,
                              java.util.function.Function<Object, Object> mapper) throws Exception {
        ClassLoader loader = source.getClass().getClassLoader();
        Class<?> base = type(loader, CORE + baseName);
        Class<?> functionClass = type(loader, FUNCTIONS + "Function");
        Object function = function(loader, "Function", args -> mapper.apply(args[0]));
        return find(base, false, operatorName, functionClass).invoke(source, function);
    }

    /**
     * Waits for the first item of an Observable, Single or Maybe.
     *
     * @return The item, or null on error, completion without items or timeout.
     */
    public static Object blockingFirst(Object reactive, long timeout, java.util.concurrent.TimeUnit unit) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Object> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> disposable = new java.util.concurrent.atomic.AtomicReference<>();

        ClassLoader loader = reactive.getClass().getClassLoader();
        Class<?> consumer = type(loader, FUNCTIONS + "Consumer");
        Object onItem = function(loader, "Consumer", args -> {
            result.compareAndSet(null, args[0]);
            latch.countDown();
            return null;
        });
        Object onError = function(loader, "Consumer", args -> {
            latch.countDown();
            return null;
        });

        for (Method method : reactive.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("subscribe") && parameters.length == 2
                    && parameters[0] == consumer && parameters[1] == consumer) {
                disposable.set(method.invoke(reactive, onItem, onError));
                break;
            }
        }
        latch.await(timeout, unit);

        Object subscription = disposable.get();
        if (subscription != null) {
            try {
                subscription.getClass().getMethod("dispose").invoke(subscription);
            } catch (Exception ignored) {
            }
        }
        return result.get();
    }

    /** Subscribes to a Completable, Single or Observable and ignores its result and errors. */
    public static void subscribeIgnoringErrors(Object reactive, Consumer<Throwable> onError) {
        try {
            ClassLoader loader = reactive.getClass().getClassLoader();
            Class<?> consumer = type(loader, FUNCTIONS + "Consumer");
            Object errorConsumer = function(loader, "Consumer", args -> {
                if (args != null && args.length == 1 && args[0] instanceof Throwable) onError.accept((Throwable) args[0]);
                return null;
            });

            for (Method method : reactive.getClass().getMethods()) {
                if (!method.getName().equals("subscribe")) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 2 || parameters[1] != consumer) continue;

                Object first = parameters[0] == consumer
                        ? function(loader, "Consumer", args -> null)
                        : function(loader, "Action", args -> null);
                method.invoke(reactive, first, errorConsumer);
                return;
            }
            throw new IllegalStateException("subscribe(onSuccess, onError) not found on " + reactive.getClass());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not subscribe", ex);
        }
    }
}
